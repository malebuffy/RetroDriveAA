package com.codeodyssey.retrodriveaa

import android.content.Context
import java.io.File

object ConfigManager {
    private const val BASE_CONFIG_ASSET = "dosbox_base.conf"
    private const val SESSION_CONFIG_FILE = "session.conf"
    private const val AUTOEXEC_PREFIX = "__line__"

    private val fallbackSchema: LinkedHashMap<String, LinkedHashMap<String, String>> = linkedMapOf(
        "sdl" to linkedMapOf(
            "fullscreen" to "false",
            "fulldouble" to "false",
            "fullresolution" to "original",
            "windowresolution" to "original",
            "output" to "texture",
            "autolock" to "true",
            "sensitivity" to "100",
            "waitonerror" to "true",
            "priority" to "higher,normal",
            "mapperfile" to "mapper-0.74.map",
            "usescancodes" to "false"
        ),
        "dosbox" to linkedMapOf(
            "language" to "",
            "machine" to "svga_s3",
            "captures" to "capture",
            "memsize" to "16"
        ),
        "render" to linkedMapOf(
            "frameskip" to "0",
            "aspect" to "false",
            "scaler" to "none"
        ),
        "cpu" to linkedMapOf(
            "core" to "auto",
            "cputype" to "auto",
            "cycles" to "auto",
            "cycleup" to "10",
            "cycledown" to "20"
        ),
        "mixer" to linkedMapOf(
            "nosound" to "false",
            "rate" to "44100",
            "blocksize" to "512",
            "prebuffer" to "20"
        ),
        "midi" to linkedMapOf(
            "mpu401" to "intelligent",
            "mididevice" to "default",
            "midiconfig" to ""
        ),
        "sblaster" to linkedMapOf(
            "sbtype" to "sb16",
            "sbbase" to "220",
            "irq" to "7",
            "dma" to "1",
            "hdma" to "5",
            "sbmixer" to "true",
            "oplmode" to "auto",
            "oplemu" to "default",
            "oplrate" to "44100"
        ),
        "gus" to linkedMapOf(
            "gus" to "false",
            "gusrate" to "44100",
            "gusbase" to "240",
            "gusirq" to "5",
            "gusdma" to "3",
            "ultradir" to "C:\\ULTRASND"
        ),
        "speaker" to linkedMapOf(
            "pcspeaker" to "true",
            "pcrate" to "44100",
            "tandy" to "auto",
            "tandyrate" to "44100",
            "disney" to "true"
        ),
        "joystick" to linkedMapOf(
            "joysticktype" to "auto",
            "timed" to "true",
            "autofire" to "false",
            "swap34" to "false",
            "buttonwrap" to "false"
        ),
        "serial" to linkedMapOf(
            "serial1" to "dummy",
            "serial2" to "dummy",
            "serial3" to "disabled",
            "serial4" to "disabled"
        ),
        "dos" to linkedMapOf(
            "xms" to "true",
            "ems" to "true",
            "umb" to "true",
            "keyboardlayout" to "auto"
        ),
        "ipx" to linkedMapOf(
            "ipx" to "false"
        )
    )

    fun getSchema(): LinkedHashMap<String, LinkedHashMap<String, String>> = deepCopy(fallbackSchema)

    fun getMasterDefaults(context: Context): Map<String, String> {
        val baseTemplate = loadBaseTemplate(context)
        val parsed = parseConfig(baseTemplate)
        val merged = deepCopy(fallbackSchema)

        parsed.forEach { (section, values) ->
            val targetSection = merged.getOrPut(section) { linkedMapOf() }
            values.forEach { (key, value) ->
                if (!key.startsWith(AUTOEXEC_PREFIX)) {
                    targetSection[key] = value
                }
            }
        }

        return flatten(merged)
    }

