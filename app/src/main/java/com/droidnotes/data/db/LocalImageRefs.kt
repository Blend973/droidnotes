package com.droidnotes.data.db

/** Extracts the local image file names referenced by a note's Markdown content. */
object LocalImageRefs {
    private val REGEX = Regex("""local://images/([A-Za-z0-9._-]+)""")

    fun extractNames(content: String): Set<String> =
        REGEX.findAll(content).map { it.groupValues[1] }.toSet()
}
