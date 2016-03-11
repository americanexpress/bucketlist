package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty

class PullRequestFilePath(@JsonProperty("components") val components: List<String>,
                          @JsonProperty("name") val name: String,
                          @JsonProperty("toString") val fullPath: String)