package com.example.screenfaceguard.service

import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory protection status shared by the service and the UI. */
object ProtectionState {
    val running = MutableStateFlow(false)
    val message = MutableStateFlow("")
}
