package io.aexp.bucketlist.examples.concurrentprs

import com.google.common.collect.HashMultiset
import com.google.common.collect.Multiset
import com.opencsv.CSVWriter
import io.aexp.bucketlist.BucketListClient
import io.aexp.bucketlist.data.PullRequest
import io.aexp.bucketlist.data.PullRequestActivity
import io.aexp.bucketlist.data.PullRequestState
import io.aexp.bucketlist.examples.getBitBucketClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Observable
import rx.observables.GroupedObservable
import java.io.FileWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.ArrayList

/**
 * This example downloads all the activity on all the PRs in the selected repository and attempts to determine
 * the maximum number of PRs that each user could have had open during that time.
 *
 * We wanted to get a sense of how many PRs our team typically has open, and even more importantly, how many PRs each
 * person has open at any given time.  We wanted to look at about a week's worth of activity to prevent too much noise
 * from being generated, so we started by breaking down the most recent week by hour and calculating how many
 * PRs could have been open by author.
 *
 * This should allow us to see how many PRs our team as a whole has open, but also how many PRs each person has
 * at a given time.
 *
 * Usage:
 * - make a properties file containing 'username', 'password', and 'url' info for your Bitbucket-Server instance
 * - invoke with <path to props file> <project key> <repo slug> <output file> <start date> <end date>
 * - dates should be in the format YYYY-MM-DD
 */
object ExportConcurrentPrData {

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
        val outputCsvPath = Paths.get(args[3])

