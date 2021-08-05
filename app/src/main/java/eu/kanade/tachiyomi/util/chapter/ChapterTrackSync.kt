package eu.kanade.tachiyomi.util.chapter

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Track
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
fun syncChaptersWithTrackServiceTwoWay(db: DatabaseHelper, chapters: List<Chapter>, remoteTrack: Track, service: TrackService) {
    // mark local chapters read based on remote
    chapters
            .filter { !it.read }
            .filter { it.chapter_number < remoteTrack.last_chapter_read }
            .forEach{ it.read = true }
    db.updateChaptersProgress(chapters).executeAsBlocking()

    // find last read local chapter number
    // default to existing tracker value
    val lastReadLocalChapterNumber = chapters
            .filter { it.read }
            .maxByOrNull { it.chapter_number }
            ?.chapter_number?.toInt()
            ?:remoteTrack.last_chapter_read

    // update tracker, no backtracking allowed
    remoteTrack.last_chapter_read = max(remoteTrack.last_chapter_read, lastReadLocalChapterNumber)

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
