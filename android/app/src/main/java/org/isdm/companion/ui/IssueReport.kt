package org.isdm.companion.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val MAX_ISSUE_REPORT_IMAGES = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IssueReportSheet(
    onDismiss: () -> Unit,
    onShare: (String, List<Uri>) -> Unit,
) {
    var description by rememberSaveable { mutableStateOf("") }
    var attemptedShare by rememberSaveable { mutableStateOf(false) }
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { selected ->
        images = selected.distinct().take(MAX_ISSUE_REPORT_IMAGES)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
            Text(
                "Report an issue",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Describe what happened. You can add up to four screenshots.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What went wrong?") },
                minLines = 4,
                isError = attemptedShare && !hasIssueDescription(description),
                supportingText = {
                    if (attemptedShare && !hasIssueDescription(description)) {
                        Text("Describe the issue before sharing.")
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                    Text(if (images.isEmpty()) "Add screenshots" else "Add or replace screenshots")
                }
                if (images.isNotEmpty()) {
                    TextButton(onClick = { images = emptyList() }) { Text("Clear") }
                }
            }
            Text(
                if (images.isEmpty()) "No screenshots added" else "${images.size} of $MAX_ISSUE_REPORT_IMAGES screenshots added",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    attemptedShare = true
                    if (hasIssueDescription(description)) onShare(description.trim(), images)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Share report", fontWeight = FontWeight.Bold)
            }
        }
    }
}

internal fun hasIssueDescription(description: String): Boolean = description.isNotBlank()

internal fun issueReportShareText(description: String): String =
    "Issue report\n\n${description.trim()}"

internal fun createIssueReportShareIntent(description: String, images: List<Uri>): Intent {
    require(hasIssueDescription(description)) { "Issue description is required." }
    val shareText = issueReportShareText(description)
    if (images.isEmpty()) {
        return Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, shareText)
    }

    val streams = ArrayList(images)
    val shareClipData = ClipData(
        "Issue report screenshots",
        arrayOf("image/*"),
        ClipData.Item(images.first()),
    ).apply {
        images.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_TEXT, shareText)
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
        clipData = shareClipData
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