        val startDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[4]), LocalTime.MIDNIGHT), ZoneId.of("UTC"))
        val endDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[5]), LocalTime.MIDNIGHT), ZoneId.of("UTC"))

        if (startDate.isAfter(endDate)) {
            System.err!!.println("Start date must be before end date")
            System.exit(1);
        }

        val prsByAuthor = getPrsByAuthor(client, projectKey, repoSlug, startDate, endDate)

        val authorStats = prsByAuthor.flatMap({ authorWithPrs ->
            authorWithPrs.collect({ -> AuthorStats(authorWithPrs.key) },
                    { authorHolder, prSummary ->
                        val truncatedOpenTime = prSummary.openDate.truncatedTo(ChronoUnit.HOURS)
                        val truncatedCloseTime = prSummary.closeDate.truncatedTo(ChronoUnit.HOURS)
                        logger.info("Adding PR opened by ${authorHolder.author} at $truncatedOpenTime")
                        authorHolder.openedPrs.add(truncatedOpenTime)
                        logger.info("Adding PR closed by ${authorHolder.author} at $truncatedCloseTime")
                        authorHolder.closedPrs.add(truncatedCloseTime)
                    })
        })
                .toSortedList({ authors1, authors2 -> authors1.author.compareTo(authors2.author) })
                .toBlocking()
                .first()

        printBetterCsv(outputCsvPath, authorStats, startDate, endDate)
        logger.info("Done writing CSV")
        System.exit(0)

    }

    /**
     * Get a collection of PRs with their respective open and close activities, grouped by the person who created the PR
     */
    private fun getPrsByAuthor(client: BucketListClient, projectKey: String, repoSlug: String, startDate: ZonedDateTime, endDate: ZonedDateTime)
            : Observable<GroupedObservable<String, PrSummary>> {
        return client.getPrs(projectKey, repoSlug, PullRequestState.ALL)
                .flatMap({ prPages -> Observable.from(prPages.values) })
                .filter({ pr ->
                    // Don't bother getting activity for any PRs that were created after the end date
                    // Don't bother getting activity for any PRs that are closed, where the last update was before the start date.
                    pr.createdAt.isBefore(endDate) && !(pr.closed && pr.updatedAt.isBefore(startDate))
                })
                .flatMap({ pr ->
                    client.getPrActivity(projectKey, repoSlug, pr.id)
                            .flatMap({ activityPage -> Observable.from(activityPage.values) })
                            .filter({ activity ->
                                // We only care about activity that opens or closes the PR, filter everything else out
                                activity.action.equals("OPENED") || activity.action.equals("MERGED") || activity.action.equals("DECLINED")
                            })
                            .toSortedList({ activity1, activity2 -> activity1.createdAt.compareTo(activity2.createdAt) })
                            .map({ activities -> PrSummary(pr, activities) })
                })
                .groupBy({ pr -> pr.pr.author.user.name })
    }

    private class AuthorStats(val author: String, val openedPrs: Multiset<ZonedDateTime> = HashMultiset.create<ZonedDateTime>(),
                              val closedPrs: Multiset<ZonedDateTime> = HashMultiset.create<ZonedDateTime>(),
                              val totalPrs: Multiset<ZonedDateTime> = HashMultiset.create<ZonedDateTime>())

    /**
     * Container class to hold a PR and its activities.  Also supplies a few convenience methods for getting
     * statistics relevant to the timeline of the PR
     */
    private class PrSummary(val pr: PullRequest, val activities: List<PullRequestActivity>) {
        val openDate: ZonedDateTime
            get() = pr.createdAt

        val closeDate: ZonedDateTime
                // We assume that the last event of the PR is either a merge or a decline.  Theoretically you can take
                // more action after that, but this combined with out filter should be enough to capture most cases.
                // Note, PRs that are still open will not have a close event, so it will look like closeDate == openDate
            get() = activities.last().createdAt
    }

    /**
     * Given a collection of data, where each author has a multiset of times where they had some number of PRs
     * open, print that to a CSV file
     *
     * Each user will have a column of integrers that correspond to the maximum number of PRs that they could
     * have had open during that time period.
     *
     * For example, if user1 starts out at time t with 2 PRs open and, over the duration that it takes to get to time
     * t2, opens 3 more PRs, then we should print a 5 in the t column for that user.  However, it might also be
     * true that user1 merged 1 PR during that time, so time t2 would have an initial value of 4.
     */
    private fun printBetterCsv(outputCsvPath: Path, authorStats: List<AuthorStats>, startDate: ZonedDateTime, endDate: ZonedDateTime) {
        val times = getTimesBetween(startDate, endDate)
        // totalAuthorStats is actually the same list as the authorStats argument, except that the totalPrs
        // multiset has been filled out, based on the openPrs and closedPrs sets.  It isn't necessary to keep
        // the extra instance, but I think it makes it clearer that it's been operated on.
        val totalAuthorStats = calculateTotalPrs(times, authorStats)

        val outputCsvFile = outputCsvPath.toFile()
        CSVWriter(FileWriter(outputCsvFile)).use { writer ->
            // Header row
            val authors = ArrayList<String>(listOf("Time"))
            for (author in totalAuthorStats) {
                authors.add(author.author)
            }
            writer.writeNext(authors.toArray(arrayOfNulls<String>(0)))


            // For every time that we're interested in, look at each author and get the count of times that we've
            // added something into their PR set.
            for (time in times) {
                logger.info("Looking for PRs on $time")
                val row = ArrayList<String>(listOf(time.toString()))

                for (authorStat in totalAuthorStats) {
                    row.add(authorStat.totalPrs.count(time).toString())
                }
                logger.info("Writing row for day $time")
                writer.writeNext(row.toArray(arrayOfNulls<String>(0)))
            }
        }
    }

    /**
     * We think that the most valuable piece of data from this set is the total number of PRs that could have been
     * open during a time slot.  We calculate that by adding the number of PRs that a user had open at the start
     * of the time slot to the number of PRs that they opened during the time slot.  However, it's also possible
     * that they closed some number of PRs during that time slot, so the following slot should be the maximum
     * described above, minus the number of PRs that the user closed.
     */
    private fun calculateTotalPrs(times: List<ZonedDateTime>, authorStats: List<AuthorStats>)
            : List<AuthorStats> {
        // Write the first time period as a base case.  All the future time slots will rely on the first one
        // being filled out
        val firstTime = times[0]
        for (authorStat in authorStats) {
            // Note, our first time slot only takes into account the number of PRs that are opened during that time.
            // This could mean that the user already has some PRs open.  We think that we can ignore those for now,
            // but we'll have to provide a little extra safety later to make sure we don't set a count of < 0.
            val openedPrs = authorStat.openedPrs.count(firstTime)
            authorStat.totalPrs.setCount(firstTime, openedPrs)
        }

        // Now that we have a base case, look at the rest of the time slots and calculate the total value based
        // on the previous total value, along with opens and closes.
        // Note that we ignore the first element, because that had to be manually set above
        for (i in times.subList(1, times.count()).indices) {
            for (authorStat in authorStats) {
                // This is a bit weird, but we're actually trying to set the count at the next timeslot, given
                // the count at the current timeslot, so we have to shift our indices to match what we're looking for.
                // Hence, the previous time is the current index and the current time is the i + 1 index.  We made sure
                // not to go out of bounds on the list by sublisting in the outer for loop.
                val currentTime = times[i + 1]
                val previousTime = times[i]
                val previouslyOpenCount = authorStat.totalPrs.count(previousTime)
                val previouslyClosedCount = authorStat.closedPrs.count(previousTime)
                val currentOpenCount = getOpenPrsForNextTime(currentTime, previouslyOpenCount, previouslyClosedCount, authorStat.openedPrs)
                authorStat.totalPrs.setCount(currentTime, if (currentOpenCount < 0) 0 else currentOpenCount)
            }
        }

        return authorStats
    }

    /**
     * Collect a list of times that we want to use as inputs to calculate the number of PRs at.
     */
    private fun getTimesBetween(startDate: ZonedDateTime, endDate: ZonedDateTime)
            : List<ZonedDateTime> {
        val times = ArrayList<ZonedDateTime>()
        var time = startDate
        while (time.isBefore(endDate)) {
            times.add(time)
            time = time.plusHours(1)
        }

        return times
    }

    /**
     * Calculation to get the current row of a CSV, based on the previous row.
     */
    private fun getOpenPrsForNextTime(time: ZonedDateTime, previouslyOpenCount: Int, previouslyClosedCount: Int, openedPrs: Multiset<ZonedDateTime>)
            : Int {
        return previouslyOpenCount + openedPrs.count(time) - previouslyClosedCount
    }

}
