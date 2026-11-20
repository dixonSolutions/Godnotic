package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePathMemoTest {
    @Test fun anAnswerSurvivesASaveAndALoad() {
        val m = ImagePathMemo()
        assertTrue(m.put("pk3:maps/bromine", "/data/uib-cache/ab12.png"))
        assertTrue(m.put("pk3:maps/xoylent", "/data/uib-cache/cd34.png"))
        val back = ImagePathMemo.parse(m.serialize())
        assertEquals("/data/uib-cache/ab12.png", back.get("pk3:maps/bromine"))
        assertEquals("/data/uib-cache/cd34.png", back.get("pk3:maps/xoylent"))
        assertNull(back.get("pk3:maps/other"))
    }

    @Test fun theSameAnswerIsNotASecondSave() {
        val m = ImagePathMemo()
        assertTrue(m.put("a", "/p/1"))
        assertFalse(m.put("a", "/p/1"))
        assertTrue(m.put("a", "/p/2"))
        assertEquals("/p/2", m.get("a"))
    }

    @Test fun theOldestAnswersGoFirst() {
        val m = ImagePathMemo(max = 3)
        for (i in 1..5) m.put("r$i", "/p/$i")
        assertEquals(3, m.size)
        assertNull(m.get("r1")); assertNull(m.get("r2"))
        assertEquals("/p/5", m.get("r5"))
    }

    @Test fun junkLinesAndEmptyAnswersAreIgnored() {
        val back = ImagePathMemo.parse("no tab here\n\t/p\nref\t\nok\t/p/ok\n")
        assertEquals(1, back.size)
        assertEquals("/p/ok", back.get("ok"))
        assertFalse(ImagePathMemo().put("missing", ""))
    }
}
