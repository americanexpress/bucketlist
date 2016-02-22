package io.aexp.bucketlist.auth

import com.ning.http.client.AsyncHttpClient
import java.nio.charset.StandardCharsets
import java.util.Base64

class UsernamePasswordAuthenticator(private val username: String, private val password: String) : Authenticator {
    override fun authenticate(
            requestBuilder: AsyncHttpClient.BoundRequestBuilder): AsyncHttpClient.BoundRequestBuilder {
        return requestBuilder.addHeader("Authorization", "Basic " + getAuthStr())

    }

    private fun getAuthStr(): String {
        return Base64.getEncoder().encodeToString((username + ":" + password).toByteArray(StandardCharsets.UTF_8))
    }

}
