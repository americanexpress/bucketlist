package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty

class PagedResponse<T>(@JsonProperty("size") val size: Int,
                       @JsonProperty("limit") val limit: Int,
                       @JsonProperty("isLastPage") val isLastPage: Boolean,
                       @JsonProperty("start") val start: Int,
                       @JsonProperty("nextPageStart") val nextPageStart: Int,
                       @JsonProperty("values") val values: List<T>) {

}
