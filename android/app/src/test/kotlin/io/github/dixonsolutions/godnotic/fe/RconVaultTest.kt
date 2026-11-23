package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class RconVaultTest {
    private class Mem : RconVault.Blobs {
        val m = LinkedHashMap<String, String>()
        override fun get(name: String) = m[name]
        override fun put(name: String, value: String) { m[name] = value }
        override fun remove(name: String) { m.remove(name) }
    }

    private fun vault(mem: Mem = Mem(), key: javax.crypto.SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) =
        RconVault(mem, { key })

    @Test fun a_saved_password_comes_back_for_its_server_only() {
        val v = vault()
        v.save("203.0.113.9:26000", "s3cret pass")
        assertEquals("s3cret pass", v.load("203.0.113.9:26000"))
        assertEquals("the default port is implied", "s3cret pass", v.load("203.0.113.9"))
        assertNull(v.load("203.0.113.10:26000"))
    }

    @Test fun the_stored_text_is_not_the_password() {
        val mem = Mem()
        vault(mem).save("host:26000", "hunter2hunter2")
        assertTrue(mem.m.isNotEmpty())
        for ((k, value) in mem.m) {
            assertFalse(k.contains("hunter2")); assertFalse(value.contains("hunter2"))
        }
    }

    @Test fun an_entry_moved_to_another_server_does_not_open() {
        val mem = Mem()
        val v = vault(mem)
        v.save("a:26000", "pw-for-a")
        mem.m["rcon:b:26000"] = mem.m["rcon:a:26000"]!!
        assertNull(v.load("b:26000"))
        assertFalse("a bad entry is dropped", mem.m.containsKey("rcon:b:26000"))
    }

    @Test fun a_lost_key_or_damaged_entry_is_forgotten_not_thrown() {
        val mem = Mem()
        vault(mem).save("a:26000", "pw")
        assertNull(vault(mem).load("a:26000")) // another key, as after a reinstall
        mem.m["rcon:z:26000"] = "zz"
        assertNull(vault(mem).load("z:26000"))
    }

    @Test fun forget_deletes_the_entry() {
        val mem = Mem()
        val v = vault(mem)
        v.save("a:26000", "pw")
        v.forget("A:26000")
        assertNull(v.load("a:26000"))
        assertTrue(mem.m.isEmpty())
    }

    @Test fun nothing_is_stored_for_an_empty_server_or_password() {
        val mem = Mem()
        val v = vault(mem)
        v.save("", "pw"); v.save("a", "")
        assertTrue(mem.m.isEmpty())
    }

    @Test fun passwords_that_could_break_the_command_line_are_not_remembered() {
        assertTrue(RconCommands.rememberable("plain pass 123"))
        for (bad in listOf("a\"b", "a;b", "a\\b", "a\nb", "a\$b", ""))
            assertFalse(bad, RconCommands.rememberable(bad))
        assertEquals("rcon_password \"abc\"", RconCommands.setPassword("abc"))
        assertEquals("set _uib_ctl_saved \"1.2.3.4:26000\"", RconCommands.setSaved("1.2.3.4:26000"))
    }
}
