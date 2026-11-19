// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.dixonsolutions.godnotic.fe

/**
 * Where cancelling a game or a join returns to.
 *
 * The native front end has Home and the pause page; Local games, Campaign and the
 * server browser are the menu's own screens (touch_uib_screens.qc), which record
 * their stack for the same reason. This is the one mapping both sides use, so a
 * game started from a screen comes back to it instead of Home.
 */
object NavOrigin {
    const val HOME = "home"
    const val LOCAL = "local"
    const val CAMPAIGN = "campaign"
    const val SERVERS = "servers"

    /** A menu screen id (fe or UIB) to the screen a game started from. */
    fun fromScreen(id: String?): String? = when (id) {
        "sp", "sp.map", LOCAL -> LOCAL
        CAMPAIGN -> CAMPAIGN
        "mp", SERVERS -> SERVERS
        else -> null
    }

    /** Leaving a match, or cancelling a join: back to where it started; Home when there is no origin (a deep link). */
    fun returnScreen(origin: String?): String = fromScreen(origin) ?: HOME

    fun leave(origin: String?): String = returnScreen(origin)

    fun cancelJoin(origin: String?): String = returnScreen(origin)

    /**
     * Only leaving a real match (context "game") spends the remembered origin. A join passes
     * through "loading" and, for a moment, "menu" before the match is up; consuming the origin
     * there lost it, so leaving the match fell back Home. QuakeC's UIB_Context applies this rule.
     */
    fun consumesOrigin(prevContext: String): Boolean = prevContext == "game"

    /** Back to where the player was: the origin's stack when it still describes a screen. */
    fun returnStack(origin: String?): String = origin?.takeIf { returnValid(it) } ?: HOME

    /** Mirror of UIB_ReturnValid: a stack that starts at Home and ends at a screen a game starts from. */
    fun returnValid(stack: String): Boolean {
        val ids = stack.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return ids.isNotEmpty() && ids[0] == HOME && ids.last() in setOf(HOME, "sp", "sp.map", CAMPAIGN, "mp", "credits")
    }
}
