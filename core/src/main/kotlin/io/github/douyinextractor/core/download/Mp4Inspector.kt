package io.github.douyinextractor.core.download

import java.io.File
import java.io.RandomAccessFile

/** Container boundaries only; Android checks for a video track separately before publishing. */
object Mp4Inspector {
    fun verify(file: File) {
        fun invalid(): Nothing = throw DownloadException(DownloadProblem.NOT_VIDEO)
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            var position = 0L
            var boxes = 0
            var ftyp = false
            var moov = false
            var mdat = false
            while (position < length) {
                if (++boxes > 100_000 || length - position < 8) invalid()
                input.seek(position)
                val shortSize = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                var header = 8L
                val size = when (shortSize) {
                    0L -> length - position
                    1L -> { if (length - position < 16) invalid(); header = 16; input.readLong() }
                    else -> shortSize
                }
                if (size < header || size > length - position) invalid()
                if (position == 0L && type != "ftyp") invalid()
                when (type) {
                    "ftyp" -> { if (size < header + 8 || ftyp) invalid(); ftyp = true }
                    "moov" -> { if (size <= header) invalid(); moov = true }
                    "mdat" -> { if (size <= header) invalid(); mdat = true }
                }
                position += size
            }
            if (!ftyp || !moov || !mdat) invalid()
        }
    }
}
