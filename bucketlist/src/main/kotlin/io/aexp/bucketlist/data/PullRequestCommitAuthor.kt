package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty

class PullRequestCommitAuthor(@JsonProperty("name") val name: String,
                              @JsonProperty("emailAddress") val emailAddress: String)
