package io.aexp.bucketlist.examples.prlifetime

import io.aexp.bucketlist.data.PullRequest
import io.aexp.bucketlist.data.PullRequestActivity
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters


data class PrSummary(val pr: PullRequest, val activity: List<PullRequestActivity>) {
    val durationSinceStart: Duration
        get() = Duration.between(activity.first().createdAt, activity.last().createdAt)

    val durationSinceLastPRCommitPushed: Duration
        get() {
            var mergeTime = activity.filter({ event -> event.action == "MERGED" }).last().createdAt
            var lastCommitTime = activity.filter({ event -> event.action == "RESCOPED" || event.action == "OPENED" }).last().createdAt

            return Duration.between(lastCommitTime, mergeTime)
        }

    val mondayOfWeekOfStart: LocalDate
        get() = pr.createdAt.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}