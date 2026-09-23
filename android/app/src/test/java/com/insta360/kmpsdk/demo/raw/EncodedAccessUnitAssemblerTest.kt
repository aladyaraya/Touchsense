package com.insta360.kmpsdk.demo.raw

import com.arashivision.sdk.camera.api.preview.PreviewStreamFrame
import com.arashivision.sdk.camera.api.preview.PreviewStreamType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EncodedAccessUnitAssemblerTest {
    @Test
    fun sameTimestampFragmentsAreJoinedAndEmittedOnNextTimestamp() {
        val output = mutableListOf<EncodedAccessUnit>()
        val assembler = EncodedAccessUnitAssembler(onAccessUnit = output::add)

        assembler.offer(video(byteArrayOf(1, 2), timestamp = 10))
        assembler.offer(video(byteArrayOf(3, 4), timestamp = 10))
        assembler.offer(video(byteArrayOf(5), timestamp = 11))

        assertEquals(1, output.size)
        assertEquals(10L, output.single().timestampMs)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.single().data)
    }

    @Test
    fun nonVideoAndOtherVideoPlaneAreIgnored() {
        val output = mutableListOf<EncodedAccessUnit>()
        val assembler = EncodedAccessUnitAssembler(onAccessUnit = output::add)

        assembler.offer(
            PreviewStreamFrame(byteArrayOf(9), 1, PreviewStreamType.AUDIO),
        )
        assembler.offer(
            PreviewStreamFrame(byteArrayOf(8), 1, PreviewStreamType.VIDEO_L),
        )
        assembler.offer(video(byteArrayOf(7), timestamp = 2))
        assembler.flush()

        assertEquals(1, output.size)
        assertArrayEquals(byteArrayOf(7), output.single().data)
    }

    @Test
    fun oversizedUnitDiscardsWholeTimestampThenRecoversOnNextFrame() {
        val output = mutableListOf<EncodedAccessUnit>()
        val errors = mutableListOf<String>()
        val assembler =
            EncodedAccessUnitAssembler(
                maxAccessUnitBytes = 4,
                onAccessUnit = output::add,
                onMalformedUnit = errors::add,
            )

        assembler.offer(video(byteArrayOf(1, 2, 3), timestamp = 20))
        assembler.offer(video(byteArrayOf(4, 5, 6), timestamp = 20))
        assembler.offer(video(byteArrayOf(7), timestamp = 20))
        assembler.flush()

        assertEquals(1, errors.size)
        assertEquals(0, output.size)
        assembler.offer(video(byteArrayOf(8, 9), timestamp = 21))
        assembler.flush()
        assertEquals(1, output.size)
        assertArrayEquals(byteArrayOf(8, 9), output.single().data)
    }

    @Test
    fun singleOversizedFragmentNeverEntersBuffer() {
        val output = mutableListOf<EncodedAccessUnit>()
        val errors = mutableListOf<String>()
        val assembler =
            EncodedAccessUnitAssembler(
                maxAccessUnitBytes = 4,
                onAccessUnit = output::add,
                onMalformedUnit = errors::add,
            )

        assembler.offer(video(ByteArray(12) { 1 }, timestamp = 30))
        assembler.offer(video(byteArrayOf(2), timestamp = 30))
        assembler.flush()
        assertEquals(1, errors.size)
        assertEquals(0, output.size)
        assembler.offer(video(byteArrayOf(3), timestamp = 31))
        assembler.flush()
        assertEquals(1, output.size)
        assertArrayEquals(byteArrayOf(3), output.single().data)
    }

    private fun video(data: ByteArray, timestamp: Long) =
        PreviewStreamFrame(data, timestamp, PreviewStreamType.VIDEO)
}
