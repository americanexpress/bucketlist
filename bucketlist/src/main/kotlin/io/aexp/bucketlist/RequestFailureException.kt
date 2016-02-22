package io.aexp.bucketlist

class RequestFailureException : Exception {

    constructor(message: String) : super(message) {
    }

    constructor(message: String, cause: Throwable) : super(message, cause) {
    }

}
