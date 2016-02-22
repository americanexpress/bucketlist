package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

class PullRequestActivity(@JsonProperty("id") val id: Int,
                          @JsonProperty("user") val user: User,
                          @JsonProperty("action") val action: String,
                          @JsonProperty("comment") val comment: PullRequestComment?,
                          @JsonProperty("commentAction") val commentAction: String?,
                          private @JsonProperty("createdDate") val createdDate: Date) {

    val createdAt: ZonedDateTime
        get() = ZonedDateTime.ofInstant(createdDate.toInstant(), ZoneId.of("UTC"))

}

