package com.example.test_project

import com.example.test_project.ondevice.DietCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DietCheckTest {
    /** What Gemma copied from the real cookie box. */
    private val cookieBox = DietCheck.readLabel(
        """
        FOOD: maple leaf cookies
        INGREDIENTS: UNBLEACHED ENRICHED FLOUR (WHEAT FLOUR, NIACIN), CANE SUGAR, PALM OIL, MAPLE SYRUP, SOY LECITHIN (EMULSIFIER).
        CONTAINS LINE: CONTAINS SOY, WHEAT.
        MAY CONTAIN LINE: MAY CONTAIN TRACES OF MILK, EGG, COCONUT.
        """.trimIndent(),
    )!!

    @Test
    fun theMatchingQuestionListsEveryItemOnTheLabel() {
        val question = DietCheck.matchQuestion("Allergic to soy.", cookieBox)!!
        assertTrue(question.startsWith("My dietary restrictions: Allergic to soy."))
        listOf("maple leaf cookies", "wheat flour", "soy lecithin", "soy", "wheat", "milk", "egg", "coconut").forEach {
            assertTrue("$it is asked about", it in question)
        }
    }

    @Test
    fun onlyItemsAnsweredYesAreMatches() {
        val judged = "maple leaf cookies: no\nwheat flour: no\nsoy lecithin: yes\nsoy: Yes.\nwheat: no\nmilk: no\negg: no\ncoconut: no"
        assertEquals("Not safe for you: it contains soy lecithin and soy.", DietCheck.verdict(cookieBox, judged))
        assertEquals(
            "It looks safe for you. Nothing on the label goes against your restrictions.",
            DietCheck.verdict(cookieBox, "soy: no\nwheat: no\nmilk: no"),
        )
    }

    @Test
    fun aMatchInTheIngredientsOrContainsLineIsContainsNeverMayContain() {
        assertEquals("Not safe for you: it contains soy and soy lecithin.", DietCheck.verdict(cookieBox, "soy, soy lecithin"))
    }

    @Test
    fun aMatchOnlyInTheMayContainLineIsMayContain() {
        assertEquals("Be careful: the label says it may contain milk.", DietCheck.verdict(cookieBox, "Milk."))
    }

    @Test
    fun bothKindsAreSaid() {
        assertEquals(
            "Not safe for you: it contains wheat. It may also contain milk and egg.",
            DietCheck.verdict(cookieBox, "WHEAT, MILK, EGG"),
        )
    }

    @Test
    fun noMatchOnAReadableLabelIsSafe() {
        assertEquals("It looks safe for you. Nothing on the label goes against your restrictions.", DietCheck.verdict(cookieBox, "NONE"))
    }

    @Test
    fun aMatchGemmaCannotPlaceCountsAsContains() {
        assertEquals("Not safe for you: it contains gluten.", DietCheck.verdict(cookieBox, "gluten"))
    }

    @Test
    fun pluralsStillPlaceTheMatch() {
        assertEquals("Be careful: the label says it may contain eggs.", DietCheck.verdict(cookieBox, "eggs"))
    }

    @Test
    fun noLabelAtAllSaysSo() {
        val apple = DietCheck.readLabel("FOOD: an apple\nINGREDIENTS: NONE\nCONTAINS LINE: NONE\nMAY CONTAIN LINE: NONE")!!
        assertEquals(
            "I can't see an ingredient list, so I can't be sure. Nothing I can see goes against your restrictions.",
            DietCheck.verdict(apple, "NONE"),
        )
        assertEquals("Not safe for you: it contains an apple.", DietCheck.verdict(apple, "an apple"))
    }

    @Test
    fun nothingReadableAtAllAsksNothing() {
        val blank = DietCheck.readLabel("FOOD: NONE\nINGREDIENTS: NONE\nCONTAINS LINE: NONE\nMAY CONTAIN LINE: NONE")!!
        assertNull(DietCheck.matchQuestion("Allergic to soy.", blank))
        assertEquals(
            "I can't see an ingredient list, so I can't be sure. Nothing I can see goes against your restrictions.",
            DietCheck.verdict(blank, null),
        )
    }

    @Test
    fun formattingSymbolsAreIgnored() {
        val label = DietCheck.readLabel("**INGREDIENTS:** soy flour\n**CONTAINS LINE:** NONE")!!
        assertEquals("Not safe for you: it contains soy flour.", DietCheck.verdict(label, "**soy flour**"))
    }

    @Test
    fun notFoodIsSilent() {
        assertNull(DietCheck.readLabel("NOT FOOD."))
        assertNull(DietCheck.readLabel("**Not food**"))
    }

    @Test
    fun aReplyThatIsNotTheFormIsShownAsItIs() {
        val label = DietCheck.readLabel("It contains **walnuts**.")!!
        assertNull(DietCheck.matchQuestion("Allergic to walnuts.", label))
        assertEquals("It contains walnuts.", DietCheck.verdict(label, null))
    }
}
