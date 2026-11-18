package com.example.playintegrity.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.playintegrity.IntegrityDemoApp
import com.example.playintegrity.data.LoadOutcome
import com.example.playintegrity.network.AndroidConnectivityMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = false,
    val steps: List<String> = emptyList(),
    val outcome: LoadOutcome? = null,
) {
    /** Sensitive features are enabled ONLY on a Ready outcome. Everything else blocks them. */
    val sensitiveFeaturesEnabled: Boolean get() = outcome is LoadOutcome.Ready
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as IntegrityDemoApp).repository
    private val connectivity = AndroidConnectivityMonitor(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = UiState(loading = true)
            val outcome = repository.load(forceRefresh) { step ->
                _state.update { it.copy(steps = it.steps + step) }
            }
            _state.update { it.copy(loading = false, outcome = outcome) }

            // "Retry When Online": wait for connectivity then re-run the flow automatically.
            if (outcome is LoadOutcome.NetworkUnavailable) {
                connectivity.awaitOnline()
                load(forceRefresh)
            }
        }
    }

    fun clearAll() {
        repository.wipe()
        _state.value = UiState(steps = listOf("Cleared memory, native and encrypted cache"))
    }
}
