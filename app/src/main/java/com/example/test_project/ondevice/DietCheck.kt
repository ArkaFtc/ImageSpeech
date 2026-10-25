package com.example.test_project.ondevice

/**
 * Checks a photo against the user's dietary restrictions, in two short steps Gemma is good at.
 *
 * 1. Gemma copies what it sees: the food and the allergen lines ([instruction]).
 * 2. Gemma answers yes or no for each of those items ([matchQuestion]).
 *
 * The verdict is built here: a match is "contains" when it is the food or in the Contains line,
 * and "may contain" only when it is only in the May contain line. The full ingredient list is
 * not copied: on the phone CPU that took about 25 seconds and made the matching longer, so
 * restrictions only found in the ingredients (such as meat) can be missed. Measured on a real cookie
 * box, asking for a sentence made Gemma indecisive ("cannot tell" with the list in plain view) or
 * contradictory ("contains soy, therefore it does not go against your restrictions"), and one
 * combined form made it list every allergen on the label whatever the restrictions said.
 */
object DietCheck {
    /** The whole reply when the photo has nothing to do with food. Never shown. */
    const val NOT_FOOD = "NOT FOOD"

    private const val FOOD = "FOOD"
    private const val CONTAINS_LINE = "CONTAINS LINE"
    private const val MAY_CONTAIN_LINE = "MAY CONTAIN LINE"
    private val KEYS = listOf(MAY_CONTAIN_LINE, CONTAINS_LINE, FOOD)

    /** At most this many items go into the matching question. */
    private const val MAX_ITEMS = 40

    /** What Gemma copied from the photo. [fields] is empty when its reply did not follow the form. */
    class Label(val raw: String, private val fields: Map<String, String>, val isForm: Boolean) {
        val food: String? get() = fields[FOOD]
        val contains: String? get() = fields[CONTAINS_LINE]
        val mayContain: String? get() = fields[MAY_CONTAIN_LINE]
        val hasAllergenLines: Boolean get() = contains != null || mayContain != null

        /** Every item worth checking: the food and the allergens named. */
        val items: List<String>
            get() = (listOfNotNull(food) + split(contains?.let(::withoutLead)) +
                split(mayContain?.let(::withoutLead))).map { it.lowercase() }.distinct().take(MAX_ITEMS)
    }

    fun instruction(restrictions: String): String =
        "My dietary restrictions: $restrictions\n" +
            "Read the photo carefully and fill in this form. Write NONE for anything that is not there. " +
            "Use one line per field and nothing else.\n" +
            "$FOOD: what the food or drink is, in a few words\n" +
            "$CONTAINS_LINE: the allergen line starting with Contains, copied as printed\n" +
            "$MAY_CONTAIN_LINE: the line starting with May contain, or about traces, copied as printed\n" +
            "Only if the photo has nothing to do with food or drink at all, reply only: $NOT_FOOD"

    /** Reads Gemma's reply to [instruction]; null when the photo is not food. */
    fun readLabel(reply: String): Label? {
        val clean = PlainText.clean(reply).trim()
        if (clean.isEmpty() || isNotFood(clean)) return null
        val (isForm, fields) = parse(clean)
        return Label(clean, fields, isForm)
    }

    /** The second question, or null when there is nothing to check. */
    fun matchQuestion(restrictions: String, label: Label): String? {
        val items = label.items
        if (!label.isForm || items.isEmpty()) return null
        return "My dietary restrictions: $restrictions\n" +
            "Judge each item below on its own. Write the item, a colon, then yes if my restrictions say I " +
            "avoid it or a food made from it, or no if my restrictions do not mention it. One item per line.\n" +
            items.joinToString("\n")
    }

