package io.aexp.bucketlist

import com.ning.http.client.AsyncCompletionHandler
import com.ning.http.client.Response
import org.slf4j.LoggerFactory
import rx.Observer

abstract class RxResponseHandler<T>(val observer: Observer<T>) : AsyncCompletionHandler<Void>() {

    companion object {
        val logger = LoggerFactory.getLogger(RxResponseHandler::class.java)
    }

    override fun onThrowable(t: Throwable?) {
        observer.onError(RequestFailureException("Request failed", t!!))
    }

    override fun onCompleted(response: Response?): Void? {
        val r = response!!

        if (r.statusCode >= 300 || r.statusCode < 200) {
            observer.onError(RequestFailureException(
                    "Bad status code: ${r.statusCode} with body ${r.responseBody}"))
            return null
        }

        if (logger.isTraceEnabled) {
            logger.trace("Got response:\n${r.responseBody}")
        }

        handleSuccessfulResponse(r, observer)
        return null
    }

    abstract fun handleSuccessfulResponse(response: Response, observer: Observer<T>)
}
