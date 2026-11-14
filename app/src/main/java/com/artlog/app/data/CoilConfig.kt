package com.artlog.app.data

import android.util.Log
import coil.EventListener
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared Coil configuration (round-5 optimization). Before this, the
 * app used Coil's defaults with no explicit tuning, no loading
 * placeholder and no retry — a failed image stayed a broken icon
 * until the user reopened the page.
 *
 * - Memory cache 25% of the app's available memory, disk cache
 *   256 MB (see ArtlogApplication.newImageLoader).
 * - crossfade on.
 * - One limited retry (max 1, only for network IOExceptions, 300ms
 *   apart) — never infinite.
 * - Remote-image load stats (success / failure counts, cache hits
 *   included) for the debug panel; local artwork files (File models)
 *   are not counted. No user data is ever logged or counted.
 */
object ImageLoadStats {
    private val success = AtomicInteger(0)
    private val failure = AtomicInteger(0)

    fun recordSuccess() {
        success.incrementAndGet()
    }

    fun recordFailure() {
        failure.incrementAndGet()
    }

    /** e.g. "图片 34 成功 / 2 失败" (cache hits included). */
    fun summary(): String = "图片 ${success.get()} 成功 / ${failure.get()} 失败"
}

/** Counts remote image loads for the debug panel. */
class InspirationImageEventListener : EventListener {
    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        if (isRemote(request)) ImageLoadStats.recordSuccess()
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        if (isRemote(request)) ImageLoadStats.recordFailure()
    }

    private fun isRemote(request: ImageRequest): Boolean {
        val data = request.data
        return data is String &&
            (data.startsWith("http://") || data.startsWith("https://"))
    }
}

/** One limited retry for failed network image loads (never infinite). */
class ImageRetryInterceptor(private val maxRetries: Int = 1) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        var result = chain.proceed(chain.request)
        var attempts = 0
        while (result is ErrorResult &&
            shouldRetryImageLoad(result.throwable, attempts, maxRetries)
        ) {
            attempts++
            delay(300)
            Log.d("CoilRetry", "retrying ${chain.request.data} (attempt $attempts)")
            result = chain.proceed(chain.request)
        }
        return result
    }
}

/**
 * 5.1.2 safety round (P2): the pure retry decision, extracted so it
 * can be JVM-tested. Only genuine network [IOException]s are retried
 * — connect/read timeouts, DNS and connect failures. HTTP errors are
 * NEVER retried: Coil 2.7's `coil.network.HttpException` extends
 * `java.lang.RuntimeException` (verified from the coil-base 2.7.0
 * jar with javap), so a 403 / 404 / 429 never satisfies
 * `throwable is IOException` in the first place — but the exclusion
 * is written explicitly here, so a future Coil change cannot
 * silently turn HTTP errors into retried requests. Retrying a 403
 * (forbidden) or a 429 (rate-limited) would be meaningless at best
 * and abusive at worst; a 404 will never succeed on retry.
 */
internal fun shouldRetryImageLoad(
    throwable: Throwable,
    attempts: Int,
    maxRetries: Int
): Boolean = throwable is IOException && attempts < maxRetries
