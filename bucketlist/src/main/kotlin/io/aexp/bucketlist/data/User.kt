package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty

class User(@JsonProperty("name") val name: String,
           @JsonProperty("emailAddress") val emailAddress: String,
           @JsonProperty("id") val id: Long,
           @JsonProperty("displayName") val displayName: String) {

}
