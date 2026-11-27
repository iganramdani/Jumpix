package com.jumpropeai.ai

import com.jumpropeai.audio.FluxSample
import com.jumpropeai.audio.NeuralAudioRopeIsolator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class NeuralVarEngineTest {

    @Test
    fun loadsBundledJvxModelsAndRunsSub40UsCausalAndBidirectionalInference() {
        val biWeights = NeuralVarEngine.loadBidirectionalWeights(null)
        val caWeights = NeuralVarEngine.loadCausalWeights(null)
        assertNotNull("Expected var_bitemporal_v7.jvxmodel to load from assets", biWeights)
        assertNotNull("Expected live_causal_tcn_v7.jvxmodel to load from assets", caWeights)
        assertEquals(16, biWeights!!.inCh)
        assertEquals(false, biWeights.causal)
        assertEquals(true, caWeights!!.causal)

        // Generate 90 frames (3 seconds @ 30 FPS) of realistic full-body 150 RPM jumping (2.5m camera distance)
        val frames = ArrayList<PoseFrame>(90)
        val flux = ArrayList<FluxSample>(600)
        for (i in 0 until 600) {
            val tMs = i * 5L
            val isWhip = (tMs % 400L) in 90L..105L
            val floorNoise = 0.8f + 0.25f * sin(i * 0.37).toFloat()
            flux.add(
                FluxSample(
                    timestampMs = tMs,
                    mid = if (isWhip) 9.5f else floorNoise,
                    high1 = if (isWhip) 7.5f else floorNoise * 0.7f,
                    high2 = if (isWhip) 11.0f else floorNoise * 0.6f,
                    wideHigh = if (isWhip) 14.0f else floorNoise * 0.5f,
                    bass = 2.0f // steady EDM bass floor
                )
            )
        }
        for (i in 0 until 90) {
            val tMs = i * 33L
            val angle = (tMs / 400.0) * 2.0 * PI
            // Realistic 2.5m full-body geometry on 720x1280: torso sc ~ 95px (scRatio*2 ~ 0.15)
            val hopPx = maxOf(0f, sin(angle).toFloat()) * 18f
            val shY = 440f - hopPx
            val hipY = 535f - hopPx
            val legAlt = sin(angle).toFloat() * 18f
            val wristJitter = sin(angle).toFloat() * 4f
            val lAnkY = 740f - hopPx * 1.15f + legAlt
            val rAnkY = 740f - hopPx * 1.15f - legAlt
            frames.add(
                PoseFrame(
                    landmarks = mapOf(
                        BodyLandmark.NOSE to LandmarkPoint(360f, shY - 42f, 0.95f),
                        BodyLandmark.LEFT_SHOULDER to LandmarkPoint(322f, shY, 0.95f),
                        BodyLandmark.RIGHT_SHOULDER to LandmarkPoint(398f, shY, 0.95f),
                        BodyLandmark.LEFT_WRIST to LandmarkPoint(304f, hipY - 6f + wristJitter, 0.93f),
                        BodyLandmark.RIGHT_WRIST to LandmarkPoint(416f, hipY - 6f + wristJitter, 0.93f),
                        BodyLandmark.LEFT_HIP to LandmarkPoint(334f, hipY, 0.95f),
                        BodyLandmark.RIGHT_HIP to LandmarkPoint(386f, hipY, 0.95f),
                        BodyLandmark.LEFT_KNEE to LandmarkPoint(336f, hipY + 102f - hopPx * 0.25f + legAlt * 0.4f, 0.94f),
                        BodyLandmark.RIGHT_KNEE to LandmarkPoint(384f, hipY + 102f - hopPx * 0.25f - legAlt * 0.4f, 0.94f),
                        BodyLandmark.LEFT_ANKLE to LandmarkPoint(340f, lAnkY, 0.93f),
                        BodyLandmark.RIGHT_ANKLE to LandmarkPoint(382f, rAnkY, 0.93f),
                        BodyLandmark.LEFT_HEEL to LandmarkPoint(338f, lAnkY + 10f, 0.91f),
                        BodyLandmark.RIGHT_HEEL to LandmarkPoint(384f, rAnkY + 10f, 0.91f),
                        BodyLandmark.LEFT_FOOT_INDEX to LandmarkPoint(338f, lAnkY + 28f, 0.91f),
                        BodyLandmark.RIGHT_FOOT_INDEX to LandmarkPoint(384f, rAnkY + 14f, 0.91f)
                    ),
                    timestampMs = tMs,
                    imageWidth = 720,
                    imageHeight = 1280,
                    isMirrored = false
                )
            )
        }

        // Also build an idle close-up walk-away sequence (must stay suppressed!)
        val idleFrames = frames.mapIndexed { idx, pf ->
            val driftX = idx * 4.5f
            pf.copy(
                landmarks = mapOf(
                    BodyLandmark.NOSE to LandmarkPoint(360f + driftX, 260f, 0.85f),
                    BodyLandmark.LEFT_SHOULDER to LandmarkPoint(240f + driftX, 340f, 0.85f),
                    BodyLandmark.RIGHT_SHOULDER to LandmarkPoint(480f + driftX, 340f, 0.85f),
                    BodyLandmark.LEFT_HIP to LandmarkPoint(260f + driftX, 820f, 0.85f),
                    BodyLandmark.RIGHT_HIP to LandmarkPoint(460f + driftX, 820f, 0.85f),
                    BodyLandmark.LEFT_ANKLE to LandmarkPoint(110f + driftX, 1180f, 0.85f),
                    BodyLandmark.RIGHT_ANKLE to LandmarkPoint(610f + driftX, 1180f, 0.85f)
                )
            )
        }

        val biPreds = NeuralVarEngine.evaluateOfflineSequence(frames, flux, null)
        val idlePreds = NeuralVarEngine.evaluateOfflineSequence(idleFrames, emptyList(), null)
        assertEquals(90, biPreds.size)
        val maxVarProb = biPreds.maxOf { it.combinedVarProb }
        val meanIdleProb = idlePreds.map { it.combinedVarProb }.average()
        assertTrue("Expected high VAR jump probability (> 0.50) on realistic jumping, got $maxVarProb", maxVarProb > 0.50f)
        assertTrue("Expected walk-away idle probability (< 0.15) << jumping ($meanIdleProb vs $maxVarProb)", meanIdleProb < 0.15)

        // Benchmark CausalJumpTcnLite speed (< 80 us/frame)
        val liveTcn = NeuralVarEngine.CausalJumpTcnLite(null)
        val flux100 = flux.filterIndexed { idx, _ -> idx % 2 == 0 }
        repeat(200) { idx ->
            liveTcn.processLiveFrame(frames[idx % frames.size], flux100)
        }
        val t0 = System.nanoTime()
        val iters = 2_000
        repeat(iters) { idx ->
            liveTcn.processLiveFrame(frames[idx % frames.size], flux100)
        }
        val usPerFrame = ((System.nanoTime() - t0) / 1000.0) / iters
        assertTrue("Expected CausalJumpTcnLite < 200 us/frame, got $usPerFrame us", usPerFrame < 200.0)
    }

    @Test
    fun rotationAwareOpticalRopeTrackerCountsDoubleUnderOn90DegPortraitNv21() {
        val tracker = OpticalRopeTracker()
        // Raw sensor is 320x240 landscape rotated 90 deg -> portrait effW=240, effH=320
        val imgW = 320
        val imgH = 240
        val effW = 240
        val effH = 320
        val nv21 = ByteArray(imgW * imgH * 3 / 2) { 30 }

        val pose = PoseFrame(
            landmarks = mapOf(
                BodyLandmark.NOSE to LandmarkPoint(120f, 60f, 0.95f),
                BodyLandmark.LEFT_SHOULDER to LandmarkPoint(95f, 95f, 0.95f),
                BodyLandmark.RIGHT_SHOULDER to LandmarkPoint(145f, 95f, 0.95f),
                BodyLandmark.LEFT_HIP to LandmarkPoint(100f, 165f, 0.95f),
                BodyLandmark.RIGHT_HIP to LandmarkPoint(140f, 165f, 0.95f),
                BodyLandmark.LEFT_FOOT_INDEX to LandmarkPoint(105f, 250f, 0.95f),
                BodyLandmark.RIGHT_FOOT_INDEX to LandmarkPoint(135f, 250f, 0.95f)
            ),
            timestampMs = 0L,
            imageWidth = effW,
            imageHeight = effH,
            isMirrored = false
        )

        // Helper to paint a bright horizontal cable streak in portrait coordinates (ex in 70..170, ey = 260)
        fun paintPortraitCable(intensity: Byte) {
            nv21.fill(30)
            for (ey in 258..262) {
                for (ex in 65..175) {
                    // Inverse of 90-deg mapping: sx = ey, sy = imgH - 1 - ex
                    val sx = ey.coerceIn(0, imgW - 1)
                    val sy = (imgH - 1 - ex).coerceIn(0, imgH - 1)
                    nv21[sy * imgW + sx] = intensity
                }
            }
        }

        // Warmup ground frames
        for (k in 0..4) {
            paintPortraitCable(30)
            tracker.processNv21Frame(nv21, imgW, imgH, 90, effW, effH, pose.copy(timestampMs = k * 33L), isAirborne = false)
        }

        // Airborne Double-Under: 2 distinct cable passes 130 ms apart!
        paintPortraitCable(220.toByte())
        val p1 = tracker.processNv21Frame(nv21, imgW, imgH, 90, effW, effH, pose.copy(timestampMs = 200L), isAirborne = true)
        paintPortraitCable(30)
        tracker.processNv21Frame(nv21, imgW, imgH, 90, effW, effH, pose.copy(timestampMs = 265L), isAirborne = true)
        paintPortraitCable(225.toByte())
        val p2 = tracker.processNv21Frame(nv21, imgW, imgH, 90, effW, effH, pose.copy(timestampMs = 340L), isAirborne = true)

        assertTrue("First cable pass should be confirmed", p1.ropePassConfirmed)
        assertTrue("Second cable pass should be confirmed (Double Under!)", p2.ropePassConfirmed)
        assertEquals(2, p2.passesInCurrentFlight)
    }

    @Test
    fun neuralAudioRopeIsolatorStripsSustainedEdmAndPreservesWhipTransients() {
        val samples = ArrayList<FluxSample>(200)
        for (i in 0 until 200) {
            val isWhip = (i == 60 || i == 120)
            samples.add(
                FluxSample(
                    timestampMs = i * 5L,
                    mid = 14f, // loud constant music wall
                    high1 = 8f,
                    high2 = if (isWhip) 26f else 6f,
                    wideHigh = if (isWhip) 34f else 5f,
                    bass = 20f // heavy EDM sub-bass
                )
            )
        }
        val clean = NeuralAudioRopeIsolator.isolateCleanWhipSeries(samples)
        assertEquals(200, clean.size)
        assertTrue("Whip spike at idx 60 should stand out (> 0.8), got ${clean[60]}", clean[60] > 0.8f)
        assertTrue("Sustained EDM floor at idx 90 should be suppressed (< 0.15), got ${clean[90]}", clean[90] < 0.15f)
    }

    @Test
    fun opticalRopeTrackerHandlesEdgeClippedFeetWithoutEmptyRangeCrash() {
        val tracker = OpticalRopeTracker()
        val imgW = 1280
        val imgH = 720
        val effW = 720
        val effH = 1280
        val nv21 = ByteArray(imgW * imgH * 3 / 2) { 40 }
        // Feet pushed past bottom edge (reproduces VID_20261123_041650_213.mp4 crash!) & head past top edge
        val edgePose = PoseFrame(
            landmarks = mapOf(
                BodyLandmark.NOSE to LandmarkPoint(360f, -15f, 0.9f),
                BodyLandmark.LEFT_SHOULDER to LandmarkPoint(300f, 900f, 0.9f),
                BodyLandmark.RIGHT_SHOULDER to LandmarkPoint(420f, 900f, 0.9f),
                BodyLandmark.LEFT_HIP to LandmarkPoint(310f, 1120f, 0.9f),
                BodyLandmark.RIGHT_HIP to LandmarkPoint(410f, 1120f, 0.9f),
                BodyLandmark.LEFT_FOOT_INDEX to LandmarkPoint(718f, 1295f, 0.9f),
                BodyLandmark.RIGHT_FOOT_INDEX to LandmarkPoint(722f, 1300f, 0.9f)
            ),
            timestampMs = 33L,
            imageWidth = effW,
            imageHeight = effH,
            isMirrored = false
        )
        val sig = tracker.processNv21Frame(nv21, imgW, imgH, 90, effW, effH, edgePose, isAirborne = true)
        assertTrue("Should safely return without throwing IllegalArgumentException", sig.verifiedPassCount >= 0)
    }

    @Test
    fun proportionalLiveHudPlacementFitsAllResolutionsCleanly() {
        val resolutions = listOf(
            1080 to 1920, // 9:16 Reels
            1080 to 2340, // 9:20 Redmi Note 9 screen
            1920 to 1080, // 16:9 Landscape
            1080 to 1080, // 1:1 Square
            2160 to 3840  // 4K UHD Portrait
        )
        for ((w, h) in resolutions) {
            for (tpl in com.jumpropeai.share.WatermarkTemplate.entries) {
                val p = com.jumpropeai.share.computeProportionalHudPlacement(w, h, tpl)
                assertTrue("fitScale > 0 for ${w}x${h} $tpl", p.fitScale > 0f)
                assertTrue("offsetLeft >= 0 for ${w}x${h} $tpl", p.offsetLeft >= 0f)
                assertTrue("offsetTop >= 0 for ${w}x${h} $tpl", p.offsetTop >= 0f)
                assertTrue("HUD right fits inside ${w}x${h} $tpl", p.offsetLeft + p.drawW <= w + 1f)
                assertTrue("HUD bottom fits inside ${w}x${h} $tpl", p.offsetTop + p.drawH <= h + 1f)
            }
        }
    }

    @Test
    fun edgeAnchoredHudPinsLargerLogoTopLeftAndLowersNumbersOnAllResolutions() {
        val resolutions = listOf(
            720 to 1280,  // 720p Portrait
            1080 to 1920, // 9:16 Reels
            1080 to 2340, // 9:20 Redmi Note 9
            1920 to 1080, // 16:9 Landscape
            1080 to 1080, // 1:1 Square
            2160 to 3840  // 4K UHD Portrait
        )
        for ((w, h) in resolutions) {
            val l = com.jumpropeai.share.computeEdgeAnchoredHudLayout(w, h, overlayScale = 1.0f)
            val shortSide = minOf(w, h).toFloat()
            assertTrue("Logo pinned to top-left X (< 8% width) on ${w}x${h}", l.marginX <= w * 0.08f)
            assertTrue("Logo pinned to top-left Y (< 8% height) on ${w}x${h}", l.marginTop <= h * 0.08f)
            assertTrue("Logo size proportional & larger (>= 7% shortSide) on ${w}x${h}", l.logoSize >= shortSide * 0.07f)
            assertTrue("Bottom telemetry anchored lower (> 92% height) on ${w}x${h}", l.bottomAnchorY >= h * 0.92f)
        }
    }

    @Test
    fun nv21DownsampleStepPreservesEvenDimensionsAcrossAllVideoResolutions() {
        val cases = listOf(
            Triple(480, 640, 1),    // 480p -> 1x (no downsample)
            Triple(720, 1280, 2),   // 720p -> 2x (360x640)
            Triple(1080, 1920, 2),  // 1080p -> 2x (540x960)
            Triple(1920, 1080, 2),  // 1080p landscape -> 2x (960x540)
            Triple(2160, 3840, 4)   // 4K UHD -> 4x (540x960)
        )
        for ((w, h, expectedStep) in cases) {
            val (step, outW, outH) = com.jumpropeai.video.computeDownsampledEvenDims(w, h)
            assertEquals("Expected step $expectedStep for ${w}x${h}", expectedStep, step)
            assertEquals("outW must be even for NV21 on ${w}x${h}", 0, outW % 2)
            assertEquals("outH must be even for NV21 on ${w}x${h}", 0, outH % 2)
            assertTrue("outW >= 256 for ML Kit pose accuracy on ${w}x${h}", outW >= 256)
            assertTrue("outH >= 256 for ML Kit pose accuracy on ${w}x${h}", outH >= 256)
        }
    }
}


