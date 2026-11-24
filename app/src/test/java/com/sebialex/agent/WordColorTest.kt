package com.sebialex.agent

import com.sebialex.agent.domain.markdown.WordColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contrast math that keeps a model-picked colour visible on the theme's surfaces. */
class WordColorTest {

    private val darkInk = 0x0B0A09
    private val lightText = 0xF2EEE8

    @Test
    fun aBrightColourOnDarkInkIsKeptAsIs() {
        for (hex in listOf("FF8800", "FFD60A", "0DA678", "FF5555")) {
            assertEquals("bright $hex stays", hex, WordColor.readableOn(hex, darkInk, lightText))
        }
    }

    @Test
    fun aDarkColourOnDarkInkIsPushedTowardTheTextColour() {
        // Nearly the background itself: as-is it is invisible, changed it must pass the bar.
        for (hex in listOf("0A0A0A", "1A1A1A", "223322")) {
            val out = WordColor.readableOn(hex, darkInk, lightText)
            assertTrue("$hex fades in; $out must not", WordColor.contrast(out.toInt(16), darkInk) >= 2.5f)
            assertTrue("$out should keep pulling visible weight", out != hex)
        }
    }

    @Test
    fun aColourNearTheTextColourIsDilutedOnlyAsFarAsNeeded() {
        // Mid-amber: readable on the dark ink, so it keeps its exact hue.
        val amber = "A07030"
        assertEquals(amber, WordColor.readableOn(amber, darkInk, lightText))
    }

    @Test
    fun theSameHexIsReadableOnALightSurfaceToo() {
        // A light theme flips the problem: near-white text on near-white paper.
        val lightPaper = 0xEFECE6
        val out = WordColor.readableOn("F5F2EC", lightPaper, 0x1A1817)
        assertTrue("near-white on light paper must survive", WordColor.contrast(out.toInt(16), lightPaper) >= 2.5f)
    }

    @Test
    fun aLeadingHashAndCaseAreAccepted() {
        assertEquals("FF8800", WordColor.readableOn("#ff8800", darkInk, lightText))
    }

    @Test
    fun luminanceKnowsBlackFromWhite() {
        assertTrue("white outshines black", WordColor.luminance(0xFFFFFF) > 0.99f)
        assertTrue("black is black", WordColor.luminance(0x000000) < 0.01f)
        assertTrue("contrast spans the scale", WordColor.contrast(0x000000, 0xFFFFFF) > 15f)
        assertEquals(1f, WordColor.contrast(0x123456, 0x123456), 0.0001f)
    }
}
