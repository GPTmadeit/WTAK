package com.atakwatch.minimap.update

/**
 * A release version, as it appears on a git tag or in `versionName`.
 *
 * GitHub releases carry no `versionCode`, so "is this newer than what I am
 * running" has to be answered from the name. Comparison is numeric per
 * component, because the obvious string compare gets `1.10.0` vs `1.9.0`
 * backwards — exactly the kind of bug that silently stops offering updates
 * once a project passes its ninth minor release.
 */
data class Version(val parts: List<Int>) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        val width = maxOf(parts.size, other.parts.size)
        for (i in 0 until width) {
            // A missing component is zero, so 1.8 and 1.8.0 are the same release.
            val diff = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        /**
         * Parse a tag or version name. Tolerates a leading `v`, and stops at the
         * first non-numeric component so pre-release suffixes (`1.9.0-rc1`)
         * compare as their release version rather than failing outright.
         * Returns null when there is no leading number at all.
         */
        fun parse(raw: String?): Version? {
            val cleaned = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
            val parts = ArrayList<Int>(3)
            for (token in cleaned.split('.')) {
                val digits = token.takeWhile { it.isDigit() }
                if (digits.isEmpty()) break
                parts.add(digits.toIntOrNull() ?: break)
                // A component with a suffix ("0-rc1") ends the numeric run.
                if (digits.length != token.length) break
            }
            return if (parts.isEmpty()) null else Version(parts)
        }

        /** True when [candidate] is a strictly newer release than [current]. */
        fun isNewer(candidate: String?, current: String?): Boolean {
            val a = parse(candidate) ?: return false
            val b = parse(current) ?: return false
            return a > b
        }
    }
}
