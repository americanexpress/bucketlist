package io.aexp.bucketlist.examples.commentcounter

import com.google.common.collect.HashMultiset
import com.google.common.collect.Multiset
import com.opencsv.CSVWriter
import io.aexp.bucketlist.BucketListClient
import io.aexp.bucketlist.data.PullRequestActivity
import io.aexp.bucketlist.data.PullRequestComment
import io.aexp.bucketlist.data.PullRequestState
import io.aexp.bucketlist.examples.getBitBucketClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Observable
import rx.schedulers.Schedulers
import java.io.FileWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList
import java.util.LinkedHashSet

/**
 * This example downloads all the comments made on all of the PRs in the selected repository and writes
 * data about how much each team member is commenting into a csv for later use
 *
 * We wanted to know how much our users are commenting on PRs on an individual basis, so we collect all the comments
 * over the lifetime of the project and then group those comments by PR Id.  Since team members join the team at
 * different times, you'll see zero counts for any time where a person had not yet started on a team or in other
 * cases where they weren't working on PRs.
 *
 * This example is purely a data generator because we found our comment data to be too noisy to generate a meaningful
 * plot.
 *
 * Usage:
 * - make a properties file containing 'username', 'password', and 'url' info for your Bitbucket-Server instance
 * - invoke with <path to props file> <project key> <repo slug> <output file>
 */
object ExportCommentCountData {
    val logger: Logger = LoggerFactory.getLogger(javaClass)

    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 4) {
            System.err!!.println("Must have 4 arguments: <config file> <project key> <repo slug> <output file>")
            System.exit(1)
        }

        val configPath = Paths.get(args[0])

        val client = getBitBucketClient(configPath)
        val projectKey = args[1]
        val repoSlug = args[2]
        val outputCsvPath = Paths.get(args[3])

        countPrCommentsPerUserPerPr(client, projectKey, repoSlug, outputCsvPath)
    }

    /**
     * Get all the PRs over the life of the project and then count the number of comments that each user has made
     * on each PR.
     */
    private fun countPrCommentsPerUserPerPr(client: BucketListClient, projectKey: String, repoSlug: String,
                                            outputCsvPath: Path) {
        // Get all of the PRs and extract the ids into a list
        val prIds = client.getPrs(projectKey, repoSlug, PullRequestState.ALL)
                .flatMap { prs -> Observable.from(prs.values) }
                .map { pr -> pr.id }

        // For each of the PRs, get a list of flattened comments, and map that to a set of counts that
        // represent how many comments each user made on that PR. Note that only users who made comments on this
        // specific PR will show up in the set, so we need to re-aggregate the users later and mark our missing
        // users as having commented zero times on that PR
        val prsWithCounts = prIds.flatMap { prId ->
            logger.info("Getting comments for pr $prId")
            getFlattenedCountOfPrComments(prId, client, projectKey, repoSlug)
                    .map { counts -> PrWithCommentCounts(prId, counts) }
        }.toSortedList(
                { p1, p2 -> p1.prId.compareTo(p2.prId) }
        ).toBlocking()
                .first()

        // Write each PR with a count of each users comments to a CSV file
        printToCsv(outputCsvPath, prsWithCounts)
        logger.info("Done writing CSV file")
        System.exit(0)

    }

    /**
     * Record the counts of comments on the PR in the CSV format:
     *
     * "user1","user2","user3"
     * "1","0","0","2"
     * "2","0","1","0"
     * "3","1","1","0"
     *
     * Note that the header row is just a collection of users who have commented on a PR at some point
     * and the non-header rows display the Id of the PR in question in the first column and then represent
     * the comment counts of the users in the remaining columns. Depending on which visualization method
     * you use, this might require adding an empty field to the header row after the data is produced.
     */
    private fun printToCsv(outputCsvPath: Path, prsWithCounts: List<PrWithCommentCounts>) {
        val ids = LinkedHashSet<String>()

        // create the iteration order for names
        prsWithCounts.forEach({ p -> p.counts.entrySet().forEach { e -> ids.add(e.element) } })

        val outputCsvFile = outputCsvPath.toFile()
        CSVWriter(FileWriter(outputCsvFile)).use { writer ->
            // Header row.  Note, this does not include a header column for the "PR Id" field that is first in
            // subsequent rows
            writer.writeNext(ids.toTypedArray())

            prsWithCounts.forEach({ p ->
                // Map to count, if this pr has something for that user. If the user is not in the set
                // of users for that PR, they should have a count of 0
                val row = ArrayList<String>(listOf(p.prId.toString()))

                ids.map {
                    id ->
                    p.counts.count(id)
                }.map {
                    count ->
                    count.toString()
                }.toCollection(row)

                logger.info("Row: $row")
                val rowArray = row.toArray(arrayOfNulls<String>(0))
                writer.writeNext(rowArray)
            })
        }
    }

    // Wrapper to bundle a PR Id with a set of users who commented on it.
    private class PrWithCommentCounts(val prId: Long, val counts: Multiset<String>)

    /**
     * Given a PR Id, get a multiset of all of the users who made comments on that PR.  This should include
     * users who commented on comments, so we need to flatten each Activity that represents a comment.
     *
     * @return an Observable of a set that contains the users who commented on the PR and the number of
     * times that each of them commented on it
     */
    private fun getFlattenedCountOfPrComments(prId: Long, client: BucketListClient, projectKey: String, repoSlug: String)
            : Observable<Multiset<String>> {

        val multiset: Multiset<String> = HashMultiset.create<String>()
        return client.getPrActivity(projectKey, repoSlug, prId)
                .flatMap({ activities ->
                    Observable.from(activities.values)
                            .filter { activity ->
                                activity.action.equals("COMMENTED")
                                        && "ADDED".equals(activity.commentAction)
                                        && activity.comment != null
                            }
                })
                .observeOn(Schedulers.trampoline())
                .reduce(multiset, { set, activity: PullRequestActivity ->
                    set.addAll(flattenComment(activity.comment!!))
                    set
                })
    }

    /**
     * Given a comment, get a list of people who commented on that comment.  This is necessary because Bitbucket-Server
     * represents comments on a comment not as a separate element, but as an array of comments (which could
     * then have their own comment).
     *
     * This is called recursively, to make sure that each comment is counted, even if it's a comment on
     * a comment
     *
     * @comment the comment to count the subcomments for
     */
    private fun flattenComment(comment: PullRequestComment): List<String> {
        val authors = ArrayList<String>()
        authors.add(comment.author.name)
        for (c in comment.comments) {
            authors.addAll(flattenComment(c))
        }
        return authors;
    }

}
