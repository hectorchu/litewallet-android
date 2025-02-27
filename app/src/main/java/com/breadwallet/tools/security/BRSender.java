package com.breadwallet.tools.security;

import android.app.Activity;
import android.content.Context;

import androidx.fragment.app.FragmentActivity;

import com.breadwallet.R;
import com.breadwallet.presenter.customviews.BRDialogView;
import com.breadwallet.presenter.entities.PaymentItem;
import com.breadwallet.presenter.interfaces.BRAuthCompletion;
import com.breadwallet.tools.animation.BRAnimator;
import com.breadwallet.tools.animation.BRDialog;
import com.breadwallet.tools.manager.BRApiManager;
import com.breadwallet.tools.manager.BRSharedPrefs;
import com.breadwallet.tools.threads.BRExecutor;
import com.breadwallet.tools.util.BRConstants;
import com.breadwallet.tools.util.BRCurrency;
import com.breadwallet.tools.util.BRExchange;
import com.breadwallet.tools.util.Utils;
import com.breadwallet.wallet.BRWalletManager;
import com.breadwallet.tools.manager.AnalyticsManager;

import java.math.BigDecimal;
import java.util.Locale;

import timber.log.Timber;

public class BRSender {
    private static BRSender instance;
    private final static long FEE_EXPIRATION_MILLIS = 72 * 60 * 60 * 1000L;
    private boolean timedOut;
    private boolean sending;

    private BRSender() {
    }

    public static BRSender getInstance() {
        if (instance == null) instance = new BRSender();
        return instance;
    }

