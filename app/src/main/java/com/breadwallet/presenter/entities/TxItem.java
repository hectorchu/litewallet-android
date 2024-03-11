package com.breadwallet.presenter.entities;


import com.breadwallet.lnd.LndTransaction;
import com.breadwallet.tools.util.Utils;
import com.platform.entities.TxMetaData;

public class TxItem {
    public static final String TAG = TxItem.class.getName();
    private long timeStamp;
    private int blockHeight;
    private byte[] txHash;
    private long sent;
    private long received;
    private long fee;
    private String to[];
    private String from[];
    public String txReversed;
    private long balanceAfterTx;
    private long outAmounts[];
    private boolean isValid;
    private int txSize;
    public TxMetaData metaData;

    private TxItem() {
    }

    public TxItem(long timeStamp, int blockHeight, byte[] hash, String txReversed, long sent,
                  long received, long fee, String to[], String from[],
                  long balanceAfterTx, int txSize, long[] outAmounts, boolean isValid) {
        this.timeStamp = timeStamp;
        this.blockHeight = blockHeight;
        this.txReversed = txReversed;
        this.txHash = hash;
        this.sent = sent;
        this.received = received;
        this.fee = fee;
        this.to = to;
        this.from = from;
        this.balanceAfterTx = balanceAfterTx;
        this.outAmounts = outAmounts;
        this.isValid = isValid;
        this.txSize = txSize;
    }

    public TxItem(LndTransaction txn) {
        this.timeStamp = txn.getTimestamp();
        this.blockHeight = txn.getBlockHeight();
        this.txReversed = txn.getTxHash();
        this.txHash = Utils.hexToBytes(Utils.reverseHex(txn.getTxHash()));
        if (txn.getAmount() < 0) {
            this.sent = -txn.getAmount();
            this.received = 0;
        } else {
            this.sent = 0;
            this.received = txn.getAmount();
        }
        this.fee = txn.getFee();
        this.to = txn.getOutputs().stream()
                .map(LndTransaction.Output::getAddress).toArray(String[]::new);
        this.from = new String[0];
        this.balanceAfterTx = txn.getBalanceAfter();
        this.outAmounts = txn.getOutputs().stream()
                .map(LndTransaction.Output::getAmount).mapToLong(Long::longValue).toArray();
        this.isValid = true;
        this.txSize = txn.getRaw().length;
    }

    public int getBlockHeight() {
        return blockHeight;
    }

    public long getFee() {
        return fee;
    }

    public int getTxSize() {
        return txSize;
    }

    public String[] getFrom() {
        return from;
    }

    public byte[] getTxHash() {
        return txHash;
    }

    public String getTxHashHexReversed() {
        return txReversed;
    }

    public long getReceived() {
        return received;
    }

    public long getSent() {
        return sent;
    }

    public static String getTAG() {
        return TAG;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public String[] getTo() {
        return to;
    }

    public long getBalanceAfterTx() {
        return balanceAfterTx;
    }

    public long[] getOutAmounts() {
        return outAmounts;
    }

    public boolean isValid() {
        return isValid;
    }

}
