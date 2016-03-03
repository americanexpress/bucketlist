package io.aexp.bucketlist.examples.prfileoccurrences

import com.google.common.collect.HashMultiset
import com.google.common.collect.Multiset
import com.google.common.collect.Multisets
import com.opencsv.CSVWriter
import io.aexp.bucketlist.BucketListClient
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

object ExportPrFileOccurrencesData {
    val logger: Logger = LoggerFactory.getLogger(javaClass)

    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 7) {
            System.err!!.println("Must have 7 arguments: <config file> <project key> <repo slug> <branch filter substring> <output file> <start date> <end date>")
            System.exit(1)
        }

        val configPath = Paths.get(args[0])

        val client = getBitBucketClient(configPath)
        val projectKey = args[1]
        val repoSlug = args[2]
        val branchFilterSubstring = args[3]
        val outputCsvPath = Paths.get(args[4])
        val startDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[5]), LocalTime.MIDNIGHT), ZoneId.of("UTC"))
        val endDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[6]), LocalTime.MIDNIGHT), ZoneId.of("UTC"))

        countFileOccurrencesInPrs(client, projectKey, repoSlug, branchFilterSubstring, outputCsvPath, startDate, endDate);
    }

    private fun countFileOccurrencesInPrs(client: BucketListClient, projectKey: String, repoSlug: String,
                                          branchFilterSubstring: String, outputCsvPath: Path, startDate: ZonedDateTime,
                                          endDate: ZonedDateTime) {

        val fileOccurrences = getFlattenedCountOfFilesFromPrs(client, projectKey, repoSlug, branchFilterSubstring, startDate, endDate)
                .toBlocking()
                .first()

        printToCsv(outputCsvPath, fileOccurrences)
        System.exit(0)
    }

    private fun getFilesChangedInPr(prId: Long, client: BucketListClient, projectKey: String, repoSlug: String):
            Observable<String> {
        logger.debug("Fetching diff for PR " + prId);

        return client.getPrDiff(projectKey, repoSlug, prId, 0, false, false)
                .first()
                .flatMap { diffResponse ->
                    logger.debug("Parsing PR for files changed");
                    Observable.from(diffResponse.diffs)
                            .filter { diff ->
                                diff.source != null && diff.destination != null &&
                                        diff.source!!.fullPath == diff.destination!!.fullPath
                            }
                            .map { diff -> diff.source!!.fullPath }
                }
    }

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

        val multiset: Multiset<String> = HashMultiset.create<String>()

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