    /**
     * Create tx from the PaymentItem object and try to send it
     */
    public void sendTransaction(final Context app, final PaymentItem request) {
        //array in order to be able to modify the first element from an inner block (can't be final)
        final String[] errTitle = {null};
        final String[] errMessage = {null};
        BRExecutor.getInstance().forLightWeightBackgroundTasks().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sending) {
                        Timber.e(new NullPointerException("sendTransaction returned because already sending.."));
                        return;
                    }
                    sending = true;
                    long now = System.currentTimeMillis();
                    //if the fee was updated more than 24 hours ago then try updating the fee
                    if (now - BRSharedPrefs.getFeeTime(app) >= FEE_EXPIRATION_MILLIS) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(3000);
                                } catch (InterruptedException e) {
                                    Timber.e(e);
                                }

                                if (sending) timedOut = true;
                            }
                        }).start();
                        BRApiManager.updateFeePerKb(app);
                        //if the fee is STILL out of date then fail with network problem message
                        long time = BRSharedPrefs.getFeeTime(app);
                        if (time <= 0 || now - time >= FEE_EXPIRATION_MILLIS) {
                            Timber.d("timber: sendTransaction: fee out of date even after fetching...");

                            AnalyticsManager.logCustomEvent(BRConstants._20200111_FNI);

                            throw new FeeOutOfDate(time, now);
                        }
                    }
                    if (!timedOut)
                        tryPay(app, request);
                    else
                        Timber.e(new NullPointerException("did not send, timedOut!"));
                    return; //return so no error is shown
                } catch (InsufficientFundsException ignored) {
                    errTitle[0] = app.getString(R.string.Alerts_sendFailure);
                } catch (AmountSmallerThanMinException e) {
                    long minAmount = BRWalletManager.getInstance().getMinOutputAmountRequested();
                    errTitle[0] = app.getString(R.string.Alerts_sendFailure);
                    errMessage[0] = String.format(Locale.getDefault(), app.getString(R.string.PaymentProtocol_Errors_smallPayment),
                            BRConstants.bitcoinLowercase + new BigDecimal(minAmount).divide(new BigDecimal(100), BRConstants.ROUNDING_MODE));
                } catch (SpendingNotAllowed spendingNotAllowed) {
                    showSpendNotAllowed(app);
                    return;
                } catch (FeeNeedsAdjust feeNeedsAdjust) {
                    //offer to change amount, so it would be enough for fee
                    showAdjustFee((Activity) app, request);
                    return;
                } catch (FeeOutOfDate ex) {
                    //Fee is out of date, show not connected error
                    Timber.e(ex);
                    BRExecutor.getInstance().forMainThreadTasks().execute(new Runnable() {
                        @Override
                        public void run() {
                            BRDialog.showCustomDialog(app, app.getString(R.string.Alerts_sendFailure), app.getString(R.string.NodeSelector_notConnected), app.getString(R.string.Button_ok), null, new BRDialogView.BROnClickListener() {
                                @Override
                                public void onClick(BRDialogView brDialogView) {
                                    brDialogView.dismiss();
                                }
                            }, null, null, 0);
                        }
                    });
                    return;
                } finally {
                    sending = false;
                    timedOut = false;
                }

                //show the message if we have one to show
                if (errTitle[0] != null && errMessage[0] != null)
                    BRExecutor.getInstance().forMainThreadTasks().execute(new Runnable() {
                        @Override
                        public void run() {
                            BRDialog.showCustomDialog(app, errTitle[0], errMessage[0], app.getString(R.string.Button_ok), null, new BRDialogView.BROnClickListener() {
                                @Override
                                public void onClick(BRDialogView brDialogView) {
                                    brDialogView.dismiss();
                                }
                            }, null, null, 0);
                        }
                    });
            }
        });
    }

    /**
     * Try transaction and throw appropriate exceptions if something was wrong
     * BLOCKS
     */
    private void tryPay(final Context app, final PaymentItem paymentRequest) throws InsufficientFundsException,
            AmountSmallerThanMinException, SpendingNotAllowed, FeeNeedsAdjust {
        if (paymentRequest == null || paymentRequest.addresses == null) {
            Timber.d("timber: handlePay: WRONG PARAMS");
            String message = paymentRequest == null ? "paymentRequest is null" : "addresses is null";
            RuntimeException ex = new RuntimeException("paymentRequest is malformed: " + message);

            Timber.e(ex);
            throw ex;
        }
        long amount = paymentRequest.amount;
        long balance = BRWalletManager.getInstance().getBalance(app);
        final BRWalletManager m = BRWalletManager.getInstance();
        long minOutputAmount = BRWalletManager.getInstance().getMinOutputAmount();
        final long maxOutputAmount = BRWalletManager.getInstance().getMaxOutputAmount();

        // check if spending is allowed
        if (!BRSharedPrefs.getAllowSpend(app)) {
            throw new SpendingNotAllowed();
        }

        //check if amount isn't smaller than the min amount
        if (isSmallerThanMin(paymentRequest)) {
            throw new AmountSmallerThanMinException(amount, balance);
        }

        //amount is larger than balance
        if (isLargerThanBalance(app, paymentRequest)) {
            throw new InsufficientFundsException(amount, balance);
        }

        //not enough for fee
        if (notEnoughForFee(paymentRequest)) {
            //weird bug when the core BRWalletManager is NULL
            if (maxOutputAmount == -1) {
                RuntimeException ex = new RuntimeException("getMaxOutputAmount is -1, meaning _wallet is NULL");
                Timber.e(ex);
                throw ex;
            }
            // max you can spend is smaller than the min you can spend
            if (maxOutputAmount < minOutputAmount) {
                throw new InsufficientFundsException(amount, balance);
            }

            long feeForTx = m.feeForTransaction(paymentRequest.addresses[0], paymentRequest.amount);
            throw new FeeNeedsAdjust(amount, balance, feeForTx);
        }
        // payment successful
        BRExecutor.getInstance().forLightWeightBackgroundTasks().execute(new Runnable() {
            @Override
            public void run() {
                byte[] tmpTx = m.tryTransaction(paymentRequest.addresses[0], paymentRequest.amount);
                if (tmpTx == null) {
                    //something went wrong, failed to create tx
                    ((Activity) app).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            BRDialog.showCustomDialog(app, "", app.getString(R.string.Alerts_sendFailure), app.getString(R.string.AccessibilityLabels_close), null, new BRDialogView.BROnClickListener() {
                                @Override
                                public void onClick(BRDialogView brDialogView) {
                                    brDialogView.dismiss();
                                }
                            }, null, null, 0);

                        }
                    });
                    return;
                }
                paymentRequest.serializedTx = tmpTx;
                PostAuth.getInstance().setPaymentItem(paymentRequest);
                confirmPay(app, paymentRequest);
            }
        });
    }

    private void showAdjustFee(final Activity app, PaymentItem item) {
        BRWalletManager m = BRWalletManager.getInstance();
        long maxAmountDouble = m.getMaxOutputAmount();
        if (maxAmountDouble == -1) {
            Timber.e(new RuntimeException("getMaxOutputAmount is -1, meaning _wallet is NULL"));
            return;
        }
        if (maxAmountDouble == 0) {
            BRDialog.showCustomDialog(app, app.getString(R.string.Alerts_sendFailure), "Insufficient amount for transaction fee", app.getString(R.string.Button_ok), null, new BRDialogView.BROnClickListener() {
                @Override
                public void onClick(BRDialogView brDialogView) {
                    brDialogView.dismissWithAnimation();
                }
            }, null, null, 0);
        } else {
            BRDialog.showCustomDialog(app, app.getString(R.string.Alerts_sendFailure), "Insufficient amount for transaction fee", app.getString(R.string.Button_ok), null, new BRDialogView.BROnClickListener() {
                @Override
                public void onClick(BRDialogView brDialogView) {
                    brDialogView.dismissWithAnimation();
                }
            }, null, null, 0);
            //todo fix this fee adjustment
        }
    }

    private void confirmPay(final Context ctx, final PaymentItem request) {
        if (ctx == null) {
            Timber.i("timber: confirmPay: context is null");
            return;
        }

        String message = createConfirmation(ctx, request);

        double minOutput;
        if (request.isAmountRequested) {
            minOutput = BRWalletManager.getInstance().getMinOutputAmountRequested();
        } else {
            minOutput = BRWalletManager.getInstance().getMinOutputAmount();
        }

        //amount can't be less than the min
        if (request.amount < minOutput) {
            final String bitcoinMinMessage = String.format(Locale.getDefault(), ctx.getString(R.string.PaymentProtocol_Errors_smallTransaction),
                    BRConstants.bitcoinLowercase + new BigDecimal(minOutput).divide(new BigDecimal("100")));


            ((Activity) ctx).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BRDialog.showCustomDialog(ctx, ctx.getString(R.string.Alerts_sendFailure), bitcoinMinMessage, ctx.getString(R.string.AccessibilityLabels_close), null, new BRDialogView.BROnClickListener() {
                        @Override
                        public void onClick(BRDialogView brDialogView) {
                            brDialogView.dismiss();
                        }
                    }, null, null, 0);
                }
            });
            return;
        }
        boolean forcePin = false;

        Timber.d("timber: confirmPay: totalSent: %s", BRWalletManager.getInstance().getTotalSent());
        Timber.d("timber: confirmPay: request.amount: %s", request.amount);
        Timber.d("timber: confirmPay: total limit: %s", AuthManager.getInstance().getTotalLimit(ctx));
        Timber.d("timber: confirmPay: limit: %s", BRKeyStore.getSpendLimit(ctx));

        if (BRWalletManager.getInstance().getTotalSent() + request.amount > AuthManager.getInstance().getTotalLimit(ctx)) {
            forcePin = true;
        }

        //successfully created the transaction, authenticate user
        AuthManager.getInstance().authPrompt(ctx, "", message, forcePin, false, new BRAuthCompletion() {
            @Override
            public void onComplete() {
                BRExecutor.getInstance().forLightWeightBackgroundTasks().execute(new Runnable() {
                    @Override
                    public void run() {
                        PostAuth.getInstance().onPublishTxAuth(ctx, false);
                        BRExecutor.getInstance().forMainThreadTasks().execute(new Runnable() {
                            @Override
                            public void run() {
                                BRAnimator.killAllFragments((FragmentActivity) ctx);
                                BRAnimator.startBreadIfNotStarted((Activity) ctx);
                            }
                        });

                    }
                });
            }

            @Override
            public void onCancel() {
                //nothing
            }
        });
    }

    private String createConfirmation(Context ctx, PaymentItem request) {
        String receiver = getReceiver(request);

        String iso = BRSharedPrefs.getIso(ctx);

        BRWalletManager m = BRWalletManager.getInstance();
        long feeForTx = m.feeForTransaction(request.addresses[0], request.amount);
        if (feeForTx == 0) {
            long maxAmount = m.getMaxOutputAmount();
            if (maxAmount == -1) {
                RuntimeException ex = new RuntimeException("getMaxOutputAmount is -1, meaning _wallet is NULL");
                Timber.e(ex);
                throw ex;
            }
            if (maxAmount == 0) {
                BRDialog.showCustomDialog(ctx, "", ctx.getString(R.string.Alerts_sendFailure), ctx.getString(R.string.AccessibilityLabels_close), null, new BRDialogView.BROnClickListener() {
                    @Override
                    public void onClick(BRDialogView brDialogView) {
                        brDialogView.dismiss();
                    }
                }, null, null, 0);

                return null;
            }
            feeForTx = m.feeForTransaction(request.addresses[0], maxAmount);
            feeForTx += (BRWalletManager.getInstance().getBalance(ctx) - request.amount) % 100;
        }
        final long total = request.amount + feeForTx;
        String formattedAmountBTC = BRCurrency.getFormattedCurrencyString(ctx, "LTC", BRExchange.getBitcoinForSatoshis(ctx, new BigDecimal(request.amount)));
        String formattedFeeBTC = BRCurrency.getFormattedCurrencyString(ctx, "LTC", BRExchange.getBitcoinForSatoshis(ctx, new BigDecimal(feeForTx)));
        String formattedTotalBTC = BRCurrency.getFormattedCurrencyString(ctx, "LTC", BRExchange.getBitcoinForSatoshis(ctx, new BigDecimal(total)));

        String formattedAmount = BRCurrency.getFormattedCurrencyString(ctx, iso, BRExchange.getAmountFromSatoshis(ctx, iso, new BigDecimal(request.amount)));
        String formattedFee = BRCurrency.getFormattedCurrencyString(ctx, iso, BRExchange.getAmountFromSatoshis(ctx, iso, new BigDecimal(feeForTx)));
        String formattedTotal = BRCurrency.getFormattedCurrencyString(ctx, iso, BRExchange.getAmountFromSatoshis(ctx, iso, new BigDecimal(total)));

        //formatted text
        return receiver + "\n\n"
                + ctx.getString(R.string.Confirmation_amountLabel) + " " + formattedAmountBTC + " (" + formattedAmount + ")"
                + "\n" + ctx.getString(R.string.Confirmation_feeLabel) + " " + formattedFeeBTC + " (" + formattedFee + ")"
                + "\n" + ctx.getString(R.string.Confirmation_totalLabel) + " " + formattedTotalBTC + " (" + formattedTotal + ")"
                + (request.comment == null ? "" : "\n\n" + request.comment);
    }

    String getReceiver(PaymentItem item) {
        boolean certified = item.cn != null && item.cn.length() != 0;
        return certified ? "certified: " + item.cn : Utils.join(item.addresses, ", ");
    }

    private boolean isSmallerThanMin(PaymentItem paymentRequest) {
        long minAmount = BRWalletManager.getInstance().getMinOutputAmountRequested();
        return paymentRequest.amount < minAmount;
    }

    private boolean isLargerThanBalance(Context app, PaymentItem paymentRequest) {
        return paymentRequest.amount > 0 && paymentRequest.amount > BRWalletManager.getInstance().getMaxOutputAmount();
    }

    private boolean notEnoughForFee(PaymentItem paymentRequest) {
        BRWalletManager m = BRWalletManager.getInstance();
        long feeForTx = m.feeForTransaction(paymentRequest.addresses[0], paymentRequest.amount);
        if (feeForTx == 0) {
            //feeForTx = m.feeForTransaction(paymentRequest.addresses[0], m.getMaxOutputAmount());
            return feeForTx != 0;
        }
        return false;
    }

    private static void showSpendNotAllowed(final Context app) {
        Timber.d("timber: showSpendNotAllowed");
        ((Activity) app).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                BRDialog.showCustomDialog(app, app.getString(R.string.Alert_error), app.getString(R.string.Send_isRescanning), app.getString(R.string.Button_ok), null, new BRDialogView.BROnClickListener() {
                    @Override
                    public void onClick(BRDialogView brDialogView) {
                        brDialogView.dismissWithAnimation();
                    }
                }, null, null, 0);
            }
        });
    }

    private static class InsufficientFundsException extends Exception {
        InsufficientFundsException(long amount, long balance) {
            super("Balance: " + balance + " satoshis, amount: " + amount + " satoshis.");
        }
    }

    private static class AmountSmallerThanMinException extends Exception {
        AmountSmallerThanMinException(long amount, long balance) {
            super("Balance: " + balance + " satoshis, amount: " + amount + " satoshis.");
        }
    }

    private static class SpendingNotAllowed extends Exception {
        SpendingNotAllowed() {
            super("spending is not allowed at the moment");
        }
    }

    private static class FeeNeedsAdjust extends Exception {
        FeeNeedsAdjust(long amount, long balance, long fee) {
            super("Balance: " + balance + " satoshis, amount: " + amount + " satoshis, fee: " + fee + " satoshis.");
        }
    }

    private static class FeeOutOfDate extends Exception {
        FeeOutOfDate(long timestamp, long now) {
            super("FeeOutOfDate: timestamp: " + timestamp + ",now: " + now);
        }
    }
}
