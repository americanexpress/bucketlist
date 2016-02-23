# What's BucketList for?

BucketList lets you access various types of information about pull requests in [Bitbucket Server](https://www.atlassian.com/software/bitbucket), formerly Stash. You can list them, get detailed info, create them, etc. This is doesn't use the same HTTP API as [Bitbucket](https://www.bitbucket.org) (as opposed to Bitbucket Server), unfortunately, so you cannot use this for both.

We built this to evaluate the hypothesis that CI improvements and process changes will improve our productivity with a measurable decrease in PR lifetimes.

Atlassian already provides a simple [`stash-java-client`](https://bitbucket.org/atlassianlabs/stash-java-client/overview), but we needed access to pull request info via the API, which that client did not support. We also wanted to consume the result of API calls as `Observable`s, and we wanted to experiment with Kotlin as well, so we decided to explore in our own project rather than making a hefty exploratory fork.

# How do I use it?

`BucketListClient` is the interface you'll use to interact with Bitbucket Server. It lets you perform various operations with the REST API (currently focused on pull request operations). The default implementation is `HttpBucketListClient`.

Some of the API responses are exposed as pages; the resulting `Observable` will emit a page at a time. The client will keep fetching pages for you automatically.

See the examples and tests for more on how to construct and use these objects. Especially, note that the Jackson `ObjectMapper` must be configured to not fail on unknown properties.

Artifacts are available in [JCenter](https://bintray.com/bintray/jcenter).

Gradle:

```groovy
compile 'io.aexp.bucketlist:bucketlist:0.1'
```

Maven:

```xml
<dependency>
    <groupId>io.aexp.bucketlist</groupId>
    <artifactId>bucketlist</artifactId>
    <version>0.1</version>
</dependency>
```

# Terminology

You'll see references to a `project` and a `repoSlug` in the API; these are terms Bitbucket Server uses to describe the path to a particular repo. If you have a url to browse the source of a Bitbucket Server repo, like this:

```
https://bitbucketserver.company.com/projects/FOO/repos/best-code-ever/browse
```

then `FOO` is the project and `best-code-ever` is the repoSlug.

# What did we use to build it?

This is a client for the [Bitbucket Server](https://www.atlassian.com/software/bitbucket) [REST API](https://developer.atlassian.com/stash/docs/latest/reference/rest-api.html) written in [Kotlin](http://kotlinlang.org/) and using [RxJava](https://github.com/ReactiveX/RxJava) `Observable`s as the means of exposing the results of API calls. (If you're using Java or another JVM language, don't worry: Kotlin's interop with Java is great, so you can use this from plain old Java too.)

# Examples

We've written several examples of the sort of things we find interesting to analyze in our repositories and included them for you to try out. First, build the examples:

```
./gradlew :examples:shadowJar
```

The examples use a properties file to provide the Bitbucket Server url and credentials, so prepare a `bitbucket-server.properties` (or whatever you'd like) file:

```
url: https://bitbucket-server.domain.in.your.company.com
username: thor
password: god-of-thunder
```

You can then use that jar as the classpath for invocations of the example tools, like ones to graph PR lifetime. Substitute `SOME-PROJ` and `some-repo` as appropriate for your repo of interest:

```
java -cp examples/build/libs/examples-all.jar \
  io.aexp.bucketlist.examples.prlifetime.ExportPrLifetimeData \
  bitbucket-server.properties \
  SOME-PROJ \
  some-repo \
  pr-lifetime-data.tsv \
  2015-01-01 \
  2015-12-31
```

And then turn the tsv into a graph:

```
java -cp examples/build/libs/examples-all.jar \
  io.aexp.bucketlist.examples.prlifetime.RenderPrLifetimeBoxWhiskerPlot \
  pr-lifetime-data.tsv \
  pr-lifetime-data.svg
```

There are several more examples in the `examples` project showing other ways to slice and dice PR data into interesting numbers and graphs, so check 'em out!

## PR Lifetime

One of the examples we've included is representative of the primary goal we had when originally writing this: measuring PR lifetime so we could tell quantitatively how much of an impact new CI hardware or changing team PR policies had.

`ExportPrLifetimeData`  and `RenderPrLifetimeBoxWhiskerPlot` together provide this. The former downloads the necessary data to a tsv, and the latter makes a plot of the data. They're separated to allow easier experimentation with plotting.

# Testing

Sadly (and ironically, given the the original need for this code: measuring the effect of faster test turnaround), we haven't included integration tests that run against an actual Bitbucket Server instance for this, because Bitbucket Server isn't free. If you have an idea of how we could have everyone programmatically set up a Bitbucket Server instance, run tests, and tear it down, all without violating Bitbucket Server licensing terms, let us know!

# Contributing

We welcome Your interest in the American Express Open Source Community on Github. Any Contributor to any Open Source Project managed by the American Express Open Source Community must accept and sign an Agreement indicating agreement to the terms below. Except for the rights granted in this Agreement to American Express and to recipients of software distributed by American Express, You reserve all right, title, and interest, if any, in and to Your Contributions. Please [fill out the Agreement](http://goo.gl/forms/mIHWH1Dcuy).

# License

Any contributions made under this project will be governed by the [Apache License 2.0](https://github.com/americanexpress/bucketlist/blob/master/LICENSE.txt).

# Code of Conduct

This project adheres to the [American Express Community Guidelines](https://github.com/americanexpress/bucketlist/wiki/Code-of-Conduct).
By participating, you are expected to honor these guidelines.
