package com.breadwallet.lnd

import chainrpc.*
import com.breadwallet.BuildConfig
import com.breadwallet.tools.util.TrustedNode
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import lndmobile.Lndmobile
import lnrpc.*
import lnrpc.Stateservice.WalletState
import neutrinorpc.*
import signrpc.*
import walletrpc.*
import java.io.File
import java.util.Optional
import kotlin.coroutines.suspendCoroutine

class NotStartedException : Exception()
class AlreadyStartedException : Exception()

class LndManager(dataDir: String, val trustedNode: String) {
    private val lndPath = File(dataDir).resolve("lnd")
    private val testnet = BuildConfig.LITECOIN_TESTNET
    private val walletState = Channel<WalletState>(CONFLATED)
    var walletExists = false
        private set
    var isStarted = false
        private set

    suspend fun start() {
        if (isStarted) throw AlreadyStartedException()
        var args = arrayOf(
            "--nolisten",
            "--lnddir=\"$lndPath\"",
            "--litecoin.active",
            "--litecoin.node=neutrino",
            "--no-macaroons",
            "--tlsdisableautofill",
            "--sync-freelist",
        )
        if (testnet) {
            args += "--litecoin.testnet"
        } else {
            args += "--litecoin.mainnet"
            args += "--feeurl=https://litecoinspace.org/api/v1/fees/recommended-lnd"
        }
        if (trustedNode != "") {
            val host = TrustedNode.getNodeHost(trustedNode)
            val port = TrustedNode.getNodePort(trustedNode)
            args += "--neutrino.addpeer=$host:$port"
        }
        suspendCoroutine {
            Lndmobile.start(args.joinToString(separator = " "), LndCallback(it))
        }
        val req = subscribeStateRequest {}
        Lndmobile.subscribeState(req.toByteArray(), LndReceiveStream { result ->
            result.fold({
                val resp = lnrpc.Stateservice.SubscribeStateResponse.parseFrom(it)
                walletState.trySend(resp.state)
            }, { walletState.close(it) })
        })
        while (true) {
            val state = walletState.receive()
            if (state == WalletState.NON_EXISTING || state == WalletState.LOCKED) {
                walletExists = state == WalletState.LOCKED
                break
            }
        }
        isStarted = true
    }

    suspend fun stop() {
        if (!isStarted) throw NotStartedException()
        isStarted = false
        val req = stopRequest {}
        suspendCoroutine {
            Lndmobile.stopDaemon(req.toByteArray(), LndCallback(it))
        }
    }

    fun deleteWallet() {
        var path = lndPath.resolve("data/chain/litecoin")
        path = if (testnet) {
            path.resolve("testnet")
        } else {
            path.resolve("mainnet")
        }
        path.resolve("wallet.db").delete()
        path.resolve("channel.backup").delete()
        walletExists = false
    }

    suspend fun initWallet(
        password: String,
        xprv: String,
        creationTime: Long,
        recoveryWindow: Int
    ) {
        if (!isStarted) throw NotStartedException()
        val req = initWalletRequest {
            walletPassword = ByteString.copyFromUtf8(password)
            extendedMasterKey = xprv
            extendedMasterKeyBirthdayTimestamp = creationTime
            this.recoveryWindow = recoveryWindow
        }
        suspendCoroutine {
            Lndmobile.initWallet(req.toByteArray(), LndCallback(it))
        }
        while (true) {
            val state = walletState.receive()
            if (state == WalletState.SERVER_ACTIVE) {
                break
            }
        }
        subscribeBlocks()
        subscribeTransactions()
        walletExists = true
    }

    suspend fun unlockWallet(password: String, recoveryWindow: Int) {
        if (!isStarted) throw NotStartedException()
        val req = unlockWalletRequest {
            walletPassword = ByteString.copyFromUtf8(password)
            this.recoveryWindow = recoveryWindow
        }
        suspendCoroutine {
            Lndmobile.unlockWallet(req.toByteArray(), LndCallback(it))
        }
        while (true) {
            val state = walletState.receive()
            if (state == WalletState.SERVER_ACTIVE) {
                break
            }
        }
        subscribeBlocks()
        subscribeTransactions()
    }

    suspend fun getRecoveryInfo(): Optional<Double> {
        if (!isStarted) throw NotStartedException()
        val req = getRecoveryInfoRequest {}
        val data = suspendCoroutine {
            Lndmobile.getRecoveryInfo(req.toByteArray(), LndCallback(it))
        }
        val resp = LightningOuterClass.GetRecoveryInfoResponse.parseFrom(data)
        if (!resp.recoveryMode || resp.recoveryFinished) {
            return Optional.empty()
        }
        return Optional.of(resp.progress)
    }

    private fun subscribeBlocks() {
        val req = blockEpoch {}
        Lndmobile.chainNotifierRegisterBlockEpochNtfn(req.toByteArray(), LndReceiveStream {
            if (it.isSuccess) {
                val block = Chainnotifier.BlockEpoch.parseFrom(it.getOrThrow())
            } else Unit
        })
    }

    private fun subscribeTransactions() {
        val req = getTransactionsRequest {}
        Lndmobile.subscribeTransactions(req.toByteArray(), LndReceiveStream {
            if (it.isSuccess) {
                val txn = LightningOuterClass.Transaction.parseFrom(it.getOrThrow())
            } else Unit
        })
    }

