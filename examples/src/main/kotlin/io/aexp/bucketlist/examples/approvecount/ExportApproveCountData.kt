package io.aexp.bucketlist.examples.approvecount

import com.opencsv.CSVWriter
import io.aexp.bucketlist.BucketListClient
import io.aexp.bucketlist.data.PullRequestActivity
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
 * This example downloads all the approvals for all the PRs in the selected repository and writes data
 * about which users approved each PR
 *
 * We wanted to know how many times each of our contributors had given their stamp of approval to some PR.  This
 * is mostly useful in conjunction with other data sets (like the "Approve Before Commit" data set)
 *
 * This example is purely a data generator, because it's general purpose enough where we think it could be munged
 * with some other data sets to get the maximum utility.
 *
 * Usage:
 * - make a properties file containing 'username', 'password', and 'url' info for your Bitbucket-Server instance
 * - invoke with <path to props file> <project key> <repo slug> <output file>
 */
object ExportApproveCountData {

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

        val prsWithActivities = getPrsWithActivities(client, projectKey, repoSlug)

        val prsWithApprovers = getApprovers(prsWithActivities)

        printToCsv(outputCsvPath, prsWithApprovers)
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
     * From each of the PRs, collect a container that holds the names of all the approvers
     */
    private fun getApprovers(prsWithActivities: Observable<PrWithActivities>)
            : List<PrWithApprovers> {
        val prs = prsWithActivities
                .toSortedList({ pr1, pr2 -> pr1.prId.compareTo(pr2.prId) })
                .toBlocking()
                .first()

        // Get a set of all of the users who had an approve action on each PR
        val prsWithApprovers = ArrayList<PrWithApprovers>()
        for (prWithActivities in prs) {
            val approvers: MutableSet<String> = hashSetOf()
            for (activity in prWithActivities.activities) {
                approvers.add(activity.user.name)
            }
            prsWithApprovers.add(PrWithApprovers(prWithActivities.prId, approvers))
        }

        return prsWithApprovers
    }

    /**
     * Print a list of all PRs, along with a list of every approver for each PR.
     * Although this uses commas as separators, the columns don't have a strict meaning.
     * Each row represents a PR, and it expresses the names of the users who have
     * approved that PR.  Therefore it's possible to have lines without any usernames, although
     * based on your team's PR rules, it's likely that you'll have at least one approval
     * for every merged PR.
     *
     * In the example below, user1 has approved PR 2 and 3, user2 has approved PR 5, and user3 has
     * approved PR 3.  PRs 1 and 4 do not have any approvals.
     *
     * "1"
     * "2","user1"
     * "3","user1","user3"
     * "4"
     * "5","user2"
     */
    private fun printToCsv(outputCsvPath: Path, prsWithApprovers: List<PrWithApprovers>) {
        val outputCsvFile = outputCsvPath.toFile()
        CSVWriter(FileWriter(outputCsvFile)).use { writer ->
            for (pr in prsWithApprovers) {
                val row = ArrayList<String>()
                row.add(pr.prId.toString())
                row.addAll(pr.approvers)
                writer.writeNext(row.toArray(arrayOfNulls<String>(0)))
            }
        }
    }

    private class PrWithActivities(val prId: Long, val activities: List<PullRequestActivity>) {}

    private class PrWithApprovers(val prId: Long, val approvers: MutableSet<String>) {}

}
