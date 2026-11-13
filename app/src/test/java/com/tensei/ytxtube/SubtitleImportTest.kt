package com.tensei.ytxtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleImportTest {
    @Test
    fun importsArabicSrtWithBomAndTimestampsBeyondThreeHours() {
        val file = "\uFEFF1\r\n03:02:01,500 --> 03:02:03,250\r\nترجمة عربية\r\n"
        val cue = SubtitleParser.readSubtitleFile(file.byteInputStream()).single()
        assertEquals(10_921_500L, cue.startMs)
        assertEquals(10_923_250L, cue.endMs)
        assertEquals("ترجمة عربية", cue.text)
    }

    @Test
    fun importsWebVttAndIgnoresFilesWithoutCues() {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:03.000\nHello\n"
        assertEquals(ParsedCue(1000L, 3000L, "Hello"), SubtitleParser.readSubtitleFile(vtt.byteInputStream()).single())
        assertTrue(SubtitleParser.readSubtitleFile("No subtitles".byteInputStream()).isEmpty())
    }
}
