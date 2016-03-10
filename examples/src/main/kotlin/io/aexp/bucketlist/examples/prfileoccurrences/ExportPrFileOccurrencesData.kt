package io.aexp.bucketlist.examples.prfileoccurrences

import com.google.common.collect.HashMultiset
import com.google.common.collect.Multiset
import com.google.common.collect.Multisets
import com.ning.http.client.AsyncHttpClient
import com.opencsv.CSVWriter
import io.aexp.bucketlist.BucketListClient
import io.aexp.bucketlist.WhitespaceMode
import io.aexp.bucketlist.data.CommentMode
import io.aexp.bucketlist.data.PullRequestState
import io.aexp.bucketlist.examples.getBitBucketClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import rx.Observable
import rx.schedulers.Schedulers
import java.io.FileWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.ArrayList

/**
 * This example downloads diff data for PRs in the specified repo and writes occurrence counts for files that appear in
 * at least one PR (sorted by occurrence count).
 *
 * We wanted to better inform our decision of when to refactor different classes by identifying which were more highly
 * correlated with bugs. So this determines in how many PRs across a given date range was each file changed. With the
 * 'branch filter substring', we were able to reduce the set to only ones including our 'bugfix' prefix.
 *
 * For this example we found that inspecting the top of the resulting list provided ample insights to which files
 * experienced the most churn with bugfixes, and thus there is no graphing component.
 *
 * Usages:
 * - make a properties file containing 'username', 'password', and 'url' info for your Bitbucket-Server instance
 * - invoke with <path to props file> <project key> <repo slug> <branch filter substring> <start date> <end date> <output file>
 */
object ExportPrFileOccurrencesData {
    val logger: Logger = LoggerFactory.getLogger(javaClass)

    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 7) {
            System.err!!.println("Must have 7 arguments: <config file> <project key> <repo slug> <branch filter substring> <start date> <end date> <output file>")
            System.exit(1)
        }

        val configPath = Paths.get(args[0])

        val httpClient = AsyncHttpClient()
        val client = getBitBucketClient(configPath, httpClient)
        val projectKey = args[1]
        val repoSlug = args[2]
        val branchFilterSubstring = args[3]

        val startDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[4]), LocalTime.MIDNIGHT), ZoneId.of("UTC"))
        // Make endDate inclusive by adding a day
        val endDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[5]), LocalTime.MIDNIGHT), ZoneId.of("UTC"))
                .plusDays(1)

        if (startDate.isAfter(endDate)) {
            System.err!!.println("Start date must be before end date")
            System.exit(1);
        }

        val outputCsvPath = Paths.get(args[6])


        val data = countFileOccurrencesInPrs(client, projectKey, repoSlug, branchFilterSubstring, startDate, endDate);

        printToCsv(outputCsvPath, data)

        httpClient.close()
    }

    /**
     * Fetches occurrence counts for files across PRs and writes the output to the specified CSV file
     */
    private fun countFileOccurrencesInPrs(client: BucketListClient, projectKey: String, repoSlug: String,
                                          branchFilterSubstring: String, startDate: ZonedDateTime,
                                          endDate: ZonedDateTime) : Multiset<String> {
        return getFlattenedCountOfFilesFromPrs(client, projectKey, repoSlug, branchFilterSubstring, startDate, endDate)
                .toBlocking()
                .first()
    }

    /**
     * Fetches files that have been changed in the specified PR and collects their associated filepaths. Filters for
     * files that have been modified without being created, renamed, or deleted.
     *
     * @return an observable of filepaths
     */
    private fun getFilesChangedInPr(prId: Long, client: BucketListClient, projectKey: String, repoSlug: String):
            Observable<String> {
        logger.debug("Fetching diff for PR " + prId);

        return client.getPrDiff(projectKey, repoSlug, prId, 0, WhitespaceMode.IgnoreAll, CommentMode.WithoutComments)
                .flatMap { diffResponse ->
                    logger.debug("Parsing PR for files changed");
                    // Filter diffs with Null sources or destinations as that indicates a new or deleted file. Then
                    // filter out diffs where the fullPath has changed to eliminate renamed files.
                    Observable.from(diffResponse.diffs)
                            .filter { diff ->
                                diff.source != null && diff.destination != null &&
                                        diff.source!!.fullPath == diff.destination!!.fullPath
                            }
                            .map { diff -> diff.source!!.fullPath }
                }
    }

    /**
     * Fetches paths for files changed with each PR within the filter criteria, and generates an aggregate Multiset of
     * filepaths where the counts are the occurrences of each file across PRs.
     *
     * @return an observable of a Multiset of filepaths
     */
    private fun getFlattenedCountOfFilesFromPrs(client: BucketListClient, projectKey: String, repoSlug: String,
                                                branchFilterSubstring: String, startDate: ZonedDateTime, endDate: ZonedDateTime):
            Observable<Multiset<String>> {
        // Get all of the PRs associated with the projectKey + repoSlug, and filter for those within the specified
        // startDate and endDate range. Then filter for only those that contain the branchFilterSubstring in the source
        // branch name. Extract the ids into a list.
        val prIds = client.getPrs(projectKey, repoSlug, PullRequestState.MERGED)
                .flatMap { prs -> Observable.from(prs.values) }
                .filter { pr ->
                    pr.createdAt.isBefore(endDate) && !(pr.closed && pr.updatedAt.isBefore(startDate))
                }
                .filter { pr -> pr.fromRef.id.contains(branchFilterSubstring, true) }
                .map { pr -> pr.id }

        val multiset: Multiset<String> = HashMultiset.create()

        // For each of the PRs, get list of files that have been changed. Then reduce them into one Multiset to get an
        // occurrence count across PRs
        return prIds.flatMap({ prId ->
            getFilesChangedInPr(prId, client, projectKey, repoSlug)
        })
                .observeOn(Schedulers.trampoline())
                .reduce(multiset, { set, filePath: String ->
                    set.add(filePath)
                    set
                })
    }

    /**
     * Print a list of all files that have been changed across the PRs and their corresponding occurrence count. The
     * result will be sorted by occurrence count in descending order.
     */
    private fun printToCsv(outputCsvPath: Path, filesWithCounts: Multiset<String>) {
        val outputCsvFile = outputCsvPath.toFile()
        CSVWriter(FileWriter(outputCsvFile)).use { writer ->
            for (file in Multisets.copyHighestCountFirst(filesWithCounts).elementSet()) {
                val row = ArrayList<String>()
                row.add(file)
                row.add(filesWithCounts.count(file).toString())
                writer.writeNext(row.toArray(arrayOfNulls<String>(0)))
            }
        }
        logger.info("Done writing CSV file")
    }
}
