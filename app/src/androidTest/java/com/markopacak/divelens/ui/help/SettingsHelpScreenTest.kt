package com.markopacak.divelens.ui.help

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.markopacak.divelens.ui.PAGE_SLIDE_MILLIS
import com.markopacak.divelens.ui.theme.DiveLensTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The Settings & help screen (add-help-and-legal D2, task 3.2). */
class SettingsHelpScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<String>()
    private var linksOpen = true

    private fun setScreen(withSetting: Boolean = false) {
        val actions = HelpActions(
            sendFeedback = { events += "feedback"; linksOpen },
            openUrl = { events += it; linksOpen },
            showTipsAgain = { events += "tips" },
        )
        rule.setContent {
            DiveLensTheme {
                SettingsHelpScreen(
                    appVersion = "1.0",
                    actions = actions,
                    onBack = { events += "back" },
                    settings = if (withSetting) ({ Text("A setting") }) else null,
                )
            }
        }
    }

    private fun scrollTo(text: String) = rule.onNodeWithTag("settings_help").performScrollToNode(hasText(text))

    private fun sections(): List<String> = rule.onAllNodesWithTag("section", useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.Text].joinToString("") }

    @Test fun sectionsInOrderWithoutAnEmptySettingsSection() {
        setScreen()
        // The list only composes visible rows, so collect the headers while scrolling down.
        val seen = LinkedHashSet(sections())
        for (row in listOf("Gestures", "Show tips again", "Terms of use")) {
            scrollTo(row)
            seen += sections()
        }
        assertEquals(listOf("Support DiveLens", "Help", "About"), seen.toList())
        rule.onAllNodesWithText("Settings").assertCountEquals(0)
    }

    @Test fun aSettingAddsTheSettingsSectionFirst() {
        setScreen(withSetting = true)
        rule.onNodeWithText("A setting").assertIsDisplayed()
        assertEquals("Settings", sections().first())
    }

    @Test fun topicsStartFoldedAndUnfoldOnTap() {
        setScreen()
        rule.onAllNodesWithTag("topic_text").assertCountEquals(0)
        scrollTo("Gestures")
        rule.onNodeWithText("Gestures").performClick()
        rule.onNodeWithTag("topic_text").assertIsDisplayed()
        rule.onNodeWithText("Hold on a photo or video to see the original", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Gestures").performClick()
        rule.onAllNodesWithTag("topic_text").assertCountEquals(0)
    }

    @Test fun privacyTopicMakesThePublicClaim() {
        setScreen()
        scrollTo("Privacy")
        rule.onNodeWithText("Privacy").performClick()
        rule.onNodeWithText("Photos and videos never leave your device.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("not the location", substring = true).assertIsDisplayed()
    }

    @Test fun aboutShowsVersionDeveloperAndLicence() {
        setScreen()
        scrollTo("Source code")
        rule.onNodeWithTag("about_version").assertIsDisplayed()
        rule.onNodeWithText("DiveLens 1.0").assertIsDisplayed()
        rule.onNodeWithText("Developed by Marko Pacak").assertIsDisplayed()
        rule.onNodeWithText("Licence: GPL-3.0", substring = true).assertIsDisplayed()
    }

    @Test fun legalPagesOpenAndCloseWithBack() {
        setScreen()
        for ((entry, expected) in listOf(
            "Privacy policy" to "Photos and videos never leave your device.",
            "Terms of use" to "No warranty",
            "Open-source notices" to "Maven groups: androidx.*",
        )) {
            scrollTo(entry)
            rule.onNodeWithText(entry).performClick()
            rule.onNodeWithTag("legal_page").assertIsDisplayed().performScrollToNode(hasText(expected, substring = true))
            rule.onNodeWithText(expected, substring = true).assertIsDisplayed()
            // Markdown is rendered, not shown raw.
            rule.onAllNodes(SemanticsMatcher("raw markup") { node ->
                node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().any { it.text.contains("**") || it.text.startsWith("# ") }
            }).assertCountEquals(0)
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.onNodeWithTag("settings_help").assertIsDisplayed()
        }
        assertEquals(emptyList<String>(), events) // back closed the pages, not the screen
    }

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test fun legalPageSlidesInAndOut() {
        setScreen()
        scrollTo("Privacy policy")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Privacy policy").performClick()
        rule.mainClock.advanceTimeBy(100)
        assertEquals(1, shown("settings_help"))
        assertEquals(1, shown("legal_page"))
        rule.mainClock.advanceTimeBy(PAGE_SLIDE_MILLIS + 100L)
        assertEquals(0, shown("settings_help"))
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(1, shown("settings_help"))
        assertEquals(1, shown("legal_page"))
        rule.mainClock.advanceTimeBy(PAGE_SLIDE_MILLIS + 100L)
        assertEquals(0, shown("legal_page"))
    }

    @Test fun theListComesBackWhereItWasScrolled() {
        setScreen()
        scrollTo("Privacy policy")
        rule.onNodeWithText("Privacy policy").performClick()
        rule.onNodeWithTag("legal_page").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        // No scrolling: the About entries are still on screen.
        rule.onNodeWithText("Privacy policy").assertIsDisplayed()
        rule.onNodeWithText("Terms of use").assertIsDisplayed()
    }

    @Test fun noticesIncludeTheFullLicence() {
        setScreen()
        scrollTo("Open-source notices")
        rule.onNodeWithText("Open-source notices").performClick()
        rule.onNodeWithTag("legal_page").performScrollToNode(hasText("GNU GENERAL PUBLIC LICENSE", substring = true))
        rule.onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertIsDisplayed()
    }

    @Test fun entriesCallTheirActions() {
        setScreen()
        rule.onNodeWithText("Send feedback").performClick()
        rule.onNodeWithText("Report a bug").performClick()
        scrollTo("Show tips again")
        rule.onNodeWithText("Show tips again").performClick()
        rule.onNodeWithText("The tips will show again in the viewer.").assertIsDisplayed()
        scrollTo("Source code")
        rule.onNodeWithText("Source code").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("feedback", Links.ISSUES, "tips", Links.REPOSITORY, "back"), events)
    }

    @Test fun withoutAMatchingAppTheAddressIsShown() {
        linksOpen = false
        setScreen()
        rule.onNodeWithText("Send feedback").performClick()
        rule.onNodeWithText("No app to open this").assertIsDisplayed()
        rule.onNodeWithTag("unopened_address").assertIsDisplayed().assertTextEquals(Links.FEEDBACK_EMAIL)
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText("Report a bug").performClick()
        rule.onNodeWithTag("unopened_address").assertTextEquals(Links.ISSUES)
    }
}
