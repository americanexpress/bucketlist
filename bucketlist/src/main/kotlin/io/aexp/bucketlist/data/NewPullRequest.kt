package io.aexp.bucketlist.data

class NewPullRequest(val title: String,
                     val description: String,
                     val fromRef: Ref,
                     val toRef: Ref) {

}
