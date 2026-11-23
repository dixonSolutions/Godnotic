package io.github.dixonsolutions.godnotic.fe

/**
 * The console commands that hand a remembered rcon password to the engine. The password is
 * quoted into a command line, so one that could end the quote or the command (or expand a
 * variable) is neither remembered nor sent: it has to be typed.
 */
object RconCommands {
    private val unsafe = charArrayOf('"', '\\', ';', '\n', '\r', '$')

    /** What the menu decided about a typed password (the engine's `ctl_result`): "ok:host:port", "denied:host:port" or "timeout:host:port". */
    class Verdict(val kind: String, val server: String)

    fun parseVerdict(raw: String): Verdict? {
        val i = raw.indexOf(':')
        if (i <= 0 || i == raw.length - 1) return null
        val kind = raw.substring(0, i)
        return if (kind == "ok" || kind == "denied" || kind == "timeout") Verdict(kind, raw.substring(i + 1)) else null
    }

    fun rememberable(password: String) = password.isNotEmpty() && password.none { it in unsafe }

    fun setPassword(password: String): String {
        require(rememberable(password))
        return "rcon_password \"$password\""
    }

    /** The server whose password was just set, as QuakeC compares it with _touch_join_address. */
    fun setSaved(server: String): String {
        require(rememberable(server))
        return "set _uib_ctl_saved \"$server\""
    }
}
