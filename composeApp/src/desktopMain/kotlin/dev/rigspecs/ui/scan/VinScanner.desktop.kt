package dev.rigspecs.ui.scan

import androidx.compose.runtime.Composable

@Composable
actual fun rememberVinScanner(onBarcode: (String) -> Unit): (() -> Unit)? = null
