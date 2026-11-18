package app.dosekeeper.core

/**
 * Injection sites used in turn, for weekly injections where the same spot shouldn't be used twice
 * running. A medicine stores its sites as one per line; each logged dose records the site it used.
 * Nothing in the app asks for a site yet: this is the rule and storage the next version builds on.
 */
object SiteRotation {

    /** One site per line. Blank lines and repeats (ignoring case) are dropped; order is kept. */
    fun parse(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val seen = mutableSetOf<String>()
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    }

    /** The stored form of [sites], or null when there are none. */
    fun format(sites: List<String>): String? = parse(sites.joinToString("\n")).takeIf { it.isNotEmpty() }?.joinToString("\n")

    /**
     * The site to use next: the one after [last] in the list, wrapping round to the first.
     * The first site when nothing has been logged yet, or when [last] is no longer in the list.
     * Null when the medicine has no sites.
     */
    fun next(sites: List<String>, last: String?): String? {
        if (sites.isEmpty()) return null
        val index = last?.let { l -> sites.indexOfFirst { it.equals(l.trim(), ignoreCase = true) } } ?: -1
        return sites[(index + 1) % sites.size]
    }
}
