package org.isdm.companion.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/** Fetches independent content with bounded concurrency; never owns account or UI state. */
internal class LmsContentLoader(
    private val gateway: LmsGateway,
    private val facultyDirectory: FacultyDirectory?,
) {
    suspend fun load(
        onAssessments: suspend (ContentFetch<List<AssessmentItem>>) -> Unit,
    ): ContentFetch<List<CourseContent>> = coroutineScope {
        gateway.invalidateContentCache()
        launch { onAssessments(fetch { gateway.assessments() }) }
        fetch {
            val courses = gateway.courses()
            val permits = Semaphore(3)
            courses.map { course ->
                async {
                    permits.withPermit {
                        CourseContent(
                            readings = fetch { gateway.readings(course) },
                            facultyProfiles = facultyDirectory?.let { directory ->
                                fetch { directory.facultyProfiles(course) }
                            },
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun <T> fetch(block: suspend () -> T): ContentFetch<T> {
        repeat(2) { attempt ->
            try {
                return ContentFetch(value = block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val networkFailure = generateSequence(error) { it.cause }.any { it is IOException }
                if (attempt == 1 || !networkFailure || error is AuthenticationFailure) {
                    return ContentFetch(error = error)
                }
            }
        }
        error("Unreachable fetch attempt")
    }
}

internal data class ContentFetch<T>(val value: T? = null, val error: Throwable? = null)

internal data class CourseContent(
    val readings: ContentFetch<List<ReadingItem>>,
    val facultyProfiles: ContentFetch<List<FacultyProfile>>?,
)
