package kr.parkingpin.app.domain.photo

/**
 * An in-memory [ParkingPhotoStore] with the same naming rule as the real one, so a test
 * about *which* files survive a delete reads the same as the production behaviour.
 */
class FakeParkingPhotoStore : ParkingPhotoStore {

    val stored: MutableSet<String> = linkedSetOf()

    var saveCount: Int = 0
        private set

    /** Set to make the next save fail, the way a full disk or a broken file would. */
    var failWith: PhotoSaveResult.Failed.Reason? = null

    override suspend fun save(recordId: String, source: PhotoSource): PhotoSaveResult {
        saveCount++
        failWith?.let { return PhotoSaveResult.Failed(it) }
        val path = pathFor(recordId)
        stored += path
        return PhotoSaveResult.Saved(path)
    }

    override suspend fun delete(relativePath: String?) {
        stored -= relativePath ?: return
    }

    override suspend fun retainOnly(relativePaths: Set<String>) {
        stored.retainAll(relativePaths)
    }

    companion object {
        fun pathFor(recordId: String): String = "parking-photos/$recordId.jpg"
    }
}
