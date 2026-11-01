package io.github.douyinextractor.core.model

import org.junit.Assert.*
import org.junit.Test

class MediaFormatSelectorTest {
    @Test fun `public alternative is preferred without pretending its watermark status is verified`() {
        val candidate = format("candidate", null, null, null, null).copy(watermarkFreeCandidate = true)
        val original = format("original", 1080, 1920, null, "h264").copy(watermarked = true)
        val unknown = original.copy(watermarked = null)
        val clean = original.copy(watermarked = false)
        assertSame(candidate, MediaFormatSelector.recommended(listOf(original, unknown, candidate)))
        assertSame(clean, MediaFormatSelector.recommended(listOf(candidate, clean)))
        assertNull(candidate.watermarked)
    }

    @Test fun `non-watermarked source outranks higher resolution watermarked and unknown sources`() {
        val clean = format("clean", 720, 1280, null, "h265").copy(watermarked = false)
        val marked = format("marked", 2160, 3840, null, "h264").copy(watermarked = true)
        val unknown = format("unknown", 1080, 1920, null, "h264")
        for (items in listOf(listOf(marked, clean, unknown), listOf(unknown, clean, marked))) {
            assertSame(clean, MediaFormatSelector.recommended(items))
            assertSame(marked, MediaFormatSelector.highestQuality(items))
        }
        assertSame(unknown, MediaFormatSelector.recommended(listOf(marked, unknown)))
    }

    @Test fun `recommended favors known AVC while highest quality compares actual pixel area`() {
        val avc = format("avc", 720, 1280, 2_000_000, "h264")
        val hevc = format("hevc", 1080, 1920, 3_000_000, "h265")
        val items = listOf(hevc, avc)
        assertSame(avc, MediaFormatSelector.recommended(items))
        assertSame(hevc, MediaFormatSelector.highestQuality(items))
    }

    @Test fun `selection is stable across input order and compares bitrate for equal dimensions`() {
        val lower = format("lower", 1920, 1080, 2_000_000, "avc1.640028")
        val higher = format("higher", 1920, 1080, 4_000_000, "h264")
        for (items in listOf(listOf(lower, higher), listOf(higher, lower))) {
            assertSame(higher, MediaFormatSelector.recommended(items))
            assertSame(higher, MediaFormatSelector.highestQuality(items))
        }
    }

    @Test fun `unknown quality is not invented and empty lists have no recommendation`() {
        val unknown = format("unknown", null, null, null, null)
        val known = format("known", 720, 1280, 1_000_000, null)
        assertSame(known, MediaFormatSelector.highestQuality(listOf(unknown, known)))
        assertNull(MediaFormatSelector.highestQuality(listOf(unknown)))
        assertNull(MediaFormatSelector.recommended(emptyList()))
        assertNull(MediaFormatSelector.highestQuality(emptyList()))
    }

    private fun format(name: String, width: Int?, height: Int?, bitrate: Long?, codec: String?) = MediaFormat(
        "https://synthetic.douyinvod.com/$name.mp4", width, height, bitrate, codec, null, null, "test",
    )
}
