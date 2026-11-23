package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogChromeTest {
    @Test fun a_dialogs_action_row_has_no_fill_of_its_own() {
        assertEquals(DialogChrome.TRANSPARENT, DialogChrome.actionBarFill("dialog"))
        assertEquals(DialogChrome.TRANSPARENT, DialogChrome.actionBarFill("sheet"))
    }

    @Test fun a_page_keeps_its_dark_action_bar() {
        for (p in listOf("", "fullscreen", "horizontal")) assertEquals(DialogChrome.PAGE_ACTION_BAR, DialogChrome.actionBarFill(p))
        assertNotEquals(DialogChrome.TRANSPARENT, DialogChrome.PAGE_ACTION_BAR)
    }

    @Test fun dialogs_and_sheets_are_modal() {
        assertTrue(DialogChrome.isModal("dialog")); assertTrue(DialogChrome.isModal("sheet"))
        assertFalse(DialogChrome.isModal("fullscreen")); assertFalse(DialogChrome.isModal(""))
    }

    @Test fun the_engines_verdict_on_a_password_parses() {
        val ok = RconCommands.parseVerdict("ok:203.0.113.9:26000")!!
        assertEquals("ok", ok.kind); assertEquals("203.0.113.9:26000", ok.server)
        assertEquals("denied", RconCommands.parseVerdict("denied:[2001:db8::1]:26000")!!.kind)
        assertEquals("[2001:db8::1]:26000", RconCommands.parseVerdict("denied:[2001:db8::1]:26000")!!.server)
        assertEquals("timeout", RconCommands.parseVerdict("timeout:h:1")!!.kind)
        assertNull(RconCommands.parseVerdict("")); assertNull(RconCommands.parseVerdict("ok:")); assertNull(RconCommands.parseVerdict("maybe:h:1"))
    }

    @Test fun a_tall_dialog_body_leaves_room_for_the_action_row() {
        // a card with 700 px (1200 px less the keyboard's room) and an 88 dp row at 1.5x: the body gets the rest
        val row = (DialogChrome.ACTION_ROW_DP * 1.5f).toInt()
        assertEquals(700 - row, DialogChrome.bodyBudget(700, row))
        assertEquals("a cramped screen still shows a body", 120, DialogChrome.bodyBudget(150, row))
    }
}
