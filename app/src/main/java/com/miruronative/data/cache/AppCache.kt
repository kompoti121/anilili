package com.miruronative.data.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

@Entity(
    tableName = "cache_entries",
    indices = [Index("expiresAt"), Index("lastAccessedAt")],
)
data class CacheEntry(
    @PrimaryKey val key: String,
    val payload: String,
    val createdAt: Long,
    val expiresAt: Long,
    val lastAccessedAt: Long,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache_entries WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): CacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CacheEntry)

    @Query("UPDATE cache_entries SET lastAccessedAt = :now WHERE `key` = :key")
    suspend fun touch(key: String, now: Long)

    @Query("DELETE FROM cache_entries WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM cache_entries WHERE expiresAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM cache_entries")
    suspend fun count(): Int

    @Query(
        "DELETE FROM cache_entries WHERE `key` IN " +
            "(SELECT `key` FROM cache_entries ORDER BY lastAccessedAt ASC LIMIT :count)",
    )
    suspend fun deleteLeastRecentlyUsed(count: Int)
}

@Database(entities = [CacheEntry::class], version = 1, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}

/**
 * Bounded two-level cache used for public metadata and episode catalogs.
 *
 * Fresh entries return immediately. Expired-but-usable entries implement stale-while-revalidate:
 * the caller gets the last successful value while one coalesced refresh happens in the background.
 * Stream URLs are deliberately not stored here because provider signatures often expire quickly.
 */
class AppCache(
    context: Context,
    private val json: Json,
) {
    private val dao = Room.databaseBuilder(
        context.applicationContext,
        CacheDatabase::class.java,
        "anilili-cache.db",
    ).fallbackToDestructiveMigration(true).build().cacheDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyLocks = Array(64) { Mutex() }
    private val memory = object : LinkedHashMap<String, CacheEntry>(MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MEMORY_ENTRIES
    }

    suspend fun <T> getOrFetch(
        key: String,
        serializer: KSerializer<T>,
        ttlMs: Long,
        staleForMs: Long = DEFAULT_STALE_MS,
        forceRefresh: Boolean = false,
        fetch: suspend () -> T,
    ): T {
        val now = System.currentTimeMillis()
        val cached = read(key)
        val decoded = cached?.let { decode(it, serializer) }
        if (!forceRefresh && cached != null && decoded != null && now <= cached.expiresAt) {
            touch(cached, now)
            return decoded
        }
        if (!forceRefresh && cached != null && decoded != null && now <= cached.expiresAt + staleForMs) {
            touch(cached, now)
            scope.launch {
                try {
                    refresh(key, serializer, ttlMs, false, fetch)
                } catch (_: Exception) {
                    // The stale value remains available until its safety window closes.
                }
            }
            return decoded
        }
        return refresh(key, serializer, ttlMs, forceRefresh, fetch)
    }

    private suspend fun <T> refresh(
        key: String,
        serializer: KSerializer<T>,
        ttlMs: Long,
        forceRefresh: Boolean,
        fetch: suspend () -> T,
    ): T {
        val lock = keyLocks[(key.hashCode() and Int.MAX_VALUE) % keyLocks.size]
        return lock.withLock {
                val now = System.currentTimeMillis()
                val newer = read(key)
                if (!forceRefresh && newer != null && now <= newer.expiresAt) {
                    decode(newer, serializer)?.let { return@withLock it }
                }

                val value = fetch()
                val entry = CacheEntry(
                    key = key,
                    payload = json.encodeToString(serializer, value),
                    createdAt = now,
                    expiresAt = now + ttlMs,
                    lastAccessedAt = now,
                )
                dao.put(entry)
                synchronized(memory) { memory[key] = entry }
                prune(now)
                value
        }
    }

    private suspend fun read(key: String): CacheEntry? {
        synchronized(memory) { memory[key] }?.let { return it }
        return dao.get(key)?.also { entry ->
            synchronized(memory) { memory[key] = entry }
        }
    }

    private suspend fun <T> decode(entry: CacheEntry, serializer: KSerializer<T>): T? =
        try {
            json.decodeFromString(serializer, entry.payload)
        } catch (_: Exception) {
            dao.delete(entry.key)
            synchronized(memory) { memory.remove(entry.key) }
            null
        }

    private fun touch(entry: CacheEntry, now: Long) {
        val touched = entry.copy(lastAccessedAt = now)
        synchronized(memory) { memory[entry.key] = touched }
        scope.launch { dao.touch(entry.key, now) }
    }

    private suspend fun prune(now: Long) {
        dao.deleteOlderThan(now - DISK_STALE_RETENTION_MS)
        val overflow = dao.count() - DISK_ENTRIES
        if (overflow > 0) dao.deleteLeastRecentlyUsed(overflow)
    }

    private companion object {
        const val MEMORY_ENTRIES = 80
        const val DISK_ENTRIES = 500
        const val DEFAULT_STALE_MS = 7L * 24 * 60 * 60 * 1000
        const val DISK_STALE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
