package io.aexp.bucketlist.examples.prlifetime

import com.opencsv.CSVWriter
import io.aexp.bucketlist.BucketListClient
import io.aexp.bucketlist.data.Order
import io.aexp.bucketlist.data.PullRequestState
import io.aexp.bucketlist.examples.getBitBucketClient
import rx.Observable
import rx.schedulers.Schedulers
import java.io.FileWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.ArrayList
import java.util.concurrent.TimeUnit


object ExportRawPrLifetimeData {

    private const val ARGUMENTS_COUNT = 6

    @JvmStatic fun main(args: Array<String>) {
        if (args.size != ExportRawPrLifetimeData.ARGUMENTS_COUNT) {
            System.err!!.println(String.format("Must have %d arguments: <config file> <project key> <repo slug> <output file> <start date> <end date>", ExportRawPrLifetimeData.ARGUMENTS_COUNT))
            System.exit(1)
        }

        val configPath = Paths.get(args[0])

        val client = getBitBucketClient(configPath)
        val projectKey = args[1]
        val repoSlug = args[2]
        val outputCsvPath = Paths.get(args[3])

        val startDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[4]), LocalTime.MIDNIGHT), ZoneId.of("UTC"))
        val endDate = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(args[5]), LocalTime.MIDNIGHT), ZoneId.of("UTC"))

        val prSummaries = getPrSummaries(client, projectKey, repoSlug, startDate, endDate)
                .toSortedList({ prSummary1, prSummary2 -> prSummary1.pr.createdAt.compareTo(prSummary2.pr.createdAt) })
                .toBlocking()
                .first()

        printBetterCsv(outputCsvPath, prSummaries)
        System.exit(0)
    }

    private fun getPrSummaries(client: BucketListClient, projKey: String, repoSlug: String, startDate: ZonedDateTime,
                               endDate: ZonedDateTime) : Observable<PrSummary> {
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
    }

    private fun printBetterCsv(outputCsvPath: Path, prSummaries: List<PrSummary>) {
        val outputCsvFile = outputCsvPath.toFile()
        CSVWriter(FileWriter(outputCsvFile)).use { writer ->
            val header = ArrayList<String>()
            header.add("Created At")
            header.add("Duration Since Start (hours)")
            header.add("Duration Since Last Push (hours)")
            writer.writeNext(header.toArray(arrayOfNulls<String>(0)))

            for (prSummary in prSummaries) {
                val row = ArrayList<String>()
                val pr = prSummary.pr
                row.add(pr.createdAt.toString())

                val hoursSinceStart = getFractionalHoursFromDuration(prSummary.durationSinceStart)
                row.add(hoursSinceStart.toString())

                val hoursSinceLastPush = getFractionalHoursFromDuration(prSummary.durationSinceLastPRCommitPushed)
                row.add(hoursSinceLastPush.toString())

                writer.writeNext(row.toArray(arrayOfNulls<String>(0)))
            }
        }
    }

    private fun getFractionalHoursFromDuration(duration: Duration) : Double {
        return duration.toMillis().toDouble() / TimeUnit.HOURS.toMillis(1)
    }
}