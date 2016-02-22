package io.aexp.bucketlist.examples.ownerwithtime

import com.opencsv.CSVWriter
import io.aexp.bucketlist.BucketListClient
import io.aexp.bucketlist.data.PullRequest
import io.aexp.bucketlist.data.PullRequestActivity
import io.aexp.bucketlist.data.PullRequestState
import io.aexp.bucketlist.examples.getBitBucketClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Observable
import java.io.FileWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.ZonedDateTime
import java.util.ArrayList
import java.util.concurrent.TimeUnit

/**
 * This example downloads the activity on all the merged PRs in the selected repository and writes
 * data about the PRs, including the owner and the start and end date.
 *
 * We wanted to get an overview of how our PRs are distributed across our team.  Additionally, we wanted
 * to be able to answer high level questions about how each contributor usually works within their PRs,
 * and this data should provide that.
 *
 * This example is not meant to power a specific graph or chart so it is purely a data generator.
 *
 * Usage:
 * - make a properties file containing 'username', 'password', and 'url' info for your Bitbucket-Server instance
 * - invoke with <path to props file> <project key> <repo slug> <output file>
 */
object ExportOwnerWithTimeData {

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

        val prsWithActivity = getPrsWithActivities(client, projectKey, repoSlug)

        val prsWithOwnerAndTime = getPrsWithOwnerAndTime(prsWithActivity)

        printToCsv(outputCsvPath, prsWithOwnerAndTime)
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
                            // Restrict the activity to only open and close events.
                            .flatMap({ pages -> Observable.from(pages.values) })
                            .filter { activity ->
                                activity.action.equals("OPENED") || activity.action.equals("MERGED")
                            }
                            .toSortedList({ activity1, activity2 -> activity1.createdAt.compareTo(activity2.createdAt) })
                            .map({ activities -> PrWithActivities(pr, activities) })
                })
    }

    /**
     * Convert a PR and it's activity into a container object that contains the owner and some simple time stats
     */
    private fun getPrsWithOwnerAndTime(prWithActivity: Observable<PrWithActivities>)
            : List<PrWithOwnerAndTime> {
        val prs = prWithActivity
                .toSortedList({ pr1, pr2 -> pr1.pr.id.compareTo(pr2.pr.id) })
                .toBlocking()
                .first()

        val prsWithOwnerAndTime = ArrayList<PrWithOwnerAndTime>()
        for (pr in prs) {
            // We assume here that the first activity on a PR is the open event and the
            // last event is a closing merge
            prsWithOwnerAndTime.add(PrWithOwnerAndTime(pr.pr.id,
                    pr.pr.author.user.name,
                    pr.activities.first().createdAt,
                    pr.activities.last().createdAt,
                    pr.durationInHours)
            )
        }

        return prsWithOwnerAndTime
    }

    /**
     * Print a list of all PRs, along with the owner and some simple time statistics.
     * It looks like:
     * "PR Id","Owner","Duration","Start","End"
     * "1","user1","0.12345","[ZonedDateTime]","[ZonedDateTime]"
     */
    private fun printToCsv(outputCsvPath: Path, prsWithApprovers: List<PrWithOwnerAndTime>) {
        val outputCsvFile = outputCsvPath.toFile()
        CSVWriter(FileWriter(outputCsvFile)).use { writer ->
            val headerRow: Array<String> = arrayOf("PR Id", "Owner", "Duration (hours)", "Start", "End")
            writer.writeNext(headerRow)
            for (pr in prsWithApprovers) {
                val row = ArrayList<String>()
                row.add(pr.prId.toString())
                row.add(pr.owner)
                row.add(pr.durationInHours.toString())
                row.add(pr.start.toString())
                row.add(pr.end.toString())
                writer.writeNext(row.toArray(arrayOfNulls<String>(0)))
            }
        }
    }

    private class PrWithActivities(val pr: PullRequest, val activities: List<PullRequestActivity>) {
        val durationInHours: Double
            get() = Duration.between(activities.first().createdAt, activities.last().createdAt).toMillis().toDouble() / TimeUnit.HOURS.toMillis(1)
    }

    private class PrWithOwnerAndTime(val prId: Long, val owner: String, val start: ZonedDateTime, val end: ZonedDateTime, val durationInHours: Double)

}
