package com.example.playintegrity.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.playintegrity.data.LoadOutcome

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                IntegrityScreen(
                    state = state,
                    onRetry = { vm.load() },
                    onForceRefresh = { vm.load(forceRefresh = true) },
                    onClear = vm::clearAll,
                )
            }
        }
    }
}

@Composable
private fun IntegrityScreen(
    state: UiState,
    onRetry: () -> Unit,
    onForceRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Play Integrity – Standard API", style = MaterialTheme.typography.headlineSmall)

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (state.sensitiveFeaturesEnabled) "Sensitive features: ENABLED" else "Sensitive features: BLOCKED",
                        color = if (state.sensitiveFeaturesEnabled) Color(0xFF2E7D32) else Color(0xFFC62828),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.loading) CircularProgressIndicator()
                    Text(describe(state.outcome))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, enabled = !state.loading) { Text("Retry") }
                OutlinedButton(onClick = onForceRefresh, enabled = !state.loading) { Text("Force refresh") }
                OutlinedButton(onClick = onClear, enabled = !state.loading) { Text("Clear") }
            }

            Text("Flow log", style = MaterialTheme.typography.titleSmall)
            state.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun describe(outcome: LoadOutcome?): String = when (outcome) {
    null -> "Starting…"
    is LoadOutcome.Ready ->
        "Configuration loaded from ${outcome.source} (${outcome.config.values.size} values, " +
            "ttl ${outcome.config.ttlSeconds}s). Values are held in memory/native only."
    is LoadOutcome.InitError -> "Play services / initialization error (${outcome.errorCode}): ${outcome.message}"
    LoadOutcome.NetworkUnavailable -> "No internet connection. Will retry automatically when online."
    is LoadOutcome.TemporaryError ->
        "Temporary error: ${outcome.message}" + (outcome.retryAfterSeconds?.let { " (retry after ${it}s)" } ?: "")
    is LoadOutcome.IntegrityError -> "Play Integrity error (${outcome.errorCode ?: "-"}): ${outcome.message}"
    is LoadOutcome.Blocked -> "Blocked by server: ${outcome.serverCode}. ${outcome.message}"
}
