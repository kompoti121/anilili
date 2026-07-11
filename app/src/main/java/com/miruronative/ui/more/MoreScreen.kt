package com.miruronative.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miruronative.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("More") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                stringResource(R.string.app_name),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Native Android client",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Metadata from AniList. Streaming resolved on-device through a Cloudflare-cleared " +
                    "WebView session. This app links to third-party media it does not host.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Text("Version 0.1.0", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}
