package io.aexp.bucketlist.auth

import com.ning.http.client.AsyncHttpClient

interface Authenticator {
    fun authenticate(requestBuilder: AsyncHttpClient.BoundRequestBuilder): AsyncHttpClient.BoundRequestBuilder
}
