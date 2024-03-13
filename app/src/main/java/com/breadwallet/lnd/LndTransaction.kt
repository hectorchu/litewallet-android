package com.breadwallet.lnd

import com.breadwallet.tools.util.Utils
import lnrpc.LightningOuterClass.OutputScriptType
import lnrpc.LightningOuterClass.Transaction

class LndTransaction() {
    class Input(val txHash: String, val index: Int, val isOurs: Boolean)
    class Output(val address: String, val amount: Long, val isOurs: Boolean) {
        var script = byteArrayOf()
    }

    var txHash = ""
    var amount = 0L
    var fee = 0L
    var inputs = listOf<Input>()
    var outputs = listOf<Output>()
    var blockHeight = 0
    var confirms = 0
    var timestamp = 0L
    var balanceAfter = 0L
    var raw = byteArrayOf()
    var label = ""
    var hogEx = false

    constructor(transaction: Transaction) : this() {
        this.txHash = transaction.txHash
        this.amount = transaction.amount
        this.fee = transaction.totalFees
        this.inputs = transaction.previousOutpointsList.map {
            val sp = it.outpoint.split(":")
            Input(sp[0], sp[1].toInt(), it.isOurOutput)
        }
        this.outputs = transaction.outputDetailsList.sortedWith { _, od ->
            if (od.outputType == OutputScriptType.SCRIPT_TYPE_WITNESS_MWEB_PEGIN) -1 else 0
        }.map { Output(it.address, it.amount, it.isOurAddress) }
        this.blockHeight = transaction.blockHeight
        this.confirms = transaction.numConfirmations
        this.timestamp = transaction.timeStamp
        this.raw = Utils.hexToBytes(transaction.rawTxHex)
        this.label = transaction.label
        this.hogEx = isHogEx(transaction)
    }

    private fun isHogEx(transaction: Transaction): Boolean {
        if (transaction.outputDetailsCount == 0) {
            return false
        }
        return transaction.getOutputDetails(0).outputType ==
                OutputScriptType.SCRIPT_TYPE_WITNESS_MWEB_HOGADDR
    }
}
