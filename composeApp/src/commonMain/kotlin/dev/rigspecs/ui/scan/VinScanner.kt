package dev.rigspecs.ui.scan

import androidx.compose.runtime.Composable

/**
 * Returns a function that opens the camera to read a VIN barcode and hands its raw text to
 * [onBarcode], or null where the platform has no scanner (desktop types the VIN). Any UI the
 * scanner needs is emitted where this is called.
 */
@Composable
expect fun rememberVinScanner(onBarcode: (String) -> Unit): (() -> Unit)?
