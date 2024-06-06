/*
 * Copyright 2024 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.phoenix.android.components.screenlock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.phoenix.android.PhoenixApplication
import fr.acinq.phoenix.android.components.screenlock.PinDialog.PIN_LENGTH
import fr.acinq.phoenix.android.security.EncryptedPin
import fr.acinq.phoenix.android.utils.datastore.PinCodeState
import fr.acinq.phoenix.android.utils.datastore.UserPrefsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Tracks the state of the PIN dialog UI. For the state of the
 * PIN lock, see [UserPrefsRepository.PREFS_CUSTOM_PIN_STATE].
 */
sealed class CheckPinFlowState {
    data object CanType : CheckPinFlowState()
    data object Checking : CheckPinFlowState()
    data object MalformedInput: CheckPinFlowState()
    sealed class IncorrectPin: CheckPinFlowState() {
        data object Forced: IncorrectPin()
        data object CanOverwrite: IncorrectPin()
    }
    data class Locked(val timeToWait: Long): CheckPinFlowState()
    data class Error(val cause: Throwable) : CheckPinFlowState()
}

class CheckPinFlowViewModel(private val userPrefsRepository: UserPrefsRepository) : ViewModel() {
    private val log = LoggerFactory.getLogger(this::class.java)
    var state by mutableStateOf<CheckPinFlowState>(CheckPinFlowState.Checking)
        private set

    var pinInput by mutableStateOf("")

    init {
        viewModelScope.launch {
            val pinCodeState = userPrefsRepository.getPinCodeState.first()
            if (pinCodeState is PinCodeState.Locked) {
                refreshLockState(pinCodeState)
            } else {
                state = CheckPinFlowState.CanType
            }
            val countdownFlow = flow {
                while (true) {
                    delay(1_000)
                    emit(Unit)
                }
            }
            combine(countdownFlow, userPrefsRepository.getPinCodeState) { _, state ->
                state
            }.collect {
                if (it is PinCodeState.Locked) {
                    refreshLockState(it)
                }
            }
        }
    }

    private fun refreshLockState(pinCodeState: PinCodeState.Locked) {
        val timeToWait = (pinCodeState.canBeUnlockedAt - currentTimestampMillis()).coerceAtLeast(0) / 1000
        if (timeToWait > 0) {
            state = CheckPinFlowState.Locked(timeToWait)
        } else if (state is CheckPinFlowState.Locked || state is CheckPinFlowState.Checking || state is CheckPinFlowState.IncorrectPin.CanOverwrite) {
            state = CheckPinFlowState.CanType
        }
    }

    fun checkPinAndSaveOutcome(context: Context, pin: String, onPinValid: () -> Unit) {
        if (state is CheckPinFlowState.Checking) return
        state = CheckPinFlowState.Checking

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (pin.isBlank() || pin.length != PIN_LENGTH) {
                    log.debug("malformed pin")
                    state = CheckPinFlowState.MalformedInput
                    delay(1500)
                    if (state is CheckPinFlowState.MalformedInput) {
                        state = CheckPinFlowState.CanType
                    }
                }

                val expected = EncryptedPin.getPinFromDisk(context)
                if (pin == expected) {
                    log.debug("valid pin")
                    delay(300)
                    pinInput = ""
                    viewModelScope.launch(Dispatchers.Main) {
                        onPinValid()
                    }
                    userPrefsRepository.savePinCodeState(PinCodeState.Unlocked(currentTimestampMillis()))
                } else {
                    log.debug("incorrect pin")
                    state = CheckPinFlowState.IncorrectPin.Forced
                    delay(1000)
                    pinInput = ""
                    state = CheckPinFlowState.IncorrectPin.CanOverwrite
                    val previousPinState = userPrefsRepository.getPinCodeState.first()
                    userPrefsRepository.savePinCodeState(
                        PinCodeState.Locked(
                            attemptCount = when (previousPinState) {
                                is PinCodeState.Locked -> previousPinState.attemptCount + 1
                                is PinCodeState.Unlocked -> 0
                            },
                            failedAt = currentTimestampMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                log.error("error when checking pin code: ", e)
                state = CheckPinFlowState.Error(e)
                delay(1500)
                pinInput = ""
                if (state is CheckPinFlowState.Error) {
                    state = CheckPinFlowState.CanType
                }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as? PhoenixApplication)
                return CheckPinFlowViewModel(application.userPrefs) as T
            }
        }
    }
}