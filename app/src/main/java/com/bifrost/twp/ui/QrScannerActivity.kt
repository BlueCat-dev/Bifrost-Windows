package com.bifrost.twp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bifrost.twp.R
import com.bifrost.twp.core.BifrostBridgeService
import com.bifrost.twp.data.ProxyRepository
import com.bifrost.twp.model.TwpLinkParser
import com.bifrost.twp.ui.theme.BifrostTheme
import com.bifrost.twp.ui.theme.DarkBackground
import com.bifrost.twp.ui.theme.NeonCyan
import com.bifrost.twp.ui.theme.TextPrimary
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : ComponentActivity() {

    private val isScanned = AtomicBoolean(false)
    private var camera: Camera? = null
    private var isTorchOn by mutableStateOf(false)

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(this, getString(R.string.msg_camera_permission_required), Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("bifrost_preferences", android.content.Context.MODE_PRIVATE)
        val lang = prefs.getString("key_app_language", "en") ?: "en"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        val repository = ProxyRepository.getInstance(applicationContext)
        setContent {
            val appLanguage by repository.appLanguageFlow.collectAsState(initial = "en")
            BifrostTheme(language = appLanguage) {
                ScannerScreen(
                    isTorchOn = isTorchOn,
                    onToggleTorch = {
                        isTorchOn = !isTorchOn
                        camera?.cameraControl?.enableTorch(isTorchOn)
                    },
                    onClose = { finish() },
                    onPreviewReady = { previewView ->
                        bindCamera(previewView)
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun bindCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        val cameraExecutor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient()

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null && !isScanned.get()) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                val rawValue = barcode.rawValue
                                if (!rawValue.isNullOrBlank() && isScanned.compareAndSet(false, true)) {
                                    handleScannedQr(rawValue)
                                    break
                                }
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleScannedQr(qrContent: String) {
        runOnUiThread {
            val repository = ProxyRepository.getInstance(applicationContext)
            val parsedConfig = TwpLinkParser.parseLink(qrContent)

            if (parsedConfig != null) {
                repository.addOrUpdateProxy(parsedConfig, makeActive = true)
                if (repository.isBridgeEnabledFlow.value) {
                    BifrostBridgeService.start(applicationContext)
                }
                Toast.makeText(this, getString(R.string.msg_proxy_saved), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, getString(R.string.msg_invalid_clipboard), Toast.LENGTH_SHORT).show()
                isScanned.set(false) // Allow retrying
            }
        }
    }
}

@Composable
fun ScannerScreen(
    isTorchOn: Boolean,
    onToggleTorch: () -> Unit,
    onClose: () -> Unit,
    onPreviewReady: (PreviewView) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "laser")
    val laserOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 240f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_y"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera View
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    onPreviewReady(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Viewfinder Cutout and Frame
        Box(
            modifier = Modifier
                .size(260.dp)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, NeonCyan, RoundedCornerShape(20.dp))
        ) {
            // Laser Scan Line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .offset(y = laserOffset.dp)
                    .background(NeonCyan)
            )
        }

        // Top Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(DarkBackground.copy(alpha = 0.6f))
            ) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_close),
                    tint = TextPrimary
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                onClick = onToggleTorch,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(DarkBackground.copy(alpha = 0.6f))
            ) {
                Icon(
                    imageVector = if (isTorchOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                    contentDescription = stringResource(R.string.action_torch),
                    tint = if (isTorchOn) NeonCyan else TextPrimary
                )
            }
        }

        // Bottom Instruction
        Text(
            text = stringResource(R.string.qr_scan_instruction),
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkBackground.copy(alpha = 0.7f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
