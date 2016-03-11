package io.aexp.bucketlist.data

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The diff for a single file within a pull request
 * @param source the path for the file prior to the changes. Can be Null if the file was created as part of this diff
 * @param destination the path for the file after the changes. Can be Null if the file was deleted as part of this diff
 * @param hunks list of changes made within this file, broken up into hunks based on line proximity grouping. Null if no
 *              code was changed as part of this diff (e.g. file renamed without modification)
 * @param truncated boolean indicating if response size limits resulted in the hunks in this diff getting truncated
 */
class PullRequestDiff(@JsonProperty("source") val source: PullRequestFilePath?,
                      @JsonProperty("destination") val destination: PullRequestFilePath?,
                      @JsonProperty("hunks") val hunks: List<PullRequestHunk>?,
                      @JsonProperty("truncated") val truncated: Boolean)