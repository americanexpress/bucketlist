package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty

class PullRequestHunk(@JsonProperty("sourceLine") val sourceLine: Int,
                      @JsonProperty("sourceSpan") val sourceSpan: Int,
                      @JsonProperty("destinationLine") val destinationLine: Int,
                      @JsonProperty("destinationSpan") val destinationSpan: Int)