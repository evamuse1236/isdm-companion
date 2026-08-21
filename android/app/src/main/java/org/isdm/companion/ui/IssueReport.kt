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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
internal const val ISSUE_REPORT_FAB_CONTENT_DESCRIPTION = "Report issues and suggestions"

internal data class IssueReportSheetLayoutPolicy(
    val scrollable: Boolean,
    val imeSafe: Boolean,
    val systemInsetsSafe: Boolean,
)

internal fun issueReportSheetLayoutPolicy(): IssueReportSheetLayoutPolicy = IssueReportSheetLayoutPolicy(
    scrollable = true,
    imeSafe = true,
    systemInsetsSafe = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IssueReportSheet(
    onDismiss: () -> Unit,
    sending: Boolean,
    onSend: (String, String, List<Uri>) -> Unit,
) {
    var category by rememberSaveable { mutableStateOf("issue") }
    var description by rememberSaveable { mutableStateOf("") }
    var attemptedSend by rememberSaveable { mutableStateOf(false) }
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { selected ->
        images = selected.distinct().take(MAX_ISSUE_REPORT_IMAGES)
    }
    val layoutPolicy = issueReportSheetLayoutPolicy()
    val scrollState = rememberScrollState()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (layoutPolicy.scrollable) Modifier.verticalScroll(scrollState) else Modifier)
                .then(if (layoutPolicy.imeSafe) Modifier.imePadding() else Modifier)
                .then(if (layoutPolicy.systemInsetsSafe) Modifier.navigationBarsPadding() else Modifier)
                .padding(start = 22.dp, end = 22.dp, bottom = 28.dp),
        ) {
            Text(
                "Send to beta",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Send an issue or suggestion directly to the private beta dashboard.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = category == "issue",
                    onClick = { category = "issue" },
                    label = { Text("Issue") },
                    enabled = !sending,
                )
                FilterChip(
                    selected = category == "suggestion",
                    onClick = { category = "suggestion" },
                    label = { Text("Suggestion") },
                    enabled = !sending,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (category == "issue") "What happened?" else "What should change?") },
                minLines = 4,
                enabled = !sending,
                isError = attemptedSend && !hasIssueDescription(description),
                supportingText = {
                    if (attemptedSend && !hasIssueDescription(description)) {
                        Text("Add a description before sending.")
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(enabled = !sending, onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                    Text(if (images.isEmpty()) "Add screenshots" else "Add or replace screenshots")
                }
                if (images.isNotEmpty()) {
                    TextButton(enabled = !sending, onClick = { images = emptyList() }) { Text("Clear") }
                }
            }
            Text(
                if (images.isEmpty()) "No screenshots added" else "${images.size} of $MAX_ISSUE_REPORT_IMAGES screenshots added",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Text(
                "Only screenshots you select here leave this phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    attemptedSend = true
                    if (isBetaReportValid(category, description)) onSend(category, description.trim(), images)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !sending,
            ) {
                Text(if (sending) "Sending…" else "Send", fontWeight = FontWeight.Bold)
            }
        }
    }
}

internal fun hasIssueDescription(description: String): Boolean = description.isNotBlank()

internal fun isBetaReportValid(category: String, description: String): Boolean =
    category in setOf("issue", "suggestion") && hasIssueDescription(description)

internal fun isBetaEnrollmentValid(inviteCode: String, section: String, consented: Boolean): Boolean =
    inviteCode.isNotBlank() && section.isNotBlank() && consented

internal fun betaReportTitle(category: String, description: String): String {
    val label = category.replaceFirstChar { it.uppercase() }
    val firstLine = description.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
        .orEmpty().take(120)
    return "$label: $firstLine"
}

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
