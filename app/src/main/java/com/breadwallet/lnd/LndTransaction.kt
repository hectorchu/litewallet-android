package com.breadwallet.lnd

import lnrpc.LightningOuterClass
import lnrpc.LightningOuterClass.OutputScriptType
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.DurationUnit
import kotlin.time.toDuration

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

    constructor(transaction: LightningOuterClass.Transaction) : this() {
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
        this.label = transaction.label
    }
}
