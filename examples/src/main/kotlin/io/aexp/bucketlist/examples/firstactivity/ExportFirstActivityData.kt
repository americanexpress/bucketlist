package io.aexp.bucketlist.examples.firstactivity

import de.erichseifert.gral.data.DataSource
import de.erichseifert.gral.io.data.DataWriterFactory
import io.aexp.bucketlist.BucketListClient
import io.aexp.bucketlist.data.Order
import io.aexp.bucketlist.data.PullRequest
import io.aexp.bucketlist.data.PullRequestActivity
import io.aexp.bucketlist.data.PullRequestState
import io.aexp.bucketlist.examples.getBitBucketClient
import io.aexp.bucketlist.examples.getBoxWhiskerPlotDataTable
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Observable
import rx.observables.GroupedObservable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * This example looks at every merged PR in the specified repo and writes some statistics about them to a tsv for
 * later use
 *
 * We wanted to know how long a PR is typically open before it gets approvals or comments. This example looks at all
 * the merged PRs in a repo that start within the specified date range.  Then it finds the first activity that is either
 * a comment or approval. We use that to calculate the time to first activity, and then group that statistic by week.
 *
 * To make a box and whisker plot of the tsv, see RenderFirstActivityBoxWhiskerPlot
 *
 * Usage:
 * - make a properties file containing 'username', 'password', and 'url' info for your Bitbucket-Server instance
 * - invoke with <path to props file> <project key> <repo slug> <output file> <start date> <end date>
 * - dates should be in the format YYYY-MM-DD
 */
object ExportFirstActivityData {

    val logger: Logger = LoggerFactory.getLogger(javaClass)

    @JvmStatic fun main(args: Array<String>) {

        if (args.size != 6) {
            System.err!!.println("Must have 6 arguments: <config file> <project key> <repo slug> <output file> <start date> <end date>")
            System.exit(1)
        }

        val configPath = Paths.get(args[0])

        val client = getBitBucketClient(configPath)
        val projectKey = args[1]
        val repoSlug = args[2]

        val startDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[4]), LocalTime.MIDNIGHT), ZoneId.of("UTC"))
        val endDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[5]), LocalTime.MIDNIGHT), ZoneId.of("UTC"))

        val boxPlotDataTable = getBoxWhiskerPlotDataTable()

        getPrTimesByWeek(client, projectKey, repoSlug, startDate, endDate)
                // Start with a collection of PrSummary, grouped by the Monday of the week
                .flatMap({ summaryWithDate ->
                    // Create a stats holder for each week and add all the times to the stats holder
                    summaryWithDate.collect({ -> WeekStats(summaryWithDate.key) },
                            { statsHolder, summary ->
                                val timeToFirstActivity = summary.timeToFirstActivityInHours.toMillis().toDouble() / TimeUnit.HOURS.toMillis(1)
                                logger.info("Recording ${summaryWithDate.key} pr ${summary.pr.id} time to first activity: $timeToFirstActivity")
                                statsHolder.stats.addValue(timeToFirstActivity)
                            })
                })
                .toSortedList({ stats1, stats2 -> stats1.mondayOfWeek.compareTo(stats2.mondayOfWeek) })
                .toBlocking()
                .first()
                .forEachIndexed { i, statsHolder ->
                    boxPlotDataTable.add(i,
                            statsHolder.stats.getPercentile(50.0),
                            statsHolder.stats.min,
                            statsHolder.stats.getPercentile(25.0),
                            statsHolder.stats.getPercentile(75.0),
                            statsHolder.stats.max)

                }

        writeData(boxPlotDataTable, Paths.get(args[3]))
        logger.info("Done")
        System.exit(0)

    }

    /**
     * Load PrSummary objects for every PR and group them by the previous Monday.
     *
     * @return an observable of week-grouped observables (each of which is an observable of every PrSummary for the corresponding week)
     */
    private fun getPrTimesByWeek(client: BucketListClient, projectKey: String, repoSlug: String, startDate: ZonedDateTime, endDate: ZonedDateTime)
            : Observable<GroupedObservable<LocalDate, PrSummary>> {
        return client.getPrs(projectKey, repoSlug, PullRequestState.MERGED, Order.OLDEST)
                // Flatten the pages into a stream of all the PRs
                .flatMap({ prs -> Observable.from(prs.values) })
                .filter({ pr ->
                    pr.createdAt.isAfter(startDate) && pr.createdAt.isBefore(endDate)
                })
                .flatMap({ pr ->
                    logger.info("Getting activity for pr ${pr.id}")
                    // For each PR, create a PrSummary that contains all the activity
                    client.getPrActivity(projectKey, repoSlug, pr.id)
                            .flatMap({ page -> Observable.from(page.values) })
                            .toSortedList({ activity1, activity2 -> activity1.createdAt.compareTo(activity2.createdAt) })
                            .map({ activities -> PrSummary(pr, activities) })
                })
                .groupBy({ summary -> summary.mondayOfWeekofStart })
    }

    /**
     * Calculate the duration that a PR is open, but does not have any comments or approvals.  The list of
     * activities need to be pre-sorted by time (OLDEST first), because we look at them as if they
     * were a stream of activities emitted in order.
     */
    private fun calculateTimeToFirstActivity(prId: Long, activities: List<PullRequestActivity>): Duration {
        val openActivity = activities.first()
        for (activity in activities) {
            if (activity.action.equals("COMMENTED") || activity.action.equals("APPROVED")) {
                logger.info("Initial activity was ${openActivity.action} by ${openActivity.user.displayName} at ${openActivity.createdAt}")
                logger.info("First activity was ${activity.action} by ${activity.user.displayName} at ${activity.createdAt}")
                return Duration.between(openActivity.createdAt, activity.createdAt)
            }
        }

        logger.info("No comment or merge activity, returning duration of zero for PR $prId")
        return Duration.ZERO
    }

    /**
     * Simple container for a PR and its Activities that contains the property that is used for grouping (previous Monday)
     * and the property that we're actually analyzing (time to first activity)
     */
    class PrSummary(val pr: PullRequest, val activity: List<PullRequestActivity>) {
        val timeToFirstActivityInHours: Duration
            get() = calculateTimeToFirstActivity(pr.id, activity)

        val mondayOfWeekofStart: LocalDate
            get() = pr.createdAt.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

    /**
     * Wrapper to bundle a specific monday with its stats
     */
    class WeekStats(val mondayOfWeek: LocalDate, val stats: DescriptiveStatistics = DescriptiveStatistics()) {}

    /**
     * Write the populated DataSource into tsv for later graphing
     */
    fun writeData(ds: DataSource, path: Path) {

        val dataWriter = DataWriterFactory.getInstance().get("text/tab-separated-values")

        Files.newOutputStream(path).use { w ->
            dataWriter.write(ds, w)
        }
    }

}