    suspend fun getTransactions(): Array<LndTransaction> {
        if (!isStarted) throw NotStartedException()
        val req = getTransactionsRequest {}
        val data = suspendCoroutine {
            Lndmobile.getTransactions(req.toByteArray(), LndCallback(it))
        }
        val resp = LightningOuterClass.TransactionDetails.parseFrom(data)
        val transactions = resp.transactionsList.map { LndTransaction(it) }
            .sortedWith { txn1, txn2 ->
                if (txn1.timestamp == txn2.timestamp) {
                    txn1.amount.compareTo(txn2.amount)
                } else {
                    txn1.timestamp.compareTo(txn2.timestamp)
                }
            }
        var balance = 0L
        for (transaction in transactions) {
            balance += transaction.amount
            transaction.balanceAfter = balance
        }
        return transactions.toTypedArray()
    }

    class GetInfo(
        val numPeers: Int,
        val blockHeight: Int,
        val blockHash: String,
        val bestHeaderTimestamp: Long,
        val synced: Boolean
    )

    suspend fun getInfo(): GetInfo {
        if (!isStarted) throw NotStartedException()
        val req = getInfoRequest {}
        val data = suspendCoroutine {
            Lndmobile.getInfo(req.toByteArray(), LndCallback(it))
        }
        val resp = LightningOuterClass.GetInfoResponse.parseFrom(data)
        return GetInfo(resp.numPeers, resp.blockHeight, resp.blockHash,
            resp.bestHeaderTimestamp, resp.syncedToChain)
    }

    suspend fun getBalance(confirmed: Boolean): Long {
        if (!isStarted) throw NotStartedException()
        val req = walletBalanceRequest {}
        val data = suspendCoroutine {
            Lndmobile.walletBalance(req.toByteArray(), LndCallback(it))
        }
        val resp = LightningOuterClass.WalletBalanceResponse.parseFrom(data)
        if (confirmed) {
            return resp.confirmedBalance
        }
        return resp.totalBalance
    }

    suspend fun getUnusedAddress(mweb: Boolean): String {
        if (!isStarted) throw NotStartedException()
        val req = newAddressRequest {
            type = if (mweb) {
                LightningOuterClass.AddressType.UNUSED_MWEB
            } else {
                LightningOuterClass.AddressType.UNUSED_WITNESS_PUBKEY_HASH
            }
        }
        val data = suspendCoroutine {
            Lndmobile.newAddress(req.toByteArray(), LndCallback(it))
        }
        val resp = LightningOuterClass.NewAddressResponse.parseFrom(data)
        return resp.address
    }

    suspend fun estimateFee(): Long {
        if (!isStarted) throw NotStartedException()
        val req = walletrpc.estimateFeeRequest {
            confTarget = 2
        }
        val data = suspendCoroutine {
            Lndmobile.walletKitEstimateFee(req.toByteArray(), LndCallback(it))
        }
        val resp = Walletkit.EstimateFeeResponse.parseFrom(data)
        return resp.satPerKw
    }

    suspend fun estimateFeeForAmount(address: String, amount: Long): Long {
        if (!isStarted) throw NotStartedException()
        val req = lnrpc.estimateFeeRequest {
            addrToAmount[address] = amount
            targetConf = 2
            spendUnconfirmed = false
        }
        val data = suspendCoroutine {
            Lndmobile.estimateFee(req.toByteArray(), LndCallback(it))
        }
        val resp = LightningOuterClass.EstimateFeeResponse.parseFrom(data)
        return resp.feeSat
    }

    suspend fun createTransaction(address: String, amount: Long): LndTransaction {
        val transaction = LndTransaction()
        transaction.outputs = listOf(LndTransaction.Output(address, amount, false))
        transaction.fee = estimateFeeForAmount(address, amount)
        return transaction
    }

    suspend fun createTransactionForOutputs(outputs: List<LndTransaction.Output>): LndTransaction {
        if (!isStarted) throw NotStartedException()
        val req = sendOutputsRequest {
            outputs.forEach {
                this.outputs.add(txOut {
                    value = it.amount
                    pkScript = it.script.toByteString()
                })
            }
        }
        val data = suspendCoroutine {
            Lndmobile.walletKitSendOutputs(req.toByteArray(), LndCallback(it))
        }
        val resp = Walletkit.SendOutputsResponse.parseFrom(data)
        val transaction = LndTransaction()
        transaction.outputs = outputs
        transaction.raw = resp.rawTx.toByteArray()
        return transaction
    }

    suspend fun publishTransaction(transaction: LndTransaction) {
        if (!isStarted) throw NotStartedException()
        val req = sendCoinsRequest {
            addr = transaction.outputs[0].address
            amount = transaction.outputs[0].amount
            label = transaction.label
        }
        val data = suspendCoroutine {
            Lndmobile.sendCoins(req.toByteArray(), LndCallback(it))
        }
        val resp = LightningOuterClass.SendCoinsResponse.parseFrom(data)
        transaction.txHash = resp.txid
    }

    suspend fun addPeer(address: String) {
        if (!isStarted) throw NotStartedException()
        val req = addPeerRequest {
            peerAddrs = address
        }
        val data = suspendCoroutine {
            Lndmobile.neutrinoKitAddPeer(req.toByteArray(), LndCallback(it))
        }
        val resp = Neutrino.AddPeerResponse.parseFrom(data)
    }
}
