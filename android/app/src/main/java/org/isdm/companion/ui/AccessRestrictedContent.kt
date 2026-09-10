package org.isdm.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.isdm.companion.platform.BetaAccessStatus

internal fun betaEnrolledAccessBlocked(enrolled: Boolean, status: BetaAccessStatus) =
    enrolled && status != BetaAccessStatus.ALLOWED

@Composable
internal fun AccessRestrictedContent(
    status: BetaAccessStatus,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (status) {
        BetaAccessStatus.SUSPENDED -> "Companion access paused"
        BetaAccessStatus.INVALID_INSTALLATION -> "Beta enrollment changed"
        BetaAccessStatus.UPDATE_REQUIRED -> "An app update is needed"
        else -> "Connect to verify access"
    }
    val body = when (status) {
        BetaAccessStatus.SUSPENDED -> "The app owner has paused your access. Contact the owner, then check again after access is restored."
        BetaAccessStatus.INVALID_INSTALLATION -> "This installation is no longer enrolled. Contact the app owner to restore this device."
        BetaAccessStatus.UPDATE_REQUIRED -> "Install the latest beta from the app owner, then check again."
        else -> "Your saved LMS login is still here. Connect to the internet and check again to continue."
    }
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Check access again") }
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out of LMS") }
    }
}
