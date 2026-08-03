package com.atakwatch.minimap.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onboarding flag was introduced in 1.4.0. Installs upgrading from an
 * earlier version have no stored value for it, and must not be dropped back
 * into first-run setup — they are already configured.
 *
 * This pins the rule the settings repository applies:
 *
 *     onboarded = storedFlag ?: (any preference is stored at all)
 *
 * Expressed here against a plain map so it can be tested without Android.
 */
class OnboardingMigrationTest {

    private fun onboarded(stored: Boolean?, prefs: Map<String, Any>): Boolean =
        stored ?: prefs.isNotEmpty()

    @Test
    fun `fresh install shows onboarding`() {
        assertFalse(onboarded(stored = null, prefs = emptyMap()))
    }

    @Test
    fun `upgrade from a configured install skips onboarding`() {
        // A 1.3.0 user: has settings, but never saw the onboarding flag.
        val old = mapOf("callsign" to "ALPHA", "team_color" to "CYAN")
        assertTrue(onboarded(stored = null, prefs = old))
    }

    @Test
    fun `a single stored preference is enough to count as an existing install`() {
        assertTrue(onboarded(stored = null, prefs = mapOf("imperial" to true)))
    }

    @Test
    fun `an explicit stored flag always wins`() {
        // Someone who deliberately re-ran setup must not be overridden by the
        // presence of other preferences.
        assertFalse(onboarded(stored = false, prefs = mapOf("callsign" to "ALPHA")))
        assertTrue(onboarded(stored = true, prefs = emptyMap()))
    }
}
