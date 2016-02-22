package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

class PullRequestComment(@JsonProperty("id") val id: Int,
                         @JsonProperty("text") val text: String,
                         @JsonProperty("comments") val comments: List<PullRequestComment>,
                         @JsonProperty("author") val author: User,
                         private @JsonProperty("createdDate") val createdDate: Date) {

    val createdAt: ZonedDateTime
        get() = ZonedDateTime.ofInstant(createdDate.toInstant(), ZoneId.of("UTC"))

}

