package org.isdm.companion.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
internal fun DebugLogExportAction(saving: Boolean, onSave: (Uri) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) onSave(uri)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        TextButton(
            onClick = { picker.launch("ISDM-Companion-debug-${LocalDate.now()}.txt") },
            enabled = !saving,
        ) { Text(if (saving) "Saving debug logs…" else "Save debug logs") }
        Text(
            "Logs stay on this phone for up to 7 days. Save a copy for debugging; saved copies are yours to delete.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
