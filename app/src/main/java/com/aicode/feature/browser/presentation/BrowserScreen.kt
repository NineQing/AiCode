package com.aicode.feature.browser.presentation

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aicode.R
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.X
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    browserManager: BrowserManager,
    onNavigateBack: () -> Unit,
    embedded: Boolean = false
) {
    val state by browserManager.state.collectAsStateWithLifecycle()
    var addressInput by rememberSaveable { mutableStateOf("") }
    var addressEditing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.url) {
        if (!addressEditing && state.url.isNotBlank()) {
            addressInput = state.url
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browser_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            if (embedded) FeatherIcons.X else FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(
                                if (embedded) R.string.common_close else R.string.common_back
                            )
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { scope.launch { browserManager.goBack() } },
                        enabled = !state.loading
                    ) {
                        Icon(
                            FeatherIcons.ChevronLeft,
                            contentDescription = stringResource(R.string.browser_back)
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { browserManager.goForward() } },
                        enabled = !state.loading
                    ) {
                        Icon(
                            FeatherIcons.ChevronRight,
                            contentDescription = stringResource(R.string.browser_forward)
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { browserManager.reload() } },
                        enabled = !state.loading
                    ) {
                        Icon(
                            FeatherIcons.RefreshCw,
                            contentDescription = stringResource(R.string.browser_reload)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = addressInput,
                onValueChange = {
                    addressInput = it
                    addressEditing = true
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        val input = addressInput.trim()
                        if (input.isNotEmpty()) {
                            addressEditing = false
                            scope.launch { browserManager.navigate(input) }
                        }
                    }
                ),
                trailingIcon = {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            )

            if (state.loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    factory = { context ->
                        browserManager.getOrCreateWebView(context)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (state.error != null) {
                    Text(
                        text = state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            browserManager.detachFromViewHierarchy()
        }
    }
}
