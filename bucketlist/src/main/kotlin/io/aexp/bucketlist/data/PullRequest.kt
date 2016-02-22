package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

class PullRequest(@JsonProperty("id") val id: Long,
                  @JsonProperty("author") val author: PullRequestAuthor,
                  @JsonProperty("closed") val closed: Boolean,
                  private @JsonProperty("createdDate") val createdDate: Date,
                  private @JsonProperty("updatedDate") val updatedDate: Date) {

    val createdAt: ZonedDateTime
        get() = ZonedDateTime.ofInstant(createdDate.toInstant(), ZoneId.of("UTC"))

    val updatedAt: ZonedDateTime
        get() = ZonedDateTime.ofInstant(updatedDate.toInstant(), ZoneId.of("UTC"))
}
