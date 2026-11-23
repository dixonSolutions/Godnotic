package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Test

class ChoiceSelectionTest {
    private val skill = listOf("1", "4", "7", "10")

    @Test fun an_exact_value_is_selected() {
        for (v in skill) assertEquals(v, ChoiceSelection.selected(skill, v))
    }

    @Test fun a_value_between_the_options_takes_the_nearest() {
        assertEquals("1", ChoiceSelection.selected(skill, "2")) // the stock default of the skill cvar
        assertEquals("4", ChoiceSelection.selected(skill, "3"))
        assertEquals("7", ChoiceSelection.selected(skill, "6"))
        assertEquals("10", ChoiceSelection.selected(skill, "9.2"))
        assertEquals("1", ChoiceSelection.selected(skill, "0"))
        assertEquals("10", ChoiceSelection.selected(skill, "11"))
    }

    @Test fun a_tie_takes_the_lower_option() {
        assertEquals("1", ChoiceSelection.selected(listOf("1", "3"), "2"))
    }

    @Test fun a_group_that_is_not_numeric_has_no_nearest() {
        assertEquals("", ChoiceSelection.selected(listOf("", "instagib", "nix"), "rockets"))
        assertEquals("", ChoiceSelection.selected(listOf("a", "b"), "c"))
        assertEquals("instagib", ChoiceSelection.selected(listOf("", "instagib", "nix"), "instagib"))
    }

    @Test fun no_value_selects_nothing_in_a_numeric_group() {
        assertEquals("", ChoiceSelection.selected(skill, ""))
        assertEquals("", ChoiceSelection.selected(listOf("-1", "5"), "abc"))
    }
}
