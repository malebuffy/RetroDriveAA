package com.codeodyssey.retrodriveaa

import android.content.Context
import java.io.File

object SaveStateRepository {
    private const val STATES_DIR = "savestates"
    const val MAX_SLOTS = 5

    data class SaveSlotInfo(
        val slot: Int,
        val stateFile: File,
        val thumbnailFile: File,
        val exists: Boolean,
        val lastModified: Long
    )

    private fun sanitize(value: String): String {
        val cleaned = value.replace("[^A-Za-z0-9._-]".toRegex(), "_")
        return if (cleaned.isBlank()) "state" else cleaned
    }

    private fun fnv1a64(input: String): String {
        var hash = -0x340d631b8c46775fL // 1469598103934665603 as signed Long
        val prime = 1099511628211L
        for (b in input.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xffL)
            hash *= prime
        }
        return java.lang.Long.toUnsignedString(hash, 16)
    }

    private fun statesDir(context: Context): File {
        val dir = File(context.filesDir, STATES_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getStateFile(context: Context, gameId: String): File {
        return getStateFile(context, gameId, 1)
    }

    fun getStateFile(context: Context, gameId: String, slot: Int): File {
        require(slot in 1..MAX_SLOTS) { "slot must be in 1..$MAX_SLOTS" }
        val id = if (gameId.isBlank()) "__browse__" else gameId
        val safe = sanitize(id)
        val digest = fnv1a64(id)
        return File(statesDir(context), "${safe}_${digest}_s${slot}.state")
    }

    fun getThumbnailFile(context: Context, gameId: String, slot: Int): File {
        require(slot in 1..MAX_SLOTS) { "slot must be in 1..$MAX_SLOTS" }
        val id = if (gameId.isBlank()) "__browse__" else gameId
        val safe = sanitize(id)
        val digest = fnv1a64(id)
        return File(statesDir(context), "${safe}_${digest}_s${slot}.png")
    }

    fun getSlots(context: Context, gameId: String): List<SaveSlotInfo> {
        return (1..MAX_SLOTS).map { slot ->
            val stateFile = getStateFile(context, gameId, slot)
            val thumbnailFile = getThumbnailFile(context, gameId, slot)
            val exists = stateFile.exists() && stateFile.length() > 0L
            SaveSlotInfo(
                slot = slot,
                stateFile = stateFile,
                thumbnailFile = thumbnailFile,
                exists = exists,
                lastModified = if (exists) stateFile.lastModified() else 0L
            )
        }
    }

    fun findFirstEmptySlot(context: Context, gameId: String): Int? {
        return getSlots(context, gameId).firstOrNull { !it.exists }?.slot
    }

    fun deleteSlot(context: Context, gameId: String, slot: Int): Boolean {
        require(slot in 1..MAX_SLOTS) { "slot must be in 1..$MAX_SLOTS" }
        val stateFile = getStateFile(context, gameId, slot)
        val thumbnailFile = getThumbnailFile(context, gameId, slot)
        val stateDeleted = !stateFile.exists() || stateFile.delete()
        val thumbDeleted = !thumbnailFile.exists() || thumbnailFile.delete()
        return stateDeleted && thumbDeleted
    }

    fun hasState(context: Context, gameId: String): Boolean {
        return getSlots(context, gameId).any { it.exists }
    }
}
