package io.aexp.bucketlist

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.ObjectWriter
import com.ning.http.client.AsyncHttpClient
import com.ning.http.client.Response
import com.palominolabs.http.url.UrlBuilder
import io.aexp.bucketlist.auth.Authenticator
import io.aexp.bucketlist.data.NewPullRequest
import io.aexp.bucketlist.data.Order
import io.aexp.bucketlist.data.PagedResponse
import io.aexp.bucketlist.data.PullRequest
import io.aexp.bucketlist.data.PullRequestActivity
import io.aexp.bucketlist.data.PullRequestCommit
import io.aexp.bucketlist.data.PullRequestDiffResponse
import io.aexp.bucketlist.data.PullRequestState
import io.aexp.bucketlist.data.Ref
import io.aexp.bucketlist.data.Repo
import rx.Observable
import rx.Observer
import rx.subjects.ReplaySubject
import rx.subjects.Subject
import java.net.URL

/**
 * @param objectReader to avoid deserialization exceptions when new fields are added to json, this must be configured with DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false
 */
class HttpBucketListClient(private val baseUrl: URL,
                           private val authenticator: Authenticator,
                           private val httpClient: AsyncHttpClient,
                           private val objectReader: ObjectReader,
                           private val objectWriter: ObjectWriter) : BucketListClient {
    override fun getPrs(projectKey: String, repoSlug: String,
                        prState: PullRequestState, order: Order): Observable<PagedResponse<PullRequest>> {
        val subject: Subject<PagedResponse<PullRequest>, PagedResponse<PullRequest>> = ReplaySubject.create()

        requestPrsAndEmit(prState, projectKey, repoSlug, subject, order, 0)

        return subject
    }

    override fun getPr(projectKey: String, repoSlug: String, prId: Int): Observable<PullRequest> {
        val subject: Subject<PullRequest, PullRequest> = ReplaySubject.create()

        val urlBuilder = UrlBuilder.fromUrl(baseUrl)
                .pathSegments("rest", "api", "1.0", "projects", projectKey, "repos", repoSlug, "pull-requests",
                        prId.toString())

        addAuth(httpClient.prepareGet(urlBuilder.toUrlString()))
                .execute(object : RxResponseHandler<PullRequest>(subject) {
                    override fun handleSuccessfulResponse(response: Response, observer: Observer<PullRequest>) {
                        subject.onNext(objectReader
                                .forType(object : TypeReference<PullRequest>() {})
                                .readValue(response.responseBodyAsStream))
                        subject.onCompleted()
                    }
                })

        return subject
    }

    override fun getPrActivity(projectKey: String, repoSlug: String, prId: Long):
            Observable<PagedResponse<PullRequestActivity>> {
        val subject: Subject<PagedResponse<PullRequestActivity>, PagedResponse<PullRequestActivity>> = ReplaySubject.create()

        requestPrActivitiesAndEmit(projectKey, repoSlug, prId, subject, 0)

        return subject;
    }

    override fun getPrCommits(projectKey: String, repoSlug: String, prId: Long):
            Observable<PagedResponse<PullRequestCommit>> {
        val subject: Subject<PagedResponse<PullRequestCommit>, PagedResponse<PullRequestCommit>> = ReplaySubject.create()

        requestPrCommitsAndEmit(projectKey, repoSlug, prId, subject, 0)

        return subject;
    }

    override fun getPrDiff(projectKey: String, repoSlug: String, prId: Long, contextLines: Int, whitespace: String,
                           withComments: Boolean): Observable<PullRequestDiffResponse> {
        val subject: Subject<PullRequestDiffResponse, PullRequestDiffResponse> = ReplaySubject.create()

        val urlBuilder = UrlBuilder.fromUrl(baseUrl)
                .pathSegments("rest", "api", "1.0", "projects", projectKey, "repos", repoSlug, "pull-requests",
                        prId.toString(), "diff")
                .queryParam("contextLines", contextLines.toString())
                .queryParam("whitespace", whitespace)
                .queryParam("withComments", withComments.toString())

        addAuth(httpClient.prepareGet(urlBuilder.toUrlString()))
                .execute(object : RxResponseHandler<PullRequestDiffResponse>(subject) {
                    override fun handleSuccessfulResponse(response: Response, observer: Observer<PullRequestDiffResponse>) {
                        subject.onNext(objectReader
                                .forType(object : TypeReference<PullRequestDiffResponse>() {})
                                .readValue(response.responseBodyAsStream))
                        subject.onCompleted()
                    }
                })

        return subject
    }

    override fun createPr(projectKey: String, repoSlug: String, title: String, description: String, fromId: String,
                          toId: String): Observable<PullRequest> {
        val subject: Subject<PullRequest, PullRequest> = ReplaySubject.create()

        val urlBuilder = getBaseUrlForRepo(projectKey, repoSlug).pathSegment("pull-requests")

        val repo = Repo(projectKey, repoSlug)

        addAuth(httpClient.preparePost(urlBuilder.toUrlString()))
                .setBody(objectWriter.writeValueAsString(
                        NewPullRequest(title, description, Ref(fromId, repo), Ref(toId, repo))))
                .setHeader("Content-Type", "application/json")
                .execute(object : RxResponseHandler<PullRequest>(subject) {
                    override fun handleSuccessfulResponse(response: Response, observer: Observer<PullRequest>) {
                        observer.onNext(objectReader.forType(object : TypeReference<PullRequest>() {})
                                .readValue(response.responseBodyAsStream))
                    }

                })

        return subject
    }

    private fun getBaseUrlForRepo(projectKey: String, repoSlug: String): UrlBuilder {
        return UrlBuilder.fromUrl(baseUrl).pathSegments("rest", "api", "1.0", "projects", projectKey, "repos", repoSlug)
    }

    private fun requestPrActivitiesAndEmit(projectKey: String, repoSlug: String, prId: Long,
                                           observer: Observer<PagedResponse<PullRequestActivity>>, start: Int) {
        val urlBuilder = UrlBuilder.fromUrl(baseUrl)
                .pathSegments("rest", "api", "1.0", "projects", projectKey, "repos", repoSlug, "pull-requests",
                        prId.toString(), "activities")
                .queryParam("start", start.toString())

        addAuth(httpClient.prepareGet(urlBuilder.toUrlString()))
                .execute(object : PagedResponseHandler<PullRequestActivity>(objectReader,
                        object : TypeReference<PagedResponse<PullRequestActivity>>() {}, observer) {
                    override fun onNotLastPage(pagedResponse: PagedResponse<PullRequestActivity>) {
                        requestPrActivitiesAndEmit(projectKey, repoSlug, prId, observer, pagedResponse.nextPageStart)
                    }
                })
    }

    private fun requestPrCommitsAndEmit(projectKey: String, repoSlug: String, prId: Long,
                                        observer: Observer<PagedResponse<PullRequestCommit>>, start: Int) {
        val urlBuilder = UrlBuilder.fromUrl(baseUrl)
                .pathSegments("rest", "api", "1.0", "projects", projectKey, "repos", repoSlug, "pull-requests",
                        prId.toString(), "commits")
                .queryParam("start", start.toString())

        addAuth(httpClient.prepareGet(urlBuilder.toUrlString()))
                .execute(object : PagedResponseHandler<PullRequestCommit>(objectReader,
                        object : TypeReference<PagedResponse<PullRequestCommit>>() {}, observer) {
                    override fun onNotLastPage(pagedResponse: PagedResponse<PullRequestCommit>) {
                        requestPrCommitsAndEmit(projectKey, repoSlug, prId, observer, pagedResponse.nextPageStart)
                    }
                })
    }

    private fun requestPrsAndEmit(prState: PullRequestState, projectKey: String, repoSlug: String,
                                  observer: Observer<PagedResponse<PullRequest>>, order: Order, start: Int) {
        val urlBuilder = UrlBuilder.fromUrl(baseUrl)
        urlBuilder.pathSegments("rest", "api", "1.0", "projects", projectKey, "repos", repoSlug, "pull-requests")
                .queryParam("state", prState.name)
                .queryParam("start", Integer.toString(start))
                .queryParam("order", order.toString())

        addAuth(httpClient.prepareGet(urlBuilder.toUrlString()))
                .execute(object : PagedResponseHandler<PullRequest>(objectReader,
                        object : TypeReference<PagedResponse<PullRequest>>() {}, observer) {
                    override fun onNotLastPage(pagedResponse: PagedResponse<PullRequest>) {
                        requestPrsAndEmit(prState, projectKey, repoSlug, observer, order, pagedResponse.nextPageStart)
                    }
                })
    }

    private fun addAuth(requestBuilder: AsyncHttpClient.BoundRequestBuilder): AsyncHttpClient.BoundRequestBuilder {
        return authenticator.authenticate(requestBuilder)
    }

    abstract class PagedResponseHandler<T>(val objectReader: ObjectReader,
                                           val typeRef: TypeReference<PagedResponse<T>>,
                                           observer: Observer<PagedResponse<T>>) : RxResponseHandler<PagedResponse<T>>(
            observer) {

        abstract fun onNotLastPage(pagedResponse: PagedResponse<T>)

        override fun handleSuccessfulResponse(response: Response, observer: Observer<PagedResponse<T>>) {
            val pagedResponse: PagedResponse<T> = objectReader.forType(typeRef)
                    .readValue(response.responseBodyAsStream)

            observer.onNext(pagedResponse)

            if (!pagedResponse.isLastPage) {
                onNotLastPage(pagedResponse);
            } else {
                observer.onCompleted()
            }
        }
    }

}