    /** The sentence to show and speak, from the label and Gemma's reply to [matchQuestion]. */
    fun verdict(label: Label, matches: String?): String {
        if (!label.isForm) return label.raw
        val matched = matchedItems(matches)
        val definite = listOfNotNull(label.food, label.contains?.let(::withoutLead)).joinToString(", ").lowercase()
        val possible = label.mayContain?.let(::withoutLead).orEmpty().lowercase()
        // Only in the May contain line is "may contain"; anything else, even a match Gemma could
        // not place, is "contains": a missed allergen is worse than a cautious one.
        val (maybe, found) = matched.map { it.lowercase() }.distinct().partition { item ->
            mentions(possible, item) && !mentions(definite, item)
        }
        return when {
            found.isNotEmpty() && maybe.isNotEmpty() -> "Not safe for you: it contains ${spoken(found)}. It may also contain ${spoken(maybe)}."
            found.isNotEmpty() -> "Not safe for you: it contains ${spoken(found)}."
            maybe.isNotEmpty() -> "Be careful: the label says it may contain ${spoken(maybe)}."
            label.hasAllergenLines -> "It looks safe for you. Nothing in the allergen information goes against your restrictions."
            else -> "I can't see allergen information, so I can't be sure. Nothing I can see goes against your restrictions."
        }
    }

    /**
     * The items Gemma answered yes to ("soy: yes"). Asked to list the matches instead, it listed
     * every allergen on the label whatever the restrictions said (measured on device).
     */
    private fun matchedItems(reply: String?): List<String> {
        val clean = PlainText.clean(reply.orEmpty())
        val judged = clean.lines().mapNotNull { line ->
            val colon = line.lastIndexOf(':')
            if (colon < 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim().lowercase()
        }
        if (judged.isEmpty()) return if (isNone(clean)) emptyList() else split(clean)
        return judged.filter { (item, answer) -> item.isNotEmpty() && answer.startsWith("yes") }.map { it.first }
    }

    fun isNotFood(reply: String): Boolean = letters(reply).startsWith(letters(NOT_FOOD))

    /**
     * Whether [reply] is the form, and its fields without those that are NONE. A form that is all
     * NONE is still the form: nothing could be read.
     */
    private fun parse(reply: String): Pair<Boolean, Map<String, String>> {
        val fields = mutableMapOf<String, String>()
        var recognised = false
        for (line in reply.lines()) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = KEYS.firstOrNull { it == line.substring(0, colon).trim().uppercase() } ?: continue
            recognised = true
            val value = line.substring(colon + 1).trim().trimEnd('.', ';', ',').trim()
            if (!isNone(value)) fields[key] = value
        }
        return recognised to fields
    }

    /** "Contains soy, wheat" becomes "soy, wheat"; "May contain traces of milk" becomes "milk". */
    private fun withoutLead(line: String): String =
        line.replace(Regex("^\\s*(may\\s+contain|contains?)\\s*(traces\\s+of)?\\s*:?\\s*", RegexOption.IGNORE_CASE), "")

    private fun split(list: String?): List<String> =
        list.orEmpty().split(',', ';', '(', ')', '[', ']')
            .map { it.trim().trimEnd('.').trim().removePrefix("and ").removePrefix("or ").trim() }
            .filter { it.isNotEmpty() && !isNone(it) }

    /** Whether [text] names [item], allowing a plural or singular form ("walnut", "walnuts"). */
    private fun mentions(text: String, item: String): Boolean {
        if (item.isEmpty()) return false
        val stem = item.removeSuffix("es").removeSuffix("s")
        return item in text || (stem.length >= 3 && stem in text)
    }

    private fun isNone(value: String): Boolean {
        val words = letters(value)
        val lower = value.trim().lowercase()
        return words.isEmpty() || words in setOf("NONE", "NA", "NO", "NOTHING", "NOTVISIBLE", "UNKNOWN") ||
            lower.startsWith("none") || lower.startsWith("no ") || lower.startsWith("not ") || lower.startsWith("nothing")
    }

    /** A list said in lower case (a phone voice spells out "SOY"): "soy, wheat and milk". */
    private fun spoken(items: List<String>): String = when (items.size) {
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun letters(text: String): String = text.filter { it.isLetter() }.uppercase()
}
