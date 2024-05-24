/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.phoenix.android.payments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.acinq.lightning.TrampolineFees
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.phoenix.android.LocalBitcoinUnit
import fr.acinq.phoenix.android.R
import fr.acinq.phoenix.android.business
import fr.acinq.phoenix.android.components.*
import fr.acinq.phoenix.android.userPrefs
import fr.acinq.phoenix.android.utils.Converter.toPrettyString
import fr.acinq.phoenix.android.utils.safeLet
import fr.acinq.phoenix.controllers.payments.Scan
import fr.acinq.phoenix.utils.extensions.isAmountlessTrampoline

@Composable
fun SendBolt11PaymentView(
    invoice: Bolt11Invoice,
    trampolineFees: TrampolineFees?,
    onBackClick: () -> Unit,
    onPayClick: (Scan.Intent.Bolt11InvoiceFlow.SendBolt11Invoice) -> Unit
) {
    val context = LocalContext.current
    val balance = business.balanceManager.balance.collectAsState(null).value
    val prefBitcoinUnit = LocalBitcoinUnit.current

    val requestedAmount = invoice.amount
    var amount by remember { mutableStateOf(requestedAmount) }
    val amountErrorMessage: String = remember(amount) {
        val currentAmount = amount
        when {
            currentAmount == null -> ""
            balance != null && currentAmount > balance -> context.getString(R.string.send_error_amount_over_balance)
            requestedAmount != null && currentAmount < requestedAmount -> context.getString(
                R.string.send_error_amount_below_requested,
                (requestedAmount).toPrettyString(prefBitcoinUnit, withUnit = true)
            )
            requestedAmount != null && currentAmount > requestedAmount * 2 -> context.getString(
                R.string.send_error_amount_overpaying,
                (requestedAmount * 2).toPrettyString(prefBitcoinUnit, withUnit = true)
            )
            else -> ""
        }
    }
    val isOverpaymentEnabled by userPrefs.getIsOverpaymentEnabled.collectAsState(initial = false)

    SplashLayout(
        header = { BackButtonWithBalance(onBackClick = onBackClick, balance = balance) },
        topContent = {
            var inputForcedAmount by remember { mutableStateOf(requestedAmount) }
            AmountHeroInput(
                initialAmount = inputForcedAmount,
                enabled = requestedAmount == null || isOverpaymentEnabled,
                onAmountChange = { newAmount -> amount = newAmount?.amount },
                validationErrorMessage = amountErrorMessage,
                inputTextSize = 42.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (requestedAmount != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        text = "5%",
                        icon = R.drawable.ic_minus_circle,
                        onClick = {
                            val newAmount = amount?.let {
                                it - requestedAmount * 0.05
                            }?.coerceAtLeast(requestedAmount) ?: requestedAmount

                            inputForcedAmount = newAmount
                            amount = newAmount
                        },
                        enabled = amount?.let { it > requestedAmount } ?: false,
                        padding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        space = 6.dp,
                        textStyle = MaterialTheme.typography.caption.copy(fontSize = 14.sp),
                        shape = CircleShape,
                    )
                    Button(
                        text = "5%",
                        icon = R.drawable.ic_plus_circle,
                        onClick = {
                            val newAmount = amount?.let {
                                it + requestedAmount * 0.05
                            }?.coerceAtMost(requestedAmount * 2) ?: requestedAmount

                            inputForcedAmount = newAmount
                            amount = newAmount
                        },
                        enabled = amount?.let { it < requestedAmount * 2 } ?: false,
                        padding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        space = 6.dp,
                        textStyle = MaterialTheme.typography.caption.copy(fontSize = 14.sp),
                        shape = CircleShape,
                    )
                }
            }
        }
    ) {
        invoice.description?.takeIf { it.isNotBlank() }?.let {
            SplashLabelRow(label = stringResource(R.string.send_description_label)) {
                Text(text = it)
            }
        }
        SplashLabelRow(label = stringResource(R.string.send_destination_label), icon = R.drawable.ic_zap) {
            SelectionContainer {
                Text(text = invoice.nodeId.toHex(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (invoice.isAmountlessTrampoline()) {
            SplashLabelRow(label = "", helpMessage = stringResource(id = R.string.send_trampoline_amountless_warning_details)) {
                Text(text = stringResource(id = R.string.send_trampoline_amountless_warning_label))
            }
        }
        SplashLabelRow(label = stringResource(id = R.string.send_trampoline_fee_label)) {
            val amt = amount
            if (amt == null) {
                Text(stringResource(id = R.string.send_trampoline_fee_no_amount), style = MaterialTheme.typography.caption)
            } else if (trampolineFees == null) {
                Text(stringResource(id = R.string.send_trampoline_fee_loading))
            } else {
                AmountWithFiatRowView(amount = trampolineFees.calculateFees(amt))
            }
        }
        Spacer(modifier = Modifier.height(36.dp))
        val mayDoPayments by business.peerManager.mayDoPayments.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledButton(
                text = if (!mayDoPayments) stringResource(id = R.string.send_connecting_button) else stringResource(id = R.string.send_pay_button),
                icon = R.drawable.ic_send,
                enabled = mayDoPayments && amount != null && amountErrorMessage.isBlank() && trampolineFees != null,
            ) {
                safeLet(amount, trampolineFees) { amt, fees ->
                    onPayClick(Scan.Intent.Bolt11InvoiceFlow.SendBolt11Invoice(invoice = invoice, amount = amt, trampolineFees = fees))
                }
            }
        }
    }
}
