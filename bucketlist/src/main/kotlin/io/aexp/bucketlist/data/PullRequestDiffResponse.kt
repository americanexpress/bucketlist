package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty

class PullRequestDiffResponse(@JsonProperty("fromHash") val fromHash: String,
                              @JsonProperty("toHash") val toHash: String,
                              @JsonProperty("diffs") val diffs: List<PullRequestDiff>)