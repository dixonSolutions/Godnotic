package io.github.dixonsolutions.godnotic.fe

/**
 * The console commands that hand a remembered rcon password to the engine. The password is
 * quoted into a command line, so one that could end the quote or the command (or expand a
 * variable) is neither remembered nor sent: it has to be typed.
 */
object RconCommands {
    private val unsafe = charArrayOf('"', '\\', ';', '\n', '\r', '$')

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
