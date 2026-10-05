package dev.rigspecs.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberCopier(): Copier {
    val context = LocalContext.current
    return remember(context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        Copier { text -> clipboard.setPrimaryClip(ClipData.newPlainText("Rig spec", text)) }
    }
}
