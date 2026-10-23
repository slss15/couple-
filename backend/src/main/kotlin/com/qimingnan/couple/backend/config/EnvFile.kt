package com.qimingnan.couple.backend.config

/**
 * Minimal dotenv parser shared in spirit with install.ps1 and the updater: `KEY=VALUE` per line, `#` comments,
 * optional `export ` prefix, optional matching single or double quotes around the value. No interpolation.
 */
object EnvFile {
    private val KEY = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("export ")) line = line.removePrefix("export ").trimStart()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (!KEY.matches(key)) continue
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && (value[0] == '"' || value[0] == '\'') && value.last() == value[0]) {
                value = value.substring(1, value.length - 1)
            }
            out[key] = value
        }
        return out
    }
}
