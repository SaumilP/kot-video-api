package org.sandcastle.apps

object HttpUtils {
    fun accepts(acceptHeader: String, toAccept: String): Boolean {
        val acceptValues = acceptHeader.split("\\s*(,|;)\\s*".toRegex())
        acceptValues.sorted()

        return acceptValues.binarySearch(toAccept) > -1
                || acceptValues.binarySearch(toAccept.replace("/.*$".toRegex(), "/*")) > -1
                || acceptValues.binarySearch("*/*") > -1
    }

    fun matches(matchHeader: String, toMatch: String): Boolean {
        val matchValues = matchHeader.split(",\\s*".toRegex())
        matchValues.sorted()
        return matchValues.binarySearch(toMatch) > -1
                || matchValues.binarySearch("*") > -1
    }
}