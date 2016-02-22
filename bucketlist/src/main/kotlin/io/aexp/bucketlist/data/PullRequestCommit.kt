package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

class PullRequestCommit(@JsonProperty("id") val id: String,
                        @JsonProperty("author") val author: PullRequestCommitAuthor,
                        @JsonProperty("message") val message: String,
                        private @JsonProperty("authorTimestamp") val authorTimestamp: Date) {

    val createdAt: ZonedDateTime
        get() = ZonedDateTime.ofInstant(authorTimestamp.toInstant(), ZoneId.of("UTC"))
}

