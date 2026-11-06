package io.github.razvanmanolache.herdronthego

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PreferencesTest {
    private class Store(private val succeeds: Boolean) {
        var commits = 0
        val writes = mutableListOf<String>()
        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> { writes += args!![0] as String; proxy }
                "commit" -> { commits++; succeeds }
                "apply" -> throw AssertionError("Checked writes must not become asynchronous")
                else -> throw AssertionError(method.name)
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            if (method.name == "edit") editor else throw AssertionError(method.name)
        } as SharedPreferences
    }

    @Test fun failedCommitRemainsObservable() {
        val store = Store(false)
        assertFalse(store.preferences.commitEdit { putString("credential", "test") })
        assertEquals(listOf("credential"), store.writes)
        assertEquals(1, store.commits)
    }

    @Test fun successfulCommitRemainsObservable() {
        val store = Store(true)
        assertTrue(store.preferences.commitEdit { putString("host-key", "test") })
        assertEquals(1, store.commits)
    }

    @Test fun failedEditDoesNotCommitPartialChanges() {
        val store = Store(true)
        assertThrows(IllegalStateException::class.java) {
            store.preferences.commitEdit {
                putString("credential", "test")
                error("Edit failed")
            }
        }
        assertEquals(0, store.commits)
    }
}
