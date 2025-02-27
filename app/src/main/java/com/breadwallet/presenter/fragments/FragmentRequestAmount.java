package com.breadwallet.presenter.fragments;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.breadwallet.BreadApp;
import com.breadwallet.R;
import com.breadwallet.presenter.customviews.BRButton;
import com.breadwallet.presenter.customviews.BRKeyboard;
import com.breadwallet.presenter.customviews.BRLinearLayoutWithCaret;
import com.breadwallet.tools.animation.BRAnimator;
import com.breadwallet.tools.animation.SlideDetector;
import com.breadwallet.tools.manager.BRClipboardManager;
import com.breadwallet.tools.manager.BRSharedPrefs;
import com.breadwallet.tools.qrcode.QRUtils;
import com.breadwallet.tools.threads.BRExecutor;
import com.breadwallet.tools.util.BRConstants;
import com.breadwallet.tools.util.BRCurrency;
import com.breadwallet.tools.util.BRExchange;
import com.breadwallet.tools.util.Utils;
import com.breadwallet.wallet.BRWalletManager;

import java.math.BigDecimal;

import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;

import timber.log.Timber;

public class FragmentRequestAmount extends Fragment {
    private BRKeyboard keyboard;
    private StringBuilder amountBuilder;
    private TextView isoText;
    private EditText amountEdit;
    public TextView mTitle;
    public TextView mAddress;
    public ImageView mQrImage;
    public LinearLayout backgroundLayout;
    public LinearLayout signalLayout;
    private String receiveAddress;
    private BRButton shareButton;
    private Button shareEmail;
    //    private Button shareTextMessage;
    private boolean shareButtonsShown = true;
    private String selectedIso;
    private Button isoButton;
    private Handler copyCloseHandler = new Handler();
    private LinearLayout keyboardLayout;
    private RelativeLayout amountLayout;
    private Button request;
    private BRLinearLayoutWithCaret shareButtonsLayout;
    private BRLinearLayoutWithCaret copiedLayout;
    private int keyboardIndex;
    //    private int currListIndex;
    private ImageButton close;
    private SwitchCompat mweb_switch;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        View rootView = inflater.inflate(R.layout.fragment_receive, container, false);
        backgroundLayout = (LinearLayout) rootView.findViewById(R.id.background_layout);
        signalLayout = (LinearLayout) rootView.findViewById(R.id.signal_layout);
        shareButtonsLayout = (BRLinearLayoutWithCaret) rootView.findViewById(R.id.share_buttons_layout);
        copiedLayout = (BRLinearLayoutWithCaret) rootView.findViewById(R.id.copied_layout);
//        currencyListLayout = (LinearLayout) rootView.findViewById(R.id.cur_spinner_layout);
//        currencyListLayout.setVisibility(View.VISIBLE);
        request = (Button) rootView.findViewById(R.id.request_button);
        keyboardLayout = (LinearLayout) rootView.findViewById(R.id.keyboard_layout);
        keyboardLayout.setVisibility(View.VISIBLE);
        amountLayout = (RelativeLayout) rootView.findViewById(R.id.amount_layout);
        amountLayout.setVisibility(View.VISIBLE);
        keyboard = (BRKeyboard) rootView.findViewById(R.id.keyboard);
        keyboard.setBRButtonBackgroundResId(R.drawable.keyboard_white_button);
        keyboard.setBRKeyboardColor(R.color.white);
        isoText = (TextView) rootView.findViewById(R.id.iso_text);
        amountEdit = (EditText) rootView.findViewById(R.id.amount_edit);
        amountBuilder = new StringBuilder(0);
        isoButton = (Button) rootView.findViewById(R.id.iso_button);
        mTitle = (TextView) rootView.findViewById(R.id.title);
        mAddress = (TextView) rootView.findViewById(R.id.address_text);
        mQrImage = (ImageView) rootView.findViewById(R.id.qr_image);
        shareButton = (BRButton) rootView.findViewById(R.id.share_button);
        shareEmail = (Button) rootView.findViewById(R.id.share_email);
//        shareTextMessage = (Button) rootView.findViewById(R.id.share_text);
        shareButtonsLayout = (BRLinearLayoutWithCaret) rootView.findViewById(R.id.share_buttons_layout);
        close = (ImageButton) rootView.findViewById(R.id.close_button);
        keyboardIndex = signalLayout.indexOfChild(keyboardLayout);
        mweb_switch = rootView.findViewById(R.id.mweb_switch);

        //TODO: all views are using the layout of this button. Views should be refactored without it
        // Hiding until layouts are built.
        ImageButton faq = (ImageButton) rootView.findViewById(R.id.faq_button);

        mTitle.setText(getString(R.string.Receive_request));
        setListeners();

        shareButtonsLayout.setVisibility(View.GONE);
        copiedLayout.setVisibility(View.GONE);
        request.setVisibility(View.GONE);

