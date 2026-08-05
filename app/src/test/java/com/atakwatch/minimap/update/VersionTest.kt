package com.atakwatch.minimap.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun `parses tags with and without a leading v`() {
        assertEquals(listOf(1, 8, 1), Version.parse("v1.8.1")!!.parts)
        assertEquals(listOf(1, 8, 1), Version.parse("1.8.1")!!.parts)
        assertEquals(listOf(2, 0), Version.parse(" V2.0 ")!!.parts)
    }

    @Test
    fun `compares numerically, not as text`() {
        // The whole reason this class exists: "1.10.0" sorts before "1.9.0" as a
        // string, which would quietly stop offering updates after v1.9.
        assertTrue(Version.isNewer("1.10.0", "1.9.0"))
        assertFalse(Version.isNewer("1.9.0", "1.10.0"))
        assertTrue(Version.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `a missing component counts as zero`() {
        assertEquals(0, Version.parse("1.8")!!.compareTo(Version.parse("1.8.0")!!))
        assertTrue(Version.isNewer("1.8.1", "1.8"))
    }

    @Test
    fun `the installed version is not an update to itself`() {
        assertFalse(Version.isNewer("1.8.1", "1.8.1"))
        assertFalse(Version.isNewer("v1.8.1", "1.8.1"))
    }

    @Test
    fun `older releases are never offered`() {
        assertFalse(Version.isNewer("1.7.0", "1.8.1"))
        assertFalse(Version.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `pre-release suffixes compare as their release version`() {
        assertEquals(listOf(1, 9, 0), Version.parse("1.9.0-rc1")!!.parts)
        assertTrue(Version.isNewer("1.9.0-rc1", "1.8.1"))
    }

    @Test
    fun `unparseable input never claims to be an update`() {
        assertNull(Version.parse(null))
        assertNull(Version.parse(""))
        assertNull(Version.parse("nightly"))
        assertFalse(Version.isNewer("nightly", "1.8.1"))
        assertFalse(Version.isNewer("1.9.0", null))
    }
}
