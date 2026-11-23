package io.github.dixonsolutions.godnotic.fe

/**
 * The surfaces a bridge screen is drawn on. A dialog or sheet is a card of its own: its action row
 * sits on that card's surface (no fill of its own), or it shows as a darker, square-cornered band
 * inset from the card's rounded edge (the "strange bar" behind Server control's Unlock). A
 * full-page screen keeps the dark bar under its actions.
 */
object DialogChrome {
    const val TRANSPARENT = 0
    const val PAGE_ACTION_BAR = 0xF2050B18.toInt()

    fun isModal(presentation: String) = presentation == "dialog" || presentation == "sheet"

    /** The fill behind a screen's bottom action row. */
    fun actionBarFill(presentation: String): Int = if (isModal(presentation)) TRANSPARENT else PAGE_ACTION_BAR

    /** The height an action row needs under a dialog's body (padding and a 56 dp button, with air). */
    const val ACTION_ROW_DP = 88f

    /** The soft fade at the top and bottom of a dialog body that scrolls. */
    const val FADE_DP = 28f

    /**
     * How tall a dialog's scrolling body may be when its card has [availablePx] and the action row needs [reservePx]:
     * what is left, never under 120 px so a cramped screen still shows something.
     */
    fun bodyBudget(availablePx: Int, reservePx: Int): Int = maxOf(120, availablePx - reservePx)
}
