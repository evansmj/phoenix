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

package fr.acinq.phoenix.android

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.phoenix.android.home.HomeView
import fr.acinq.phoenix.android.home.ReadDataView
import fr.acinq.phoenix.android.home.StartupView
import fr.acinq.phoenix.android.init.CreateWalletView
import fr.acinq.phoenix.android.init.InitWallet
import fr.acinq.phoenix.android.init.RestoreWalletView
import fr.acinq.phoenix.android.payments.PaymentDetailsView
import fr.acinq.phoenix.android.payments.ReceiveView
import fr.acinq.phoenix.android.payments.SendView
import fr.acinq.phoenix.android.security.KeyState
import fr.acinq.phoenix.android.settings.*
import fr.acinq.phoenix.android.utils.Prefs
import fr.acinq.phoenix.android.utils.logger
import fr.acinq.phoenix.data.BitcoinUnit
import fr.acinq.phoenix.data.FiatCurrency
import fr.acinq.phoenix.data.walletPaymentId
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@ExperimentalMaterialApi
@Composable
fun AppView(appVM: AppViewModel) {
    val log = logger()
    val navController = rememberNavController()
    val fiatRates = application.business.currencyManager.ratesFlow.collectAsState(listOf())
    val context = LocalContext.current
    val isAmountInFiat = Prefs.getIsAmountInFiat(context).collectAsState(false)
    val fiatCurrency = Prefs.getFiatCurrency(context).collectAsState(initial = FiatCurrency.USD)
    val bitcoinUnit = Prefs.getBitcoinUnit(context).collectAsState(initial = BitcoinUnit.Sat)
    val electrumServer = Prefs.getElectrumServer(context).collectAsState(initial = null)

    CompositionLocalProvider(
        LocalBusiness provides application.business,
        LocalControllerFactory provides application.business.controllers,
        LocalNavController provides navController,
        LocalKeyState provides appVM.keyState,
        LocalExchangeRates provides fiatRates.value,
        LocalBitcoinUnit provides bitcoinUnit.value,
        LocalFiatCurrency provides fiatCurrency.value,
        LocalShowInFiat provides isAmountInFiat.value,
        LocalElectrumServer provides electrumServer.value
    ) {
        Column(
            Modifier
                .background(appBackground())
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            NavHost(navController = navController, startDestination = Screen.Startup.route) {
                composable(Screen.Startup.route) {
                    StartupView(
                        appVM,
                        onKeyAbsent = { navController.navigate(Screen.InitWallet.route) },
                        onBusinessStart = {navController.navigate(Screen.Home.route)}
                    )
                }
                composable(Screen.InitWallet.route) {
                    InitWallet()
                }
                composable(Screen.CreateWallet.route) {
                    CreateWalletView(appVM)
                }
                composable(Screen.RestoreWallet.route) {
                    RestoreWalletView(appVM)
                }
                composable(Screen.Home.route) {
                    RequireKey(appVM.keyState) {
                        HomeView(
                            onPaymentClick = { payment ->
                                navController.navigate("${Screen.PaymentDetails.route}/${payment.walletPaymentId().dbType.value}/${payment.walletPaymentId().dbId}")
                            }
                        )
                    }
                }
                composable(Screen.Receive.route) {
                    ReceiveView()
                }
                composable(Screen.ReadData.route) {
                    ReadDataView(
                        onBackClick = { navController.popBackStack() },
                        onInvoiceRead = {
                            navController.navigate("${Screen.Send.route}/${it.write()}") {
                                popUpTo(Screen.Home.route) {
                                    inclusive = true
                                    saveState = true
                                }
                            }
                        }
                    )
                }
                composable(
                    route = "${Screen.Send.route}/{request}",
                    arguments = listOf(
                        navArgument("request") { type = NavType.StringType },
                    ),
                ) {
                    val request = try {
                        PaymentRequest.read(it.arguments!!.getString("request")!!)
                    } catch (e: Exception) {
                        val context = LocalContext.current
                        LaunchedEffect(key1 = true) {
                            Toast.makeText(context, "Invalid payment request", Toast.LENGTH_SHORT).show()
                        }
                        null
                    }
                    if (request == null) {
                        // navController.navigate(Screen.Home.route)
                    } else {
                        SendView(request)
                    }
                }
                composable(
                    route = "${Screen.PaymentDetails.route}/{direction}/{id}",
                    arguments = listOf(
                        navArgument("direction") { type = NavType.LongType },
                        navArgument("id") { type = NavType.StringType }
                    ),
                ) {
                    val direction = it.arguments?.getLong("direction")
                    val id = it.arguments?.getString("id")
                    if (direction != null && id != null) {
                        PaymentDetailsView(direction = direction, id = id, onBackClick = {
                            navController.navigate(Screen.Home.route)
                        })
                    }
                }
                composable(Screen.Settings.route) {
                    SettingsView()
                }
                composable(Screen.DisplaySeed.route) {
                    SeedView(appVM)
                }
                composable(Screen.ElectrumServer.route) {
                    ElectrumView()
                }
                composable(Screen.Channels.route) {
                    ChannelsView()
                }
                composable(Screen.MutualClose.route) {
                    MutualCloseView()
                }
                composable(Screen.Preferences.route) {
                    PreferencesView()
                }
            }
        }
    }
}

@Composable
private fun RequireKey(
    keyState: KeyState,
    children: @Composable () -> Unit
) {
    if (keyState !is KeyState.Present) {
        logger().warning { "rejecting access to screen with keyState=$keyState" }
        navController.navigate(Screen.Startup)
        Text("redirecting...")
    } else {
        logger().debug { "access to screen granted" }
        children()
    }
}