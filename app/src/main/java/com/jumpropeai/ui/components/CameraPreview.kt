package com.jumpropeai.ui.components

import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jumpropeai.ai.PoseFrame
import com.jumpropeai.camera.PoseAnalyzer
import java.util.concurrent.Executors

private const val TAG = "CameraPreview"

/**
 * CameraX host supporting 3 power modes:
 * - Normal (`isCameraActive = true, isPreviewEnabled = true`): Preview + ImageAnalysis (480x640).
 * - Headless Eco (`isCameraActive = true, isPreviewEnabled = false`): ImageAnalysis only (0 GPU preview).
 * - Audio-Only (`isCameraActive = false`): CameraX completely unbound (0 camera/ML Kit power!).
 */
@Composable
fun CameraPreview(
    lensFacing: Int,
    isCameraActive: Boolean,
    isPreviewEnabled: Boolean,
    isSessionRunning: Boolean,
    isDynamicEcoLowFps: Boolean,
    onPoseFrame: (PoseFrame) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentLensFacing by rememberUpdatedState(lensFacing)
    val currentSessionRunning by rememberUpdatedState(isSessionRunning)
    val currentDynamicEco by rememberUpdatedState(isDynamicEcoLowFps)
    val currentOnPoseFrame by rememberUpdatedState(onPoseFrame)

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val poseAnalyzer = remember {
        PoseAnalyzer(
            isFrontCameraProvider = { currentLensFacing == CameraSelector.LENS_FACING_FRONT },
            isSessionRunningProvider = { currentSessionRunning },
            isDynamicEcoLowFpsProvider = { currentDynamicEco },
            onPoseFrame = { frame -> currentOnPoseFrame(frame) }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            poseAnalyzer.close()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(lensFacing, isCameraActive, isPreviewEnabled, lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()

                    if (!isCameraActive) {
                        // Audio-Only Ultra-Eco Mode: Camera completely off!
                        return@addListener
                    }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(480, 640),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(analysisExecutor, poseAnalyzer)
                        }

                    val requestedSelector = CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build()

                    val cameraSelector = if (cameraProvider.hasCamera(requestedSelector)) {
                        requestedSelector
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    if (isPreviewEnabled) {
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } else {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            imageAnalysis
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind CameraX use cases", e)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    if (isCameraActive && isPreviewEnabled) {
        AndroidView(
            factory = { previewView },
            modifier = modifier
        )
    } else {
        Box(modifier = modifier.background(Color.Black))
    }
}
