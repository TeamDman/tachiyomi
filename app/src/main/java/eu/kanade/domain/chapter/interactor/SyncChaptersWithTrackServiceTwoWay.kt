package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.model.Chapter
import eu.kanade.domain.chapter.model.toChapterUpdate
import eu.kanade.domain.track.interactor.InsertTrack
import eu.kanade.domain.track.model.Track
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.tachiyomi.data.preference.PreferenceValues
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.util.system.logcat
import logcat.LogPriority
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max

class SyncChaptersWithTrackServiceTwoWay(
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
) {

    suspend fun await(
        chapters: List<Chapter>,
        remoteTrack: Track,
        service: TrackService,
        behaviour: PreferenceValues.MarkReadBehaviour,
    ) {
        val sortedChapters = chapters.sortedBy { it.chapterNumber }
        val chapterUpdates = sortedChapters
            // Don't modify chapters already marked read
            .asSequence()
            .filter { !it.read }
            // Only modify chapters earlier than what remote says is latest read
            .filter { it.chapterNumber <= remoteTrack.lastChapterRead }
            // Don't modify if preference set to never
            .filter { behaviour != PreferenceValues.MarkReadBehaviour.NEVER }
            // If preferences set to not mark "special" chapters as read, verify is whole number ch
            .filter { behaviour != PreferenceValues.MarkReadBehaviour.NOT_SPECIAL || it.chapterNumber % 1.0 == 0.0 }
            // Update chapter as read
            .map { it.copy(read = true).toChapterUpdate() }
            .toList()

        // only take into account continuous reading
        var localLastRead = sortedChapters.takeWhile { it.read }.lastOrNull()?.chapterNumber?.toDouble() ?: 0.0
        // prevent backtracking
        localLastRead = max(remoteTrack.lastChapterRead, localLastRead)

        val updatedTrack = remoteTrack.copy(lastChapterRead = localLastRead)

        try {
            service.update(updatedTrack.toDbTrack())
            updateChapter.awaitAll(chapterUpdates)
            insertTrack.await(updatedTrack)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e)
        }
    }
}
