package io.aexp.bucketlist.examples.contributorsvstime

import de.erichseifert.gral.data.DataSource
import de.erichseifert.gral.data.DataTable
import de.erichseifert.gral.io.data.DataWriterFactory
import io.aexp.bucketlist.BucketListClient
import io.aexp.bucketlist.data.PullRequestActivity
import io.aexp.bucketlist.data.PullRequestCommit
import io.aexp.bucketlist.data.PullRequestState
import io.aexp.bucketlist.examples.getBitBucketClient
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Observable
import rx.observables.GroupedObservable
import rx.schedulers.Schedulers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * This example downloads the activity and commits for every PR in the specified repo that has been merged and writes
 * some of the aggregated data into a tsv for later use
 *
 * We wanted to know how the length of time that a PR stayed open related to the number of people who contribute to it.
 * For each PR, we collected all of the commits and created a set of committers.  Then we calculate the duration of the
 * PR and grouped the PRs by number of committers.
 *
 * To make a box and whisker plot of the tsv, use RenderContributorsVsTimeBoxWhiskerPlot
 *
 * Usage:
 * - make a properties file containing 'username', 'password', and 'url' info for your Bitbucket-Server instance.
 * - invoke with <path to props file> <project key> <repo slug> <output file>
 */
object ExportContributorsVsTimeData {

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

        val boxPlotDataTable = getDataTable()

        val durationsByCommitters = getPrDurationsByCommitters(client, projectKey, repoSlug)

        durationsByCommitters.flatMap({ durations ->
            durations.collect({ -> ContributorCountStats(durations.key) },
                    { statsHolder, record ->
                        val fractionalHours = record.duration.toMillis().toDouble() / TimeUnit.HOURS.toMillis(1)
                        logger.info("Recorded duration of $fractionalHours fractional hours for ${record.committers} committers")
                        statsHolder.stats.addValue(fractionalHours)
                    })
        }).toSortedList({ stats1, stats2 -> stats1.contributorCount.compareTo(stats2.contributorCount) })
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

    private fun getPrDurationsByCommitters(client: BucketListClient, projectKey: String, repoSlug: String)
            : Observable<GroupedObservable<Int, PrWithCommitsAndDuration>> {
        // Get all the PRs that have been merged
        val prsWithActivity = client.getPrs(projectKey, repoSlug, PullRequestState.MERGED)
                .flatMap({ prs -> Observable.from(prs.values) })
                .flatMap({ pr ->
                    // Get the activity for each PR, so we can calculate open duration
                    client.getPrActivity(projectKey, repoSlug, pr.id)
                            .flatMap({ page -> Observable.from(page.values) })
                            .toSortedList({ activity1, activity2 -> activity1.createdAt.compareTo(activity2.createdAt) })
                            .map({ activities -> PrWithActivities(pr.id, activities) })
                })


        return prsWithActivity.flatMap({ prWithActivity ->
            // Get a set of committers and store the count in a new data object
            getPrCommitters(prWithActivity.prId, client, projectKey, repoSlug)
                    .map({ authors ->
                        PrWithCommitsAndDuration(prWithActivity.duration, authors.count())
                    })
        })
                // Group the data objects, by number of contributors
                .groupBy({
                    prWithCommitsAndDurations ->
                    prWithCommitsAndDurations.committers
                })
    }

    /**
     * Fetch all the commits for a given PR and collect the authors into a set
     *
     * @return a set of all the people who committed to a PR
     */
    private fun getPrCommitters(prId: Long, client: BucketListClient, projectKey: String, repoSlug: String)
            : Observable<MutableSet<String>> {
        val authors: MutableSet<String> = hashSetOf()
        logger.info("Getting commits for PR $prId")
        return client.getPrCommits(projectKey, repoSlug, prId)
                .flatMap({ commits -> Observable.from(commits.values) })
                .observeOn(Schedulers.trampoline())
                .reduce(authors, { set, commit: PullRequestCommit ->
                    set.add(commit.author.name)
                    set
                })
    }

    /**
     * Wrapper to bundle a PR with its Activity so we can calculate duration
     */
    private class PrWithActivities(val prId: Long, val activities: List<PullRequestActivity>) {
        val duration: Duration
            get() = Duration.between(activities.first().createdAt, activities.last().createdAt)
    }

    /**
     * Wrapper to bundle the number of committers on a PR with a duration
     */
    private class PrWithCommitsAndDuration(val duration: Duration, val committers: Int) {}

    /**
     * Wrapper to bundle stats for groups of PRs (grouped by number of committers)
     */
    private class ContributorCountStats(val contributorCount: Int, val stats: DescriptiveStatistics = DescriptiveStatistics()) {}

    /**
     * @return DataTable configured with the appropriate columns for a box and whisker plot
     */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun getDataTable(): DataTable {
        return DataTable(Integer::class.java,
                Double::class.java,
                Double::class.java,
                Double::class.java,
                Double::class.java,
                Double::class.java)
    }

    /**
     * Write the populated DataSource into tsv for later graphing
     */
    private fun writeData(ds: DataSource, path: Path) {

        val dataWriter = DataWriterFactory.getInstance().get("text/tab-separated-values")

        Files.newOutputStream(path).use { w ->
            dataWriter.write(ds, w)
        }
    }
}
