package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty

class PullRequestDiff(@JsonProperty("source") val source: PullRequestFilePath?,
                      @JsonProperty("destination") val destination: PullRequestFilePath?,
                      @JsonProperty("hunks") val hunks: List<PullRequestHunk>?,
                      @JsonProperty("truncated") val truncated: Boolean)