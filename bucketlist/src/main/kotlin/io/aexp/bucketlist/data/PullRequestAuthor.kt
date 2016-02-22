package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty

class PullRequestAuthor(@JsonProperty("user") val user: User)