        showCurrencyList(false);
        selectedIso = BRSharedPrefs.getPreferredLTC(getContext()) ? "LTC" : BRSharedPrefs.getIso(getContext());

        signalLayout.setOnClickListener(v -> {
//                removeCurrencySelector();
        });
        updateText();

        signalLayout.setLayoutTransition(BRAnimator.getDefaultTransition());

        signalLayout.setOnTouchListener(new SlideDetector(signalLayout, this::animateClose));

        return rootView;
    }

    private void setListeners() {
        amountEdit.setOnClickListener(v -> {
            removeCurrencySelector();
            showKeyboard(true);
            showShareButtons(false);
        });

        close.setOnClickListener(v -> animateClose());

        mQrImage.setOnClickListener(v -> {
            removeCurrencySelector();
            showKeyboard(false);
        });

        keyboard.addOnInsertListener(key -> {
            removeCurrencySelector();
            handleClick(key);
        });


        shareEmail.setOnClickListener(v -> {
            removeCurrencySelector();
            if (!BRAnimator.isClickAllowed()) return;
            showKeyboard(false);
            String iso = selectedIso;
            String strAmount = amountEdit.getText().toString();
            BigDecimal bigAmount = new BigDecimal((Utils.isNullOrEmpty(strAmount) || strAmount.equalsIgnoreCase(".")) ? "0" : strAmount);
            long amount = BRExchange.getSatoshisFromAmount(getActivity(), iso, bigAmount).longValue();
            String bitcoinUri = Utils.createBitcoinUrl(receiveAddress, amount, null, null, null);
            QRUtils.share("mailto:", getActivity(), bitcoinUri);

        });
//        shareTextMessage.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                removeCurrencySelector();
//                if (!BRAnimator.isClickAllowed()) return;
//                showKeyboard(false);
//                String iso = selectedIso;
//                String strAmount = amountEdit.getText().toString();
//                BigDecimal bigAmount = new BigDecimal((Utils.isNullOrEmpty(strAmount) || strAmount.equalsIgnoreCase(".")) ? "0" : strAmount);
//                long amount = BRExchange.getSatoshisFromAmount(getActivity(), iso, bigAmount).longValue();
//                String bitcoinUri = Utils.createBitcoinUrl(receiveAddress, amount, null, null, null);
//                QRUtils.share("sms:", getActivity(), bitcoinUri);
//            }
//        });
        shareButton.setOnClickListener(v -> {
            if (!BRAnimator.isClickAllowed()) return;
            shareButtonsShown = !shareButtonsShown;
            showShareButtons(shareButtonsShown);
            showKeyboard(false);
        });
        mAddress.setOnClickListener(v -> {
            removeCurrencySelector();
            copyText();
            showKeyboard(false);
        });
        mweb_switch.setOnClickListener(v -> {
            if (!BRAnimator.isClickAllowed()) return;
            setAddress();
        });

        backgroundLayout.setOnClickListener(v -> {
            removeCurrencySelector();
            if (!BRAnimator.isClickAllowed()) return;
            animateClose();
        });

        isoButton.setOnClickListener(v -> {
            if (selectedIso.equalsIgnoreCase(BRSharedPrefs.getIso(getContext()))) {
                selectedIso = "LTC";
            } else {
                selectedIso = BRSharedPrefs.getIso(getContext());
            }
            boolean generated = generateQrImage(receiveAddress, amountEdit.getText().toString(), selectedIso);
            if (!generated)
                throw new RuntimeException("failed to generate qr image for address");
            updateText();
        });
    }

    private void copyText() {
        BRClipboardManager.putClipboard(getContext(), mAddress.getText().toString());
        showCopiedLayout(true);
    }

    private void toggleShareButtonsVisibility() {
        if (shareButtonsShown) {
            shareButtonsLayout.setVisibility(View.GONE);
            shareButtonsShown = false;
        } else {
            shareButtonsLayout.setVisibility(View.VISIBLE);
            shareButtonsShown = true;
        }
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final ViewTreeObserver observer = signalLayout.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (observer.isAlive()) {
                    observer.removeOnGlobalLayoutListener(this);
                }
                BRAnimator.animateBackgroundDim(backgroundLayout, false);
                BRAnimator.animateSignalSlide(signalLayout, false, null);
                toggleShareButtonsVisibility();
            }
        });

        BRExecutor.getInstance().forLightWeightBackgroundTasks().execute(new Runnable() {
            @Override
            public void run() {
                boolean success = BRWalletManager.refreshAddress(getActivity());
                if (!success) throw new RuntimeException("failed to retrieve address");

                BRExecutor.getInstance().forMainThreadTasks().execute(new Runnable() {
                    @Override
                    public void run() {
                        setAddress();
                    }
                });
            }
        });
    }

    private void setAddress() {
        Context ctx = getContext();
        if (ctx == null) return;
        receiveAddress = BRSharedPrefs.getReceiveAddress(ctx);

        if (mweb_switch.isChecked()) {
            try {
                receiveAddress = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
                        (scope, continuation) -> BreadApp.lnd.getUnusedAddress(true, continuation));
            } catch (InterruptedException e) {
                return;
            }
        }

        mAddress.setText(receiveAddress);
        boolean generated = generateQrImage(receiveAddress, amountEdit.getText().toString(), selectedIso);
        if (!generated) throw new RuntimeException("failed to generate qr image for address");
    }

    private void handleClick(String key) {
        if (key == null) {
            Timber.d("timber: handleClick: key is null! ");
            return;
        }

        if (key.isEmpty()) {
            handleDeleteClick();
        } else if (Character.isDigit(key.charAt(0))) {
            handleDigitClick(Integer.parseInt(key.substring(0, 1)));
        } else if (key.charAt(0) == '.') {
            handleSeparatorClick();
        }

        boolean generated = generateQrImage(receiveAddress, amountEdit.getText().toString(), selectedIso);
        if (!generated) throw new RuntimeException("failed to generate qr image for address");
    }

    private void handleDigitClick(Integer dig) {
        String currAmount = amountBuilder.toString();
        String iso = selectedIso;
        if (new BigDecimal(currAmount.concat(String.valueOf(dig))).doubleValue()
                <= BRExchange.getMaxAmount(getActivity(), iso).doubleValue()) {
            //do not insert 0 if the balance is 0 now
            if (currAmount.equalsIgnoreCase("0")) amountBuilder = new StringBuilder("");
            if ((currAmount.contains(".") && (currAmount.length() - currAmount.indexOf(".") > BRCurrency.getMaxDecimalPlaces(iso))))
                return;
            amountBuilder.append(dig);
            updateText();
        }
    }

    private void handleSeparatorClick() {
        String currAmount = amountBuilder.toString();
        if (currAmount.contains(".") || BRCurrency.getMaxDecimalPlaces(selectedIso) == 0)
            return;
        amountBuilder.append(".");
        updateText();
    }

    private void handleDeleteClick() {
        String currAmount = amountBuilder.toString();
        if (currAmount.length() > 0) {
            amountBuilder.deleteCharAt(currAmount.length() - 1);
            updateText();
        }
    }

    private void updateText() {
        if (getActivity() == null) return;
        String tmpAmount = amountBuilder.toString();
        amountEdit.setText(tmpAmount);
        isoText.setText(BRCurrency.getSymbolByIso(getActivity(), selectedIso));
        isoButton.setText(String.format("%s(%s)", BRCurrency.getCurrencyName(getActivity(), selectedIso), BRCurrency.getSymbolByIso(getActivity(), selectedIso)));
    }

    private void showKeyboard(boolean b) {
        keyboardLayout.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    private boolean generateQrImage(String address, String strAmount, String iso) {
        String amountArg = "";
        if (strAmount != null && !strAmount.isEmpty()) {
            BigDecimal bigAmount = new BigDecimal((Utils.isNullOrEmpty(strAmount) || strAmount.equalsIgnoreCase(".")) ? "0" : strAmount);
            long amount = BRExchange.getSatoshisFromAmount(getActivity(), iso, bigAmount).longValue();
            String am = new BigDecimal(amount).divide(new BigDecimal(100000000), 8, BRConstants.ROUNDING_MODE).toPlainString();
            amountArg = "?amount=" + am;
        }
        return QRUtils.generateQR(getActivity(), "litecoin:" + address + amountArg, mQrImage);
    }


    private void removeCurrencySelector() {
//        showCurrencyList(false);
    }

    private void showShareButtons(boolean b) {
        if (!b) {
            shareButtonsLayout.setVisibility(View.GONE);
            shareButton.setType(2);
        } else {
            shareButtonsLayout.setVisibility(View.VISIBLE);
            shareButton.setType(3);
            showCopiedLayout(false);
        }
    }


    private void showCopiedLayout(boolean b) {
        if (!b) {
            copiedLayout.setVisibility(View.GONE);
            copyCloseHandler.removeCallbacksAndMessages(null);
        } else {
            copiedLayout.setVisibility(View.VISIBLE);
            showShareButtons(false);
            shareButtonsShown = false;
            copyCloseHandler.postDelayed(() -> copiedLayout.setVisibility(View.GONE), 2000);
        }
    }

    private void showCurrencyList(boolean b) {
    }

    private void animateClose() {
        BRAnimator.animateBackgroundDim(backgroundLayout, true);
        BRAnimator.animateSignalSlide(signalLayout, true, new BRAnimator.OnSlideAnimationEnd() {
            @Override
            public void onAnimationEnd() {
                close();
            }
    });
    }

    private void close() {
        FragmentActivity activity = getActivity();
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            activity.getSupportFragmentManager().popBackStack();
        }
    }
}
