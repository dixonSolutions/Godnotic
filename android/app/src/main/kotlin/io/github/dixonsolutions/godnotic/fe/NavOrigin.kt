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
}
