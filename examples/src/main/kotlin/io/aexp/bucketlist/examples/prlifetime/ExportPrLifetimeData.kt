package io.aexp.bucketlist.examples.prlifetime

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
import rx.schedulers.Schedulers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime.MIDNIGHT
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * This example downloads activity data for every PR in the specified repo and writes some statistics about them to a
 * tsv for later use.
 *
 * We wanted to know trends in PR lifetime for each week, so this calculates the duration of each pr, and groups that
 * together by week. Each week is then summarized into min, max, etc and written as a row in the tsv.
 *
 * To make a box and whisker plot of the tsv, see RenderPrLifetimeBoxWhiskerPlot.
 *
 * Usage:
 * - make a properties file containing 'username', 'password', and 'url' info for your Bitbucket-Server instance.
 * - invoke with <path to props file> <project key> <repo slug> <output file> <start date> <end date> <start duration from>
 * - dates should be in the format YYYY-MM-DD
 */
object ExportPrLifetimeData {

    val logger: Logger = LoggerFactory.getLogger(javaClass)

    enum class DurationStart {
        prCreation,
        lastPrCommitPushed
    }

    @JvmStatic fun main(args: Array<String>) {

        if (args.size != 7) {
            System.err!!.println("Must have 6 arguments: <config file> <project key> <repo slug> <output file> <start date> <end date> <start duration from>")
            System.exit(1)
        }

        val configPath = Paths.get(args[0])

        val client = getBitBucketClient(configPath)
        val projectKey = args[1]
        val repoSlug = args[2]

        val startDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[4]), MIDNIGHT), ZoneId.of("UTC"))
        val endDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[5]), MIDNIGHT), ZoneId.of("UTC"))

        var startDurationFrom: DurationStart
        try {
            startDurationFrom = DurationStart.valueOf(args[6])
        } catch (e: IllegalArgumentException) {
            System.err!!.println("`start duration from` parameter must be `prCreation` or `lastPrCommitPushed`")
            System.exit(1)
            return
        }

        val boxPlotDataTable = getBoxWhiskerPlotDataTable()

        getPrSummariesByWeek(client, projectKey, repoSlug, startDate, endDate)
                .flatMap({ weekOfHours ->
                    weekOfHours.collect({ -> WeekStats(weekOfHours.key) },
                            { statsHolder, summary ->
                                // fractional hours
                                var duration : Duration
                                if (startDurationFrom == DurationStart.prCreation) {
                                    duration = summary.durationSinceStart
                                } else {
                                    duration = summary.durationSinceLastPRCommitPushed
                                }
                                val fractionalHours = duration.toMillis().toDouble() / TimeUnit.HOURS.toMillis(
                                        1)
                                logger.info(
                                        "Recording ${weekOfHours.key} pr ${summary.pr.id} duration: $fractionalHours")
                                statsHolder.stats.addValue(fractionalHours)
                            })
                })
                .toSortedList({ stats1, stats2 -> stats1.mondayOfWeek.compareTo(stats2.mondayOfWeek) })
                .toBlocking()
                .first()
                .forEachIndexed {
                    i, statsHolder ->
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
     * Loads PrSummary objects for every PR and groups them by the previous Monday.
     *
     * @return an observable of week-grouped observables (each of which is an observable of every PrSummary for the corresponding week)
     */
    private fun getPrSummariesByWeek(client: BucketListClient, projKey: String,
                                     repoSlug: String, startDate: ZonedDateTime, endDate: ZonedDateTime)
            : Observable<GroupedObservable<LocalDate, PrSummary>> {
        return client.getPrs(projKey, repoSlug, PullRequestState.MERGED, Order.OLDEST)
                .observeOn(Schedulers.io())
                // flatten pages into one stream of prs
                .flatMap({ prs -> Observable.from(prs.values) })
                .filter({ pr ->
                    // Don't bother getting activity for any PRs that were created after the end date
                    // Don't bother getting activity for any PRs that are closed, where the last update was before the start date.
                    pr.createdAt.isBefore(endDate) && !(pr.closed && pr.updatedAt.isBefore(startDate))
                })
                .flatMap({ pr ->
                    // gather all activity for each pr into a PrSummary
                    client.getPrActivity(projKey, repoSlug, pr.id)
                            .flatMap({ page -> Observable.from(page.values) })
                            .toSortedList({ a1, a2 -> a1.createdAt.compareTo(a2.createdAt) })
                            .map({ list -> PrSummary(pr, list) })
                })
                .groupBy({ summary -> summary.mondayOfWeekOfStart })
    }

    /**
     * Wrapper to bundle a specific monday with its stats
     */
    data class WeekStats(val mondayOfWeek: LocalDate, val stats: DescriptiveStatistics = DescriptiveStatistics()) {}

    /**
     * Wrapper to bundle a PR with its activity
     */
    data class PrSummary(val pr: PullRequest, val activity: List<PullRequestActivity>) {
        val durationSinceStart: Duration
            get() = Duration.between(activity.first().createdAt, activity.last().createdAt)

        val durationSinceLastPRCommitPushed: Duration
            get() {
                var mergeTime: ZonedDateTime = activity.filter({ event -> event.action == "MERGED" }).last().createdAt
                var lastCommitTime: ZonedDateTime = activity.filter({ event -> event.action == "RESCOPED" || event.action == "OPENED" }).last().createdAt

                return Duration.between(lastCommitTime, mergeTime)
            }

        val mondayOfWeekOfStart: LocalDate
            get() = pr.createdAt.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

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
