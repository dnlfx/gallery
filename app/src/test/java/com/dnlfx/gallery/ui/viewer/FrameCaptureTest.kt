package com.dnlfx.gallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCaptureTest {
    @Test
    fun namesFramesAfterTheVideoAndTheMoment() {
        assertEquals("PXL_1_frame_01-23-456.jpg", frameFileName("PXL_1.mp4", 83_456))
        assertEquals("trip_frame_1-02-03-004.jpg", frameFileName("trip.mov", 3_723_004))
        assertEquals("video_frame_00-00-000.jpg", frameFileName(null, 0))
    }
}
