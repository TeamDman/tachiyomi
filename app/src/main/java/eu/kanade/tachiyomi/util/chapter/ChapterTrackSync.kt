package eu.kanade.tachiyomi.util.chapter

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.preference.PreferenceValues.MarkReadBehaviour
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.util.lang.launchIO
import timber.log.Timber
import java.lang.Float.max

/**
 * Helper method for syncing a remote track with the local chapters, and back
 *
 * @param db the database.
 * @param chapters a list of chapters from the source.
 * @param remoteTrack the remote Track object.
 * @param service the tracker service.
 */
fun syncChaptersWithTrackServiceTwoWay(db: DatabaseHelper, chapters: List<Chapter>, remoteTrack: Track, service: TrackService, behaviour: MarkReadBehaviour) {
    val sortedChapters = chapters.sortedBy { it.chapter_number }
    sortedChapters
            // Don't modify chapters already marked read
            .filter { !it.read }
            // Only modify chapters earlier than what remote says is latest read
            .filter { it.chapter_number <= remoteTrack.last_chapter_read }
            // Don't modify if preference set to never
            .filter { behaviour != MarkReadBehaviour.NEVER }
            // If preferences set to not mark "special" chapters as read, verify is whole number ch
            .filter { behaviour != MarkReadBehaviour.NOT_SPECIAL || it.chapter_number % 1.0 == 0.0 }
            // Update chapter as read
            .forEach { it.read = true }

    // commit local update to db
    db.updateChaptersProgress(sortedChapters).executeAsBlocking()

    // only take into account continuous reading
    val latestReadChapterNumber = sortedChapters
            .takeWhile { it.read }
            .lastOrNull()?.chapter_number
            ?: 0F

    // update tracker, no backtracking allowed
    remoteTrack.last_chapter_read = max(remoteTrack.last_chapter_read, latestReadChapterNumber)

    // publish changes
    launchIO {
        try {
            service.update(remoteTrack)
            db.insertTrack(remoteTrack).executeAsBlocking()
        } catch (e: Throwable) {
            Timber.w(e)
        }
    }
}
