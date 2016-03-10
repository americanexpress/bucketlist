package io.aexp.bucketlist;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.restdriver.clientdriver.ClientDriverRule;
import com.google.common.io.Resources;
import com.ning.http.client.AsyncHttpClient;
import io.aexp.bucketlist.auth.Authenticator;
import io.aexp.bucketlist.data.CommentMode;
import io.aexp.bucketlist.data.Order;
import io.aexp.bucketlist.data.PagedResponse;
import io.aexp.bucketlist.data.PullRequest;
import io.aexp.bucketlist.data.PullRequestActivity;
import io.aexp.bucketlist.data.PullRequestCommit;
import io.aexp.bucketlist.data.PullRequestDiff;
import io.aexp.bucketlist.data.PullRequestDiffResponse;
import io.aexp.bucketlist.data.PullRequestHunk;
import io.aexp.bucketlist.data.PullRequestState;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static com.github.restdriver.clientdriver.ClientDriverRequest.Method.POST;
import static com.github.restdriver.clientdriver.RestClientDriver.giveResponse;
import static com.github.restdriver.clientdriver.RestClientDriver.onRequestTo;
import static com.google.common.io.Resources.getResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpBucketListClientTest {

    @Rule
    public ClientDriverRule driver = new ClientDriverRule();

    private BucketListClient client;
    private final String proj = "proj";
    private final String repo = "repo";

    @Before
    public void setUp() throws MalformedURLException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        Authenticator noOpAuth = requestBuilder -> requestBuilder;

        client = new HttpBucketListClient(new URL(driver.getBaseUrl()), noOpAuth, new AsyncHttpClient(),
                objectMapper.reader(),
                objectMapper.writer());
    }

    @Test
    public void testGetPrs() throws IOException {
        driver.addExpectation(onRequestTo("/rest/api/1.0/projects/" + proj + "/repos/" + repo + "/pull-requests")
                        .withParam("state", "ALL")
                        .withParam("start", "0")
                        .withParam("order", "NEWEST"),
                giveResponse(Resources.toString(getResource(getClass(), "getPrsResp.json"), UTF_8),
                        "application/json"));

        PagedResponse<PullRequest> firstPage = client.getPrs(proj, repo, PullRequestState.ALL, Order.NEWEST)
                .toBlocking()
                .first();

        List<PullRequest> values = firstPage.getValues();
        assertEquals(2, values.size());

        assertEquals(2, values.get(0).getId());

        assertEquals(ZonedDateTime.parse("2015-06-24T23:29:29Z[UTC]"), values.get(1).getCreatedAt());
    }

    @Test
    public void testGetPrsMultiPage() throws IOException {
        driver.addExpectation(onRequestTo("/rest/api/1.0/projects/" + proj + "/repos/" + repo + "/pull-requests")
                        .withParam("state", "ALL")
                        .withParam("start", "0")
                        .withParam("order", "NEWEST"),
                giveResponse(Resources.toString(getResource(getClass(), "getPrsRespPage1.json"), UTF_8),
                        "application/json"));

        driver.addExpectation(onRequestTo("/rest/api/1.0/projects/" + proj + "/repos/" + repo + "/pull-requests")
                        .withParam("state", "ALL")
                        .withParam("start", "25")
                        .withParam("order", "NEWEST"),
                giveResponse(Resources.toString(getResource(getClass(), "getPrsRespPage2.json"), UTF_8),
                        "application/json"));

        List<PagedResponse<PullRequest>> pages = client.getPrs(proj, repo, PullRequestState.ALL, Order.NEWEST)
                .toList()
                .toBlocking()
                .first();

        assertEquals(2, pages.size());

        PagedResponse<PullRequest> page1 = pages.get(0);
        assertEquals(25, page1.getValues().size());
        assertFalse(page1.isLastPage());
        assertEquals(669, page1.getValues().get(0).getId());

        PagedResponse<PullRequest> page2 = pages.get(1);
        assertEquals(25, page2.getValues().size());
        assertTrue(page2.isLastPage());
        assertEquals(632, page2.getValues().get(24).getId());
    }

    @Test
    public void testGetPr() throws IOException {
        driver.addExpectation(onRequestTo("/rest/api/1.0/projects/" + proj + "/repos/" + repo + "/pull-requests/2"),
                giveResponse(Resources.toString(getResource(getClass(), "getPr2Resp.json"), UTF_8),
                        "application/json"));

        PullRequest pr = client.getPr(proj, repo, 2)
                .toBlocking()
                .first();

        assertEquals(2, pr.getId());
        assertEquals(ZonedDateTime.parse("2015-06-25T18:06:19Z[UTC]"), pr.getCreatedAt());
    }

    @Test
    public void testGetPrActivity() throws IOException {
        driver.addExpectation(
                onRequestTo("/rest/api/1.0/projects/" + proj + "/repos/" + repo + "/pull-requests/2/activities")
                        .withParam("start", "0"),
                giveResponse(Resources.toString(getResource(getClass(), "getPr2ActivityResp.json"), UTF_8),
                        "application/json"));

        PagedResponse<PullRequestActivity> page = client.getPrActivity(proj, repo, 2)
                .toBlocking()
                .first();

        assertEquals(4, page.getValues().size());
        assertTrue(page.isLastPage());

        PullRequestActivity activity = page.getValues().get(3);
        assertEquals(38470, activity.getId());
        assertEquals("OPENED", activity.getAction());
        assertEquals(ZonedDateTime.parse("2015-06-25T18:06:19Z[UTC]"), activity.getCreatedAt());
        assertEquals("joe", activity.getUser().getName());

        PullRequestActivity commentActivity = page.getValues().get(2);
        assertEquals("COMMENTED", commentActivity.getAction());
        assertEquals("ADDED", commentActivity.getCommentAction());
        assertEquals("joe", commentActivity.getComment().getAuthor().getName());
        assertEquals("I'm a comment!", commentActivity.getComment().getText());
        assertEquals("jane", commentActivity.getComment().getComments().get(0).getAuthor().getName());
        assertEquals("I'm a reply", commentActivity.getComment().getComments().get(0).getText());
    }

    @Test
    public void testGetPrCommits() throws IOException {
        driver.addExpectation(
                onRequestTo("/rest/api/1.0/projects/" + proj + "/repos/" + repo + "/pull-requests/2/commits")
                        .withParam("start", "0"),
                giveResponse(Resources.toString(getResource(getClass(), "getPr2CommitsResp.json"), UTF_8),
                        "application/json"));

        PagedResponse<PullRequestCommit> page = client.getPrCommits(proj, repo, 2)
                .toBlocking()
                .first();

        assertEquals(2, page.getValues().size());
        assertTrue(page.isLastPage());

        PullRequestCommit commit1 = page.getValues().get(0);
        assertEquals("commit id 1", commit1.getId());
        assertEquals("Joe", commit1.getAuthor().getName());

        PullRequestCommit commit2 = page.getValues().get(1);
        assertEquals("commit id 2", commit2.getId());
        assertEquals("Jane", commit2.getAuthor().getName());
    }

    @Test
    public void testGetPrDiff() throws IOException {
        Map<String, Object> params = new HashMap();
        params.put("contextLines", "0");
        params.put("whitespace", "ignore-all");
        params.put("withComments", "false");

        driver.addExpectation(
                onRequestTo("/rest/api/1.0/projects/" + proj + "/repos/" + repo + "/pull-requests/2/diff")
                        .withParams(params),
                giveResponse(Resources.toString(getResource(getClass(), "getPrDiffResp.json"), UTF_8),
                        "application/json"));

        PullRequestDiffResponse diffResponse = client.getPrDiff(proj, repo, 2, 0, "ignore-all", CommentMode.WithoutComments)
                .toBlocking()
                .first();

        assertEquals("16fb16e8afbe6c4087d39feddd68bdc881e302a2", diffResponse.getFromHash());
        assertEquals("1830d0529a30d3d7253935d918ee0e34e7680c64", diffResponse.getToHash());
        assertEquals(2, diffResponse.getDiffs().size());

        PullRequestDiff secondDiff = diffResponse.getDiffs().get(1);
        assertEquals(null, secondDiff.getSource());
        assertEquals("Tests/Controllers/MyAwesomeControllerTests.swift", secondDiff.getDestination().getFullPath());
        assertFalse(secondDiff.getTruncated());
        assertEquals(2, secondDiff.getHunks().size());

        PullRequestHunk firstHunk = secondDiff.getHunks().get(0);
        assertEquals(0, firstHunk.getSourceLine());
        assertEquals(0, firstHunk.getSourceSpan());
        assertEquals(1, firstHunk.getDestinationLine());
        assertEquals(46, firstHunk.getDestinationSpan());
    }

    @Test
    public void testCreatePr() throws IOException {
        String expectedBody =
                "{\"title\":\"Sweet new feature\",\"description\":\"This feature is the best\",\"fromRef\":{\"id\":\"refs/heads/new-feature\",\"repo\":{\"project\":{\"key\":\"proj\"},\"slug\":\"repo\"}},\"toRef\":{\"id\":\"refs/heads/master\",\"repo\":{\"project\":{\"key\":\"proj\"},\"slug\":\"repo\"}}}";
        System.out.println(expectedBody);
        driver.addExpectation(
                onRequestTo("/rest/api/1.0/projects/" + proj + "/repos/" + repo + "/pull-requests")
                        .withMethod(POST)
                        .withBody(new JsonMatcher(expectedBody), "application/json"),
                giveResponse(Resources.toString(getResource(getClass(), "createPrResp.json"), UTF_8),
                        "application/json"));

        PullRequest pr =
                client.createPr(proj, repo, "Sweet new feature", "This feature is the best", "refs/heads/new-feature",
                        "refs/heads/master")
                        .toBlocking()
                        .first();

        assertEquals(3, pr.getId());
        assertEquals(ZonedDateTime.parse("2015-09-07T22:51:39.206Z[UTC]"), pr.getCreatedAt());
    }

    private static class JsonMatcher extends BaseMatcher<String> {

        private final String expectedBody;

        JsonMatcher(String expectedBody) {
            this.expectedBody = expectedBody;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("Expects json semantically equivalent to " + expectedBody);
        }

        @Override
        public boolean matches(Object item) {
            ObjectMapper objectMapper = new ObjectMapper();

            try {
                JsonNode expected = objectMapper.readValue(expectedBody, JsonNode.class);
                return expected.equals(objectMapper.readValue((String) item, JsonNode.class));
            } catch (IOException e) {
                throw new RuntimeException("Could not deserialize", e);
            }
        }
    }
}
