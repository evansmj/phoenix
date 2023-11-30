/*
 * Copyright 2021 ACINQ SAS
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


package fr.acinq.phoenix.android.settings


import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.phoenix.android.*
import fr.acinq.phoenix.android.R
import fr.acinq.phoenix.android.components.*
import fr.acinq.phoenix.android.components.feedback.ErrorMessage
import fr.acinq.phoenix.android.components.mvi.MVIView
import fr.acinq.phoenix.android.utils.*
import fr.acinq.phoenix.android.utils.datastore.UserPrefs
import fr.acinq.phoenix.controllers.config.ElectrumConfiguration
import fr.acinq.phoenix.data.ElectrumConfig
import fr.acinq.secp256k1.Hex
import io.ktor.util.network.*
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.text.NumberFormat

@Composable
fun ElectrumView() {
    val log = logger("ElectrumView")
    val nc = navController
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefElectrumServer = LocalElectrumServer.current
    var showCustomServerDialog by rememberSaveable { mutableStateOf(false) }

    DefaultScreenLayout {
        DefaultScreenHeader(
            onBackClick = { nc.popBackStack() },
            title = stringResource(id = R.string.electrum_title),
        )
        Card(internalPadding = PaddingValues(16.dp)) {
            Text(text = stringResource(R.string.electrum_about))
        }
        MVIView(CF::electrumConfiguration) { model, postIntent ->
            Card {
                val config = model.configuration
                if (showCustomServerDialog) {
                    ElectrumServerDialog(
                        initialAddress = prefElectrumServer,
                        onConfirm = { address ->
                            scope.launch {
                                UserPrefs.saveElectrumServer(context, address)
                                postIntent(ElectrumConfiguration.Intent.UpdateElectrumServer(address))
                                showCustomServerDialog = false
                            }
                        },
                        onDismiss = { showCustomServerDialog = false }
                    )
                }

                // -- connection detail
                val connection = model.connection
                SettingInteractive(
                    title = when {
                        connection is Connection.ESTABLISHED -> {
                            stringResource(id = R.string.electrum_connection_connected, "${model.currentServer?.host}:${model.currentServer?.port}")
                        }
                        connection is Connection.ESTABLISHING && config is ElectrumConfig.Random -> {
                            stringResource(id = R.string.electrum_connection_connecting_to_random, "${model.currentServer?.host}:${model.currentServer?.port}")
                        }
                        connection is Connection.ESTABLISHING && config is ElectrumConfig.Custom -> {
                            stringResource(id = R.string.electrum_connection_connecting_to_custom, config.server.host)
                        }
                        connection is Connection.CLOSED && config is ElectrumConfig.Custom -> {
                            stringResource(id = R.string.electrum_connection_closed_with_custom, config.server.host)
                        }
                        else -> {
                            stringResource(id = R.string.electrum_connection_closed_with_random)
                        }
                    },
                    description = {
                        when (config) {
                            is ElectrumConfig.Custom -> {
                                if (connection is Connection.CLOSED && connection.isBadCertificate()) {
                                    Text(
                                        text = stringResource(id = R.string.electrum_description_bad_certificate),
                                        style = MaterialTheme.typography.subtitle2.copy(color = negativeColor)
                                    )
                                } else {
                                    Text(text = stringResource(id = R.string.electrum_description_custom))
                                }
                            }
                            else -> Unit
                        }
                    },
                    icon = R.drawable.ic_server,
                    iconTint = when (connection) {
                        is Connection.ESTABLISHED -> positiveColor
                        is Connection.ESTABLISHING -> orange
                        else -> negativeColor
                    },
                    maxTitleLines = 1
                ) { showCustomServerDialog = true }
            }

            Card {
                // block height
                if (model.blockHeight > 0) {
                    val height = remember { NumberFormat.getInstance().format(model.blockHeight) }
                    Setting(title = stringResource(id = R.string.electrum_block_height_label), description = height)
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ElectrumServerDialog(
    initialAddress: ServerAddress?,
    onConfirm: (ServerAddress?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardManager = LocalSoftwareKeyboardController.current

    var useCustomServer by rememberSaveable { mutableStateOf(initialAddress != null) }
    var address by rememberSaveable { mutableStateOf(initialAddress?.run { "$host:$port" } ?: "") }
    val host = remember(address) { address.trim().substringBeforeLast(":").takeIf { it.isNotBlank() } }
    val port = remember(address) { address.trim().substringAfterLast(":").toIntOrNull() ?: 50002 }
    val isOnionHost = remember(address) { host?.endsWith(".onion") ?: false }

    var addressError by rememberSaveable { mutableStateOf(false) }

    val vm = viewModel<ElectrumDialogViewModel>()

    Dialog(
        onDismiss = onDismiss,
        buttons = null
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(16.dp))
                Checkbox(
                    text = stringResource(id = R.string.electrum_dialog_checkbox),
                    checked = useCustomServer,
                    onCheckedChange = {
                        addressError = false
                        if (useCustomServer != it) {
                            vm.state = ElectrumDialogViewModel.CertificateCheckState.Init
                        }
                        useCustomServer = it
                    },
                    enabled = vm.state !is ElectrumDialogViewModel.CertificateCheckState.Checking
                )
                TextInput(
                    modifier = Modifier.fillMaxWidth(),
                    text = address,
                    onTextChange = {
                        addressError = false
                        if (address != it) {
                            vm.state = ElectrumDialogViewModel.CertificateCheckState.Init
                        }
                        address = it
                    },
                    maxLines = 4,
                    staticLabel = stringResource(id = R.string.electrum_dialog_input),
                    enabled = useCustomServer && vm.state !is ElectrumDialogViewModel.CertificateCheckState.Checking,
                    errorMessage = if (addressError) stringResource(id = R.string.electrum_dialog_invalid_input) else null
                )
                if (isOnionHost) {
                    TextWithIcon(
                        text = stringResource(id = R.string.electrum_connection_dialog_onion_port),
                        textStyle = MaterialTheme.typography.subtitle2,
                        icon = R.drawable.ic_info,
                    )
                } else {
                    TextWithIcon(
                        text = stringResource(id = R.string.electrum_connection_dialog_tls_port),
                        textStyle = MaterialTheme.typography.subtitle2,
                        icon = R.drawable.ic_info,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            when (val state = vm.state) {
                ElectrumDialogViewModel.CertificateCheckState.Init, is ElectrumDialogViewModel.CertificateCheckState.Failure -> {
                    if (state is ElectrumDialogViewModel.CertificateCheckState.Failure) {
                        ErrorMessage(
                            header = stringResource(R.string.electrum_dialog_cert_failure),
                            details = when (state.e) {
                                is UnresolvedAddressException -> stringResource(R.string.electrum_dialog_cert_unresolved)
                                else -> state.e.message ?: state.e.javaClass.simpleName
                            },
                            alignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    Row(Modifier.align(Alignment.End)) {
                        Button(onClick = onDismiss, text = stringResource(id = R.string.btn_cancel))
                        Button(
                            onClick = {
                                keyboardManager?.hide()
                                if (useCustomServer) {
                                    val host = host
                                    if (address.matches("""(.*):*(\d*)""".toRegex()) && host != null) {
                                        scope.launch {
                                            if (isOnionHost) {
                                                onConfirm(ServerAddress(host, port, TcpSocket.TLS.DISABLED))
                                            } else {
                                                vm.checkCertificate(host, port, onCertificateValid = onConfirm)
                                            }
                                        }
                                    } else {
                                        addressError = true
                                    }
                                } else {
                                    onConfirm(null)
                                }
                            },
                            enabled = !addressError,
                            text = if (useCustomServer) {
                                stringResource(id = R.string.electrum_dialog_cert_check_button)
                            } else {
                                stringResource(id = R.string.btn_ok)
                            },
                        )
                    }
                }
                ElectrumDialogViewModel.CertificateCheckState.Checking -> {
                    Row(Modifier.align(Alignment.End)) {
                        ProgressView(text = stringResource(id = R.string.electrum_dialog_cert_checking))
                    }
                }
                is ElectrumDialogViewModel.CertificateCheckState.Rejected -> {
                    val cert = state.certificate
                    Column(
                        Modifier
                            .background(mutedBgColor)
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        TextWithIcon(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            icon = R.drawable.ic_alert_triangle,
                            text = stringResource(R.string.electrum_dialog_cert_header),
                            textStyle = MaterialTheme.typography.body2
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            text = stringResource(id = R.string.electrum_dialog_cert_copy),
                            padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            space = 8.dp,
                            textStyle = MaterialTheme.typography.body1.copy(fontSize = 12.sp),
                            onClick = { copyToClipboard(context, "-----BEGIN CERTIFICATE-----\n${String(Base64.encode(cert.encoded, Base64.DEFAULT), Charsets.US_ASCII)}-----END CERTIFICATE-----") }
                        )
                        Spacer(Modifier.height(12.dp))
                        CertDetail(
                            label = stringResource(id = R.string.electrum_dialog_cert_sha1),
                            value = Hex.encode(MessageDigest.getInstance("SHA-1").digest(cert.encoded)),
                        )
                        CertDetail(
                            label = stringResource(id = R.string.electrum_dialog_cert_sha256),
                            value = Hex.encode(MessageDigest.getInstance("SHA-256").digest(cert.encoded)),
                        )
                        if (cert is X509Certificate) {
                            CertDetail(
                                label = stringResource(id = R.string.electrum_dialog_cert_issuer),
                                value = cert.issuerX500Principal.name.substringAfter("CN=").substringBefore(","),
                            )
                            CertDetail(
                                label = stringResource(id = R.string.electrum_dialog_cert_subject),
                                value = cert.issuerX500Principal.name.substringAfter("CN=").substringBefore(","),
                            )
                            CertDetail(
                                label = stringResource(id = R.string.electrum_dialog_cert_expiration),
                                value = DateFormat.getDateTimeInstance().format(cert.notAfter),
                            )
                        }
                    }
                    Row(Modifier.align(Alignment.End)) {
                        Button(
                            text = stringResource(id = R.string.btn_cancel),
                            space = 8.dp,
                            onClick = onDismiss
                        )
                        Button(
                            text = stringResource(id = R.string.electrum_dialog_cert_accept),
                            icon = R.drawable.ic_check_circle,
                            iconTint = positiveColor,
                            space = 8.dp,
                            onClick = { onConfirm(ServerAddress(state.host, state.port, TcpSocket.TLS.PINNED_PUBLIC_KEY(Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)))) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CertDetail(label: String, value: String) {
    Text(text = label, style = MaterialTheme.typography.body2)
    Spacer(modifier = Modifier.height(2.dp))
    Text(text = value, style = monoTypo, maxLines = 1)
    Spacer(modifier = Modifier.height(4.dp))
}
