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

package fr.acinq.phoenix.receive

import android.content.*
import android.os.Bundle
import android.os.PowerManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.addCallback
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.`NORMAL$`
import fr.acinq.eclair.channel.`OFFLINE$`
import fr.acinq.eclair.channel.`WAIT_FOR_FUNDING_CONFIRMED$`
import fr.acinq.eclair.payment.PaymentReceived
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.wire.SwapInResponse
import fr.acinq.phoenix.BaseFragment
import fr.acinq.phoenix.NavGraphMainDirections
import fr.acinq.phoenix.R
import fr.acinq.phoenix.databinding.FragmentReceiveBinding
import fr.acinq.phoenix.paymentdetails.PaymentDetailsFragment
import fr.acinq.phoenix.utils.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scala.Option

class ReceiveFragment : BaseFragment() {

  override val log: Logger = LoggerFactory.getLogger(this::class.java)

  private lateinit var mBinding: FragmentReceiveBinding
  private lateinit var model: ReceiveViewModel
  private lateinit var unitList: List<String>
  private var powerSavingReceiver: BroadcastReceiver? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    activity?.onBackPressedDispatcher?.addCallback(this) {
      handleBackAction()
    }
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    mBinding = FragmentReceiveBinding.inflate(inflater, container, false)
    mBinding.lifecycleOwner = this
    return mBinding.root
  }

  override fun onActivityCreated(savedInstanceState: Bundle?) {
    super.onActivityCreated(savedInstanceState)
    model = ViewModelProvider(this).get(ReceiveViewModel::class.java)
    mBinding.model = model
    mBinding.appModel = app

    context?.let {
      unitList = listOf(SatUnit.code(), BitUnit.code(), MBtcUnit.code(), BtcUnit.code(), Prefs.getFiatCurrency(it))
      ArrayAdapter(it, android.R.layout.simple_spinner_item, unitList).also { adapter ->
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        mBinding.amountUnit.adapter = adapter
      }
      val unit = Prefs.getCoinUnit(it)
      mBinding.amountUnit.setSelection(unitList.indexOf(unit.code()))
    }

    model.invoice.observe(viewLifecycleOwner, {
      if (it != null) {
        model.generateQrCodeBitmap()
        mBinding.rawInvoice.text = if (it.second == null) PaymentRequest.write(it.first) else it.second
      } else {
        mBinding.rawInvoice.text = ""
      }
    })

    model.bitmap.observe(viewLifecycleOwner, { bitmap ->
      if (bitmap != null) {
        mBinding.qrImage.setImageBitmap(bitmap)
        model.invoice.value?.let {
          if (it.second != null) {
            model.state.value = SwapInState.DONE
          } else {
            model.state.value = PaymentGenerationState.DONE
          }
        }
      }
    })

    app.pendingSwapIns.observe(viewLifecycleOwner, {
      model.invoice.value?.let { invoice ->
        // if user is swapping in and a payment is incoming on this address, move to main
        if (invoice.second != null && invoice.second != null && model.state.value == SwapInState.DONE) {
          val currentOnchainAddress = invoice.second
          if (it.keys.contains(currentOnchainAddress)) {
            findNavController().navigate(R.id.action_receive_to_main)
          }
        }
      }
    })

    appContext()?.payToOpenSettings?.value?.minFunding?.let { minFunding ->
      context?.let { ctx ->
        val min = Converter.printAmountPretty(minFunding, ctx, withUnit = true)
        mBinding.minFundingPayToOpen.text = Converter.html(getString(R.string.receive_min_amount_pay_to_open, min))
        mBinding.minFundingSwapIn.text = Converter.html(getString(R.string.receive_min_amount_swap_in, min))
      }
      checkMinFunding(minFunding)
    }

    context?.let { mBinding.descValue.setText(Prefs.getDefaultPaymentDescription(it)) }
  }

  override fun onStart() {
    super.onStart()
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }

    mBinding.amountValue.addTextChangedListener(object : TextWatcher {
      override fun afterTextChanged(s: Editable?) = Unit

      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        refreshConversionDisplay()
      }
    })

    mBinding.amountUnit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onNothingSelected(parent: AdapterView<*>?) = Unit

      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        refreshConversionDisplay()
      }
    }

    mBinding.generateButton.setOnClickListener {
      generatePaymentRequest()
    }

    mBinding.copyButton.setOnClickListener {
      copyInvoice()
    }

    mBinding.qrImage.setOnClickListener {
      copyInvoice()
    }

    mBinding.shareButton.setOnClickListener {
      model.invoice.value?.let {
        val source = if (model.invoice.value!!.second != null) "bitcoin:${model.invoice.value!!.second}" else "lightning:${PaymentRequest.write(model.invoice.value!!.first)}"
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = "text/plain"
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.receive_share_subject))
        shareIntent.putExtra(Intent.EXTRA_TEXT, source)
        startActivity(Intent.createChooser(shareIntent, getString(R.string.receive_share_title)))
      }
    }

    mBinding.editButton.setOnClickListener {
      model.state.value = PaymentGenerationState.EDITING_REQUEST
    }

    mBinding.swapInButton.setOnClickListener {
      val swapInFee = 100 * (appContext()?.swapInSettings?.value?.feePercent ?: Constants.DEFAULT_SWAP_IN_SETTINGS.feePercent)
      AlertHelper.build(layoutInflater, getString(R.string.receive_swap_in_disclaimer_title),
        Converter.html(getString(R.string.receive_swap_in_disclaimer_message, String.format("%.2f", swapInFee))))
        .setPositiveButton(R.string.utils_proceed) { _, _ -> generateSwapIn() }
        .setNegativeButton(R.string.btn_cancel, null)
        .show()
    }

    mBinding.withdrawButton.setOnClickListener {
      findNavController().navigate(R.id.global_action_any_to_read_input)
    }

    mBinding.actionBar.setOnBackAction { handleBackAction() }

    if (model.state.value == PaymentGenerationState.INIT) {
      generatePaymentRequest()
    }

    // listen to power saving mode
    context?.let {
      val powerManager = it.getSystemService(Context.POWER_SERVICE) as PowerManager
      powerSavingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          log.info("power saving ? ${powerManager.isPowerSaveMode}")
          model.isPowerSavingMode.value = powerManager.isPowerSaveMode
        }
      }
      it.registerReceiver(powerSavingReceiver, IntentFilter("android.os.action.POWER_SAVE_MODE_CHANGED"))
    }
  }

  override fun onStop() {
    super.onStop()
    EventBus.getDefault().unregister(this)
    context?.let {
      if (powerSavingReceiver != null) {
        it.unregisterReceiver(powerSavingReceiver!!)
      }
    }
  }

  private fun handleBackAction() {
    if (model.state.value == PaymentGenerationState.EDITING_REQUEST) {
      model.state.value = PaymentGenerationState.DONE
    } else {
      findNavController().navigate(R.id.action_receive_to_main)
    }
  }

  private fun copyInvoice() {
    context?.run {
      val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      val source = model.invoice.value!!.second ?: PaymentRequest.write(model.invoice.value!!.first)
      clipboard.setPrimaryClip(ClipData.newPlainText("Payment request", source))
      Toast.makeText(this, R.string.utils_copied, Toast.LENGTH_SHORT).show()
    }
  }

  private fun checkMinFunding(minFunding: Satoshi) {
    lifecycleScope.launch(Dispatchers.Default + CoroutineExceptionHandler { _, exception ->
      log.error("could not list channels for min funding check: ", exception)
    }) {
      val channels = app.requireService.getChannels().filter { it.state() is `NORMAL$` || it.state() is `WAIT_FOR_FUNDING_CONFIRMED$` || it.state() is `OFFLINE$` }
      model.showMinFundingPayToOpen.postValue(channels.isEmpty() && minFunding.`$greater`(Satoshi(0)))
      model.showMinFundingSwapIn.postValue(minFunding.`$greater`(Satoshi(0)))
    }
  }

  private fun generatePaymentRequest() {
    lifecycleScope.launch(CoroutineExceptionHandler { _, exception ->
      log.error("error when generating payment request: ", exception)
      model.state.value = PaymentGenerationState.ERROR
    }) {
      Wallet.hideKeyboard(context, mBinding.amountValue)
      model.state.value = PaymentGenerationState.IN_PROGRESS
      val invoice = app.requireService.generatePaymentRequest(mBinding.descValue.text.toString(), extractAmount())
      model.invoice.value = Pair(invoice, null)
    }
  }

  private fun generateSwapIn() {
    lifecycleScope.launch(CoroutineExceptionHandler { _, exception ->
      log.error("error when generating swap in: ", exception)
      model.state.value = SwapInState.ERROR
    }) {
      Wallet.hideKeyboard(context, mBinding.amountValue)
      model.state.value = SwapInState.IN_PROGRESS
      app.requireService.sendSwapIn()
    }
  }

  private fun refreshConversionDisplay() {
    context?.let {
      try {
        val amount = extractAmount()
        val unit = mBinding.amountUnit.selectedItem.toString()
        if (unit == Prefs.getFiatCurrency(it)) {
          mBinding.amountConverted.text = getString(R.string.utils_converted_amount, Converter.printAmountPretty(amount.get(), it, withUnit = true))
        } else {
          mBinding.amountConverted.text = getString(R.string.utils_converted_amount, Converter.printFiatPretty(it, amount.get(), withUnit = true))
        }
      } catch (e: Exception) {
        log.info("could not extract amount: ${e.message}")
        mBinding.amountConverted.text = ""
      }
    }
  }

  private fun extractAmount(): Option<MilliSatoshi> {
    val unit = mBinding.amountUnit.selectedItem
    val amount = mBinding.amountValue.text.toString()
    return context?.run {
      when (unit) {
        null -> Option.empty()
        Prefs.getFiatCurrency(this) -> Option.apply(Converter.convertFiatToMsat(this, amount))
        else -> Converter.string2Msat_opt(amount, unit.toString())
      }
    } ?: Option.empty()
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  fun handleEvent(event: PaymentReceived) {
    model.invoice.value?.let {
      if (event.paymentHash() == it.first.paymentHash()) {
        val action = NavGraphMainDirections.globalActionAnyToPaymentDetails(PaymentDetailsFragment.INCOMING, event.paymentHash().toString(), fromEvent = true)
        findNavController().navigate(action)
      }
    }
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  fun handleEvent(event: SwapInResponse) {
    model.invoice.value = model.invoice.value?.copy(second = event.bitcoinAddress())
  }
}
