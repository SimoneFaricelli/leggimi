package it.faricelli.leggimi.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.faricelli.leggimi.AppContainer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Stato condiviso a livello di activity: importazione dei documenti e messaggi. */
class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val _importing = MutableStateFlow(0)
    val importing: StateFlow<Int> = _importing

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _openDocument = Channel<String>(Channel.BUFFERED)
    val openDocument = _openDocument.receiveAsFlow()

    fun import(uri: Uri) {
        viewModelScope.launch {
            _importing.update { it + 1 }
            try {
                val id = container.library.import(uri)
                _openDocument.send(id)
            } catch (e: Exception) {
                _messages.send(e.message ?: "Importazione non riuscita.")
            } finally {
                _importing.update { it - 1 }
            }
        }
    }

    fun delete(id: String) {
        container.engine.close(id)
        viewModelScope.launch { container.library.delete(id) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container) }
        }
    }
}