    fun generateSessionConfig(context: Context, game: GameProfile): String {
        val baseTemplate = loadBaseTemplate(context)
        val parsed = parseConfig(baseTemplate)
        val merged = deepCopy(fallbackSchema)

        parsed.forEach { (section, values) ->
            val targetSection = merged.getOrPut(section) { linkedMapOf() }
            values.forEach { (key, value) ->
                if (!key.startsWith(AUTOEXEC_PREFIX)) {
                    targetSection[key] = value
                }
            }
        }

        game.configOverrides.forEach { (compoundKey, value) ->
            val sectionName = compoundKey.substringBefore('.', missingDelimiterValue = "")
            val keyName = compoundKey.substringAfter('.', missingDelimiterValue = "")
            if (sectionName.isBlank() || keyName.isBlank()) return@forEach
            val section = merged.getOrPut(sectionName) { linkedMapOf() }
            section[keyName] = value
        }

        val gamePath = File(context.getExternalFilesDir(null), "game").absolutePath
        val startupDirectory = DosWorkingDirectoryResolver.resolveStartupDirectory(context, game.gameId)
        val autoexec = linkedMapOf<String, String>()
        autoexec["${AUTOEXEC_PREFIX}1"] = "mount c \"$gamePath\""
        autoexec["${AUTOEXEC_PREFIX}2"] = "c:"
        autoexec["${AUTOEXEC_PREFIX}3"] = "cd \\"
        if (!startupDirectory.isNullOrBlank()) {
            autoexec["${AUTOEXEC_PREFIX}4"] = buildDosChangeDirectoryCommand(startupDirectory)
        }
        merged["autoexec"] = autoexec

        val configText = buildConfigText(merged)
        val sessionFile = File(context.cacheDir, SESSION_CONFIG_FILE)
        sessionFile.writeText(configText)
        return sessionFile.absolutePath
    }

    private fun loadBaseTemplate(context: Context): String {
        return try {
            context.assets.open(BASE_CONFIG_ASSET).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            buildConfigText(fallbackSchema)
        }
    }

    private fun parseConfig(text: String): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val result = linkedMapOf<String, LinkedHashMap<String, String>>()
        var currentSection: String? = null
        var autoexecIndex = 0

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim().lowercase()
                result.putIfAbsent(currentSection!!, linkedMapOf())
                autoexecIndex = 0
                return@forEach
            }

            val section = currentSection ?: return@forEach
            val sectionMap = result.getOrPut(section) { linkedMapOf() }

            val eq = line.indexOf('=')
            if (eq > 0) {
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                sectionMap[key] = value
            } else if (section == "autoexec") {
                autoexecIndex += 1
                sectionMap["$AUTOEXEC_PREFIX$autoexecIndex"] = line
            }
        }

        return result
    }

    private fun buildConfigText(config: LinkedHashMap<String, LinkedHashMap<String, String>>): String {
        val sb = StringBuilder()
        config.forEach { (section, values) ->
            sb.append("[").append(section).append("]\n")
            if (section == "autoexec") {
                values.forEach { (key, value) ->
                    if (key.startsWith(AUTOEXEC_PREFIX)) {
                        sb.append(value).append("\n")
                    }
                }
            } else {
                values.forEach { (key, value) ->
                    if (!key.startsWith(AUTOEXEC_PREFIX)) {
                        sb.append(key).append("=").append(value).append("\n")
                    }
                }
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun buildDosChangeDirectoryCommand(directory: String): String {
        val normalized = directory.trim().trimStart('\\')
        return "cd \\" + normalized
    }

    private fun flatten(config: LinkedHashMap<String, LinkedHashMap<String, String>>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        config.forEach { (section, values) ->
            values.forEach { (key, value) ->
                if (!key.startsWith(AUTOEXEC_PREFIX)) {
                    result["$section.$key"] = value
                }
            }
        }
        return result
    }

    private fun deepCopy(source: LinkedHashMap<String, LinkedHashMap<String, String>>): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val copy = linkedMapOf<String, LinkedHashMap<String, String>>()
        source.forEach { (section, values) ->
            copy[section] = LinkedHashMap(values)
        }
        return copy
    }
}
