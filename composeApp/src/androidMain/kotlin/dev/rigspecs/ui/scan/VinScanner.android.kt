package dev.rigspecs.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import dev.rigspecs.domain.extractVin

@Composable
actual fun rememberVinScanner(onBarcode: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        open = granted
        denied = !granted
    }
    if (open) {
        ScannerDialog(
            onVin = {
                open = false
                onBarcode(it)
            },
            onDismiss = { open = false },
        )
    }
    if (denied) {
        Dialog(onDismissRequest = { denied = false }) {
            Column(
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Camera access is off", style = MaterialTheme.typography.titleLarge)
                Text("Type the VIN instead, or allow the camera for Rig Specs in system settings.")
                Button(onClick = { denied = false }, modifier = Modifier.fillMaxWidth()) { Text("OK") }
            }
        }
    }
    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) open = true else permission.launch(Manifest.permission.CAMERA)
    }
}

/** Full-screen camera that closes on the first barcode holding something VIN-shaped. */
@Composable
private fun ScannerDialog(onVin: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnVin by rememberUpdatedState(onVin)
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                // Door-jamb labels use Code 39 or 128; newer windshield and build labels use 2D codes.
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_QR_CODE, Barcode.FORMAT_PDF417,
                )
                .build(),
        )
    }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        var done = false
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(scanner), CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED, executor) { result ->
                if (done) return@MlKitAnalyzer
                val raw = result?.getValue(scanner).orEmpty().mapNotNull { it.rawValue }.firstOrNull { extractVin(it) != null }
                if (raw != null) {
                    done = true
                    currentOnVin(raw)
                }
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        // TextureView: a SurfaceView can come up black inside a dialog window.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp).height(140.dp)
                    .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Point at the VIN barcode on the driver's door jamb or the windshield.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(12.dp),
                )
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) {
                    Text("Cancel")
                }
            }
        }
    }
}
