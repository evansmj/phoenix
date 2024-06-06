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

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.acinq.phoenix.android.AppViewModel
import fr.acinq.phoenix.android.R
import fr.acinq.phoenix.android.components.Button
import fr.acinq.phoenix.android.components.ProgressView
import fr.acinq.phoenix.android.components.VSeparator
import fr.acinq.phoenix.android.userPrefs
import fr.acinq.phoenix.android.utils.BiometricsHelper
import fr.acinq.phoenix.android.utils.findActivity
import fr.acinq.phoenix.android.utils.horizon
import fr.acinq.phoenix.android.utils.mutedBgColor
import fr.acinq.phoenix.android.utils.safeLet

/**
 * Screen shown when authentication through biometrics or PIN is required, depending on the user settings.
 *
 */
@Composable
fun LockPrompt(
    appViewModel: AppViewModel
) {
    val context = LocalContext.current

    val isBiometricLockEnabledState by userPrefs.getIsBiometricLockEnabled.collectAsState(initial = null)
    val isCustomPinLockEnabledState by userPrefs.getIsCustomPinLockEnabled.collectAsState(initial = null)
    var showPinLockDialog by rememberSaveable { mutableStateOf(false) }

    safeLet(isBiometricLockEnabledState, isCustomPinLockEnabledState) { isBiometricLockEnabled, isCustomPinEnabled ->

        val promptBiometricLock = {
            val promptInfo = BiometricPrompt.PromptInfo.Builder().apply {
                setTitle(context.getString(R.string.authprompt_title))
                setAllowedAuthenticators(BiometricsHelper.authCreds)
            }.build()
            BiometricsHelper.getPrompt(
                activity = context.findActivity(),
                onSuccess = { appViewModel.unlockScreen() },
                onFailure = { appViewModel.lockScreen() },
                onCancel = { }
            ).authenticate(promptInfo)
        }

        LaunchedEffect(key1 = true) {
            if (isBiometricLockEnabled) {
                promptBiometricLock()
            } else if (isCustomPinEnabled) {
                showPinLockDialog = true
            } else {
                appViewModel.unlockScreen()
            }
        }

        if (showPinLockDialog) {
            CheckPinFlow(
                onCancel = { showPinLockDialog = false },
                onPinValid = { appViewModel.unlockScreen() }
            )
        }

        Column(modifier = Modifier.padding(horizontal = 32.dp)) {
            Spacer(modifier = Modifier.height(64.dp))
            if (isBiometricLockEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    text = stringResource(id = R.string.startup_manual_unlock_biometrics_button),
                    icon = R.drawable.ic_fingerprint,
                    onClick = promptBiometricLock,
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colors.surface,
                    shape = CircleShape
                )
            }
            if (isCustomPinEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    text = stringResource(id = R.string.startup_manual_unlock_pin_button),
                    icon = R.drawable.ic_pin,
                    onClick = { showPinLockDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colors.surface,
                    shape = CircleShape
                )
            }
        }
    } ?: ProgressView(text = stringResource(id = R.string.utils_loading_prefs))
}