package io.aexp.bucketlist.data

class Repo(private val projKey: String, private val repoSlug: String) {
    val project: Project
        get() = Project(projKey)

    val slug: String
        get() = repoSlug

    class Project(val key: String) {}
}
