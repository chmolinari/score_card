package com.christianmolinari.scorecard

import com.christianmolinari.scorecard.domain.HelpBlock
import com.christianmolinari.scorecard.domain.helpTopics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The in-app help page's content, transcribed from docs/help-content.md.
//
// The identifiers and their order are the cross-platform contract with the iOS
// port: the same nine topics appear there, in the same order, pinned by the
// matching assertion in the iOS test suite. Adding, removing or reordering a
// topic therefore fails a test on each side until both have been updated, which
// is the point — a help page that describes one port while the other quietly
// does something else is worse than no help page.
//
// The rest is a shape check: prose is not asserted word for word (the document
// is the source of truth for the wording), but an empty topic or a blank list
// item would ship a broken page, and neither is visible at compile time.
class HelpContentTest {

    private val canonicalIds = listOf(
        "gettingStarted",
        "startingGame",
        "keepingScore",
        "handsAndDealer",
        "finishingGame",
        "correctingResult",
        "registeringPastGame",
        "playersAndTeams",
        "dataAndBackups",
    )

    @Test
    fun `the topics are the canonical nine, in the canonical order`() {
        assertEquals(canonicalIds, helpTopics.map { it.id })
    }

    @Test
    fun `topic ids are unique`() {
        // The id is the navigation key, so a duplicate would make one topic
        // unreachable rather than merely look untidy.
        assertEquals(helpTopics.size, helpTopics.map { it.id }.toSet().size)
    }

    @Test
    fun `every topic has a title and at least one block`() {
        helpTopics.forEach { topic ->
            assertTrue("blank title for ${topic.id}", topic.title.isNotBlank())
            assertTrue("no blocks in ${topic.id}", topic.blocks.isNotEmpty())
        }
    }

    @Test
    fun `no block is empty or blank`() {
        helpTopics.forEach { topic ->
            topic.blocks.forEach { block ->
                when (block) {
                    is HelpBlock.Paragraph ->
                        assertTrue("blank paragraph in ${topic.id}", block.text.isNotBlank())

                    is HelpBlock.Note ->
                        assertTrue("blank note in ${topic.id}", block.text.isNotBlank())

                    is HelpBlock.Steps -> {
                        assertTrue("empty steps in ${topic.id}", block.items.isNotEmpty())
                        block.items.forEach {
                            assertTrue("blank step in ${topic.id}", it.isNotBlank())
                        }
                    }

                    is HelpBlock.Bullets -> {
                        assertTrue("empty bullets in ${topic.id}", block.items.isNotEmpty())
                        block.items.forEach {
                            assertTrue("blank bullet in ${topic.id}", it.isNotBlank())
                        }
                    }
                }
            }
        }
    }
}
