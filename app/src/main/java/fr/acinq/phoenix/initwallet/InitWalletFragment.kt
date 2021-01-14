/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.phoenix.initwallet

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import fr.acinq.phoenix.R
import fr.acinq.phoenix.databinding.FragmentInitWalletBinding
import fr.acinq.phoenix.utils.Converter
import fr.acinq.phoenix.utils.Prefs
import fr.acinq.phoenix.utils.Wallet


class InitWalletFragment : Fragment() {

  private lateinit var mBinding: FragmentInitWalletBinding

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    mBinding = FragmentInitWalletBinding.inflate(inflater, container, false)
    mBinding.terms.text = Converter.html(getString(R.string.initwallet_terms))
    mBinding.terms.movementMethod = LinkMovementMethod.getInstance()
    return mBinding.root
  }

  override fun onStart() {
    super.onStart()
    val showFTUE = context?.let { Prefs.showFTUE(it) && Prefs.getLastVersionUsed(it) == 0 } ?: false
    if (showFTUE) {
      findNavController().navigate(R.id.action_init_wallet_to_ftue)
    }
    mBinding.createSeed.setOnClickListener { findNavController().navigate(R.id.action_init_wallet_to_auto_create) }
    mBinding.restoreSeed.setOnClickListener { findNavController().navigate(R.id.action_init_wallet_to_restore) }
    mBinding.settings.setOnClickListener { findNavController().navigate(R.id.global_action_any_to_settings) }
  }

  override fun onResume() {
    super.onResume()
    context?.let {
      if (Wallet.hasWalletBeenSetup(it)) {
        findNavController().navigate(R.id.global_action_any_to_startup)
      }
    }
  }
}
