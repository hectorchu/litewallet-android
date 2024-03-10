package com.breadwallet.lnd

import lndmobile.Callback
import lndmobile.RecvStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LndCallback(private val continuation: Continuation<ByteArray>): Callback {
    override fun onResponse(data: ByteArray?) =
        continuation.resume(data ?: byteArrayOf())

    override fun onError(e: Exception?) = continuation.resumeWithException(e!!)
}

class LndReceiveStream(private val callback: (Result<ByteArray>) -> Unit): RecvStream {
    override fun onResponse(data: ByteArray?) =
        callback(Result.success(data ?: byteArrayOf()))

    override fun onError(e: Exception?) = callback(Result.failure(e!!))
}
