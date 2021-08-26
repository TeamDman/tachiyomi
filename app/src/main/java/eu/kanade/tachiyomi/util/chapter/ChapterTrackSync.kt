package eu.kanade.tachiyomi.util.chapter

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.preference.PreferenceValues
import eu.kanade.tachiyomi.data.preference.PreferenceValues.MarkReadBehaviour.*
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.util.lang.launchIO
import timber.log.Timber
import kotlin.math.max

/**
 * Helper method for syncing a remote track with the local chapters, and back
 *
 * @param db the database.
 * @param chapters a list of chapters from the source.
 * @param remoteTrack the remote Track object.
 * @param service the tracker service.
 */
fun syncChaptersWithTrackServiceTwoWay(db: DatabaseHelper, chapters: List<Chapter>, remoteTrack: Track, service: TrackService, behaviour: PreferenceValues.MarkReadBehaviour) {
    // mark local chapters read based on remote
    if (behaviour != NEVER) {
        chapters
                .filter { !it.read }
                .filter { it.chapter_number <= remoteTrack.last_chapter_read }
                .filter {
                    if (behaviour == NOT_SPECIAL) { // only mark read if whole-numbered chapter
                        it.chapter_number % 1.0 == 0.0
                    } else { // always mark read
                        true
                    }
                }
                .forEach { it.read = true }
        db.updateChaptersProgress(chapters).executeAsBlocking()
    }

    // find the first unread chapter
    val nextUnreadChapterIndex = chapters
            .sortedBy { it.chapter_number }
            .indexOfFirst { !it.read }

    // the chapter before the unread one is considered the latest locally read chapter
    // fallback to the remote tracker value
    val latestReadChapterNumber = chapters.getOrNull(nextUnreadChapterIndex - 1)
            ?.chapter_number?.toInt()
            ?: remoteTrack.last_chapter_read

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
