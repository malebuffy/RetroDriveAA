package com.codeodyssey.retrodriveaa

import android.content.Context
import java.io.File

object DosWorkingDirectoryResolver {
    private val ignoredNames = setOf("__MACOSX", "THUMBS.DB", "DESKTOP.INI")

    private data class ShortNameEntry(
        val originalName: String,
        val shortName: String,
        val shortNumber: Int
    )

    @JvmStatic
    fun resolveStartupDirectory(context: Context, gameFolder: String?): String? {
        val selectedFolder = gameFolder?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val gameRoot = File(context.getExternalFilesDir(null), "game")
        return toDosVisibleName(gameRoot, selectedFolder)
    }

    @JvmStatic
    fun quoteForDos(path: String): String {
        return path.replace("\"", "").let { "\"$it\"" }
    }

    private fun toDosVisibleName(parentDirectory: File, childName: String): String {
        val entries = parentDirectory.listFiles().orEmpty()
        val generatedEntries = mutableListOf<ShortNameEntry>()

        entries.forEach { entry ->
            generatedEntries += createShortNameEntry(entry.name, generatedEntries)
        }

        return generatedEntries.firstOrNull { it.originalName == childName }?.shortName
            ?: createShortNameEntry(childName, generatedEntries).shortName
    }

    private fun createShortNameEntry(
        originalName: String,
        existingEntries: List<ShortNameEntry>
    ): ShortNameEntry {
        var workingName = originalName.uppercase()
        var createShort = false

        val removedSpaces = removeSpaces(workingName)
        workingName = removedSpaces.first
        createShort = removedSpaces.second

        var firstDot = workingName.indexOf('.')
        if (firstDot >= 0) {
            if (workingName.length - firstDot > 4) {
                workingName = workingName.trimStart('.')
                createShort = true
            }
            firstDot = workingName.indexOf('.')
        }

        val baseLength = if (firstDot >= 0) firstDot else workingName.length
        createShort = createShort || baseLength > 8
        if (!createShort) {
            createShort = existingEntries.any { it.shortName == workingName }
        }

        if (!createShort) {
            return ShortNameEntry(
                originalName = originalName,
                shortName = removeTrailingDot(workingName),
                shortNumber = 0
            )
        }

        val shortNumber = createShortNameId(existingEntries, workingName)
        val numberText = shortNumber.toString()
        val prefixLength = if (baseLength + numberText.length + 1 > 8) {
            8 - numberText.length - 1
        } else {
            baseLength
        }.coerceAtLeast(0)

        val builder = StringBuilder()
        builder.append(workingName.take(prefixLength))
        builder.append('~')
        builder.append(numberText)

        val lastDot = workingName.lastIndexOf('.')
        if (lastDot >= 0) {
            val extension = workingName.substring(lastDot)
            builder.append(extension.take(4))
        }

        return ShortNameEntry(
            originalName = originalName,
            shortName = removeTrailingDot(builder.toString()),
            shortNumber = shortNumber
        )
    }

    private fun createShortNameId(existingEntries: List<ShortNameEntry>, compareName: String): Int {
        var highestNumber = 0
        existingEntries.forEach { entry ->
            if (entry.shortNumber > 0 && compareShortName(compareName, entry.shortName) == 0) {
                highestNumber = maxOf(highestNumber, entry.shortNumber)
            }
        }
        return highestNumber + 1
    }

    private fun compareShortName(compareName: String, shortName: String): Int {
        val tildeIndex = shortName.indexOf('~')
        if (tildeIndex < 0) {
            return compareName.compareTo(shortName)
        }

        var compareCount1 = tildeIndex
        val numberSize = shortName.substring(tildeIndex).indexOf('.').let { index ->
            if (index >= 0) index else shortName.length - tildeIndex
        }
        var compareCount2 = compareName.indexOf('.').let { index ->
            if (index >= 0) index else compareName.length
        }
        if (compareCount2 > 8) {
            compareCount2 = 8
        }
        if (compareCount2 > compareCount1 + numberSize) {
            compareCount1 = compareCount2 - numberSize
        }

        for (index in 0 until compareCount1) {
            val left = compareName.getOrElse(index) { '\u0000' }
            val right = shortName.getOrElse(index) { '\u0000' }
            if (left != right) {
                return left.compareTo(right)
            }
        }
        return 0
    }

    private fun removeSpaces(value: String): Pair<String, Boolean> {
        val withoutSpaces = buildString(value.length) {
            value.forEach { character ->
                if (character != ' ') {
                    append(character)
                }
            }
        }
        return withoutSpaces to (withoutSpaces.length != value.length)
    }

    private fun removeTrailingDot(value: String): String {
        if (!value.endsWith('.')) {
            return value
        }
        if (value.length == 1) {
            return value
        }
        if (value.length == 2 && value[0] == '.') {
            return value
        }
        return value.dropLast(1)
    }

    private fun isIgnorableEntry(file: File): Boolean {
        if (file.name.startsWith('.')) {
            return true
        }

        return file.name.uppercase() in ignoredNames
    }
}