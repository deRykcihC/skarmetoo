package com.deryk.skarmetoo

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isGenerating: Boolean = false,
    val image: Bitmap? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val llmManager = LlmManager.getInstance(application)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _modelStatus = MutableStateFlow("Uninitialized")
    val modelStatus: StateFlow<String> = _modelStatus.asStateFlow()

    init {
        viewModelScope.launch {
            llmManager.uiState.collect { state ->
                when (state) {
                    is LlmManager.LlmState.Initial -> _modelStatus.value = "Please load a model"
                    is LlmManager.LlmState.Loading -> {
                        _modelStatus.value = "Loading model..."
                        _isModelReady.value = false
                    }
                    is LlmManager.LlmState.Ready -> {
                        _modelStatus.value = "Ready"
                        _isModelReady.value = true
                    }
                    is LlmManager.LlmState.Generating -> _modelStatus.value = "Generating..."
                    is LlmManager.LlmState.Error -> {
                        _modelStatus.value = "Error: ${state.message}"
                        _isModelReady.value = false
                    }
                }
            }
        }

        viewModelScope.launch {
            llmManager.partialResults.collect { (partialResult, done) ->
                val currentMessages = _messages.value.toMutableList()
                if (currentMessages.isNotEmpty() && !currentMessages.last().isUser) {
                    val lastMessage = currentMessages.last()
                    currentMessages[currentMessages.size - 1] = lastMessage.copy(
                        text = lastMessage.text + partialResult,
                        isGenerating = !done
                    )
                    _messages.value = currentMessages
                }
            }
        }
    }

    fun initializeModel(path: String, useGpu: Boolean = false) {
        llmManager.initializeModel(path, useGpu = useGpu)
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        val newMessages = _messages.value.toMutableList()
        newMessages.add(ChatMessage(text = text, isUser = true))
        newMessages.add(ChatMessage(text = "", isUser = false, isGenerating = true))
        _messages.value = newMessages

        llmManager.generateResponse(text)
    }

    fun sendImageMessage(bitmap: Bitmap, prompt: String) {
        val displayPrompt = prompt.ifBlank { "Describe this image in detail." }

        val newMessages = _messages.value.toMutableList()
        newMessages.add(ChatMessage(text = displayPrompt, isUser = true, image = bitmap))
        newMessages.add(ChatMessage(text = "", isUser = false, isGenerating = true))
        _messages.value = newMessages

        llmManager.analyzeImage(bitmap, displayPrompt)
    }

    override fun onCleared() {
        super.onCleared()
        llmManager.close()
    }
}
