package com.rafay.autoeditor

import com.rafay.autoeditor.engine.FilterGraphBuilder
import com.rafay.autoeditor.engine.Plan
import com.rafay.autoeditor.engine.VideoAnalyzer
import org.junit.Assert.*
import org.junit.Test

class FilterGraphBuilderTest {
    private fun plan(intervals: List<Pair<Double, Double>> = listOf(8.0 to 12.0), audio: Boolean = true) =
        Plan(1080, 1920, 30.0, audio, 48000, 4.0, 5.0, 960, intervals, EditSettings())

    @Test fun keepsExactlyLastSecondAtNormalSpeed() {
        val g = FilterGraphBuilder.build(plan())
        assertTrue(g.contains("trim=start=4.000:end=5.000"))
        assertTrue(g.contains("atrim=start=4.000:end=5.000"))
    }
    @Test fun contrastAndSaturation() {
        val g = FilterGraphBuilder.build(plan())
        assertTrue(g.contains("*1.100+128")); assertTrue(g.contains("*0.950+128"))
    }
    @Test fun speedIs104() {
        val g = FilterGraphBuilder.build(plan())
        assertTrue(g.contains("setpts=PTS/1.040")); assertTrue(g.contains("atempo=1.040"))
    }
    @Test fun mirrorOnlyUpperHalfAndRelativeTime() {
        val g = FilterGraphBuilder.build(plan())
        assertTrue(g.contains("crop=1080:960:0:0,hflip"))
        assertTrue(g.contains("between(t,3.000,7.000)"))   // 8-5 .. 12-5
    }
    @Test fun noMirrorNoOverlay() = assertFalse(FilterGraphBuilder.build(plan(emptyList())).contains("hflip"))
    @Test fun noAudioGraph() = assertTrue(FilterGraphBuilder.build(plan(audio = false)).contains("a=0"))
    @Test fun intervalMergingAndParsing() {
        val t = listOf(0.0, 0.33, 0.66, 1.0, 1.33, 1.66, 2.0); val f = listOf(true, true, true, false, true, true, false)
        assertEquals(1, VideoAnalyzer.toIntervals(t, f, 0.33).size)
        assertEquals(2, VideoAnalyzer.parseIntervals("1-2, 5-6.5").size)
    }
}
