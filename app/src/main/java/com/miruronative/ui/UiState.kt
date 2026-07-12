package com.miruronative.ui

import kotlinx.coroutines.CancellationException

/** Minimal load/success/error wrapper used by every screen's ViewModel. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

/**
 * Rethrows [CancellationException] so a cancelled coroutine is never reported as a failure.
 * Call first inside every `catch (e: Exception)` / `onFailure` block in a ViewModel.
 */
fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
