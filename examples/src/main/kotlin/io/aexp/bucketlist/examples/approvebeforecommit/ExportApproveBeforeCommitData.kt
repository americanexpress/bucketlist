package io.aexp.bucketlist.examples.approvebeforecommit

import com.opencsv.CSVWriter
import io.aexp.bucketlist.BucketListClient
import io.aexp.bucketlist.data.PullRequestActivity
import io.aexp.bucketlist.data.PullRequestCommit
import io.aexp.bucketlist.data.PullRequestState
import io.aexp.bucketlist.examples.getBitBucketClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Observable
import java.io.FileWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList

/**
 * This example downloads the approvals and commits made on all the PRs in the selected repository and writes
 * data about PRs where a non-merge commit was made after an approval.
 *
 * We wanted to know how much code was being introduced to the repository without being reviewed.  We were worried
 * that some PRs were getting approved by the requisite number of users, but later having the author go back and fix
 * an issue with a new commit.  Unless a new person reviewed the latest commit, the new code would be unreviewed.
 *
 * This example is purely a data generator because we found that the data was sparse enough that typical graphing
 * methods would not be particularly instructive.
 *
 * Usage:
 * - make a properties file containing 'username', 'password', and 'url' info for your Bitbucket-Server instance
 * - invoke with <path to props file> <project key> <repo slug> <output file>
 */
object ExportApproveBeforeCommitData {

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

        var prsWithActivities = getPrsWithActivities(client, projectKey, repoSlug)

        val prsWithActivitiesAndCommits = getPrsWithActivitiesAndCommits(prsWithActivities, client, projectKey, repoSlug)

        val prsWithEarlyApprovers = getEarlyApprovers(prsWithActivitiesAndCommits)

        printToCsv(outputCsvPath, prsWithEarlyApprovers)

        logger.info("Done writing CSV file")
        System.exit(0)
    }

    /**
     * Collect all the approvals associated with a PR and store it in a data object
     */
    private fun getPrsWithActivities(client: BucketListClient, projectKey: String, repoSlug: String)
            : Observable<PrWithActivities> {
        return client.getPrs(projectKey, repoSlug, PullRequestState.MERGED)
                .flatMap({ prs -> Observable.from(prs.values) })
                .flatMap({ pr ->
                    logger.info("Getting activity for PR ${pr.id}")
                    client.getPrActivity(projectKey, repoSlug, pr.id)
                            .flatMap({ pages -> Observable.from(pages.values) })
                            .filter { activity ->
                                activity.action.equals("APPROVED")
                            }
                            .toSortedList({ activity1, activity2 -> activity1.createdAt.compareTo(activity2.createdAt) })
                            .map({ activities -> PrWithActivities(pr.id, activities) })
                })
    }

    /**
     * Collect all the non-merge commits associated with a PR and store it in a data object
     */
    private fun getPrsWithActivitiesAndCommits(prsWithActivities: Observable<PrWithActivities>, client: BucketListClient, projectKey: String, repoSlug: String)
            : Observable<PrWithActivitiesAndCommits> {
        return prsWithActivities.flatMap({ prWithActivity ->
            logger.info("Getting commits for PR ${prWithActivity.prId}")
            client.getPrCommits(projectKey, repoSlug, prWithActivity.prId)
                    .flatMap({ page -> Observable.from(page.values) })
                    .filter { commit ->
                        !commit.message.contains("Merge branch")
                    }
                    .toSortedList({ commit1, commit2 -> commit1.createdAt.compareTo(commit2.createdAt) })
                    .map({ commits -> PrWithActivitiesAndCommits(prWithActivity.prId, prWithActivity.activities, commits) })
        })
    }

    /**
     * Traverse the list of data objects containing approvals and commits and find users who
     * made an approval before the last commit was made
     */
    private fun getEarlyApprovers(prs: Observable<PrWithActivitiesAndCommits>)
            : List<PrWithEarlyApprovers> {
        val prList = prs.toSortedList({ p1, p2 -> p1.prId.compareTo(p2.prId) })
                .toBlocking()
                .first()

        // Keep a list of all PRs, along with a list for each of early approvers
        val prsWithApprovers = ArrayList<PrWithEarlyApprovers>(listOf())
        for (pr in prList) {
            prsWithApprovers.add(PrWithEarlyApprovers(pr.prId, getEarlyApproversForPr(pr)))
        }
        return prsWithApprovers
    }

    /**
     * Examine a single PR and find any approvals that happened before a
     * non-merge commit. If a PR does not have early approvals, it will
     * still create an entry, but with an empty list
     */
    private fun getEarlyApproversForPr(pr: PrWithActivitiesAndCommits)
            : MutableSet<String> {
        val earlyApprovers: MutableSet<String> = hashSetOf()
        for (activity in pr.activities) {
            for (commit in pr.commits) {
                if (activity.createdAt.isBefore(commit.createdAt)) {
                    // This might trigger multiple times, because multiple
                    // commits could happen after an approval. This works with
                    // the current Set, but it might be a good start for another
                    // example
                    logger.info("${activity.user.name} was an early approver for PR ${pr.prId}")
                    earlyApprovers.add(activity.user.name)
                }
            }
        }

        return earlyApprovers
    }

    /**
     * Print a list of all PRs, along with a list of every early approver for each PR.
     * Although this uses commas as separators, this isn't a CSV in the strict sense.
     * It looks more like:
     * "1"
     * "2","user1"
     * "3","user1","user3"
     * "4"
     * "5","user2"
     */
    private fun printToCsv(outputCsvPath: Path, prsWithApprovers: List<PrWithEarlyApprovers>) {
        val outputCsvFile = outputCsvPath.toFile()
        CSVWriter(FileWriter(outputCsvFile)).use { writer ->
            for (pr in prsWithApprovers) {
                val row = ArrayList<String>(listOf())
                row.add(pr.prId.toString())
                row.addAll(pr.earlyApprovers)
                writer.writeNext(row.toArray(arrayOfNulls<String>(0)))
            }
        }
    }

    private class PrWithActivities(val prId: Long, val activities: List<PullRequestActivity>) {}

    private class PrWithActivitiesAndCommits(val prId: Long, val activities: List<PullRequestActivity>, val commits: List<PullRequestCommit>) {}

    private class PrWithEarlyApprovers(val prId: Long, val earlyApprovers: MutableSet<String>) {}

}
