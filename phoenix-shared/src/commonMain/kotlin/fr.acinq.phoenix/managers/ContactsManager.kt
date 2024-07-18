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

package fr.acinq.phoenix.managers

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.db.IncomingPayment
import fr.acinq.lightning.db.WalletPayment
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.wire.OfferTypes
import fr.acinq.phoenix.PhoenixBusiness
import fr.acinq.phoenix.data.ContactInfo
import fr.acinq.phoenix.db.SqliteAppDb
import fr.acinq.phoenix.utils.extensions.incomingOfferMetadata
import fr.acinq.phoenix.utils.extensions.outgoingInvoiceRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMap
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


class ContactsManager(
    private val loggerFactory: LoggerFactory,
    private val appDb: SqliteAppDb,
) : CoroutineScope by MainScope() {

    constructor(business: PhoenixBusiness) : this(
        loggerFactory = business.loggerFactory,
        appDb = business.appDb,
    )

    private val log = loggerFactory.newLogger(this::class)

    private val _contactsList = MutableStateFlow<List<ContactInfo>>(emptyList())
    val contactsList = _contactsList.asStateFlow()

    val contactsMap = _contactsList.map { list ->
        list.associateBy { it.id }
    }.stateIn(scope = this, started = SharingStarted.Eagerly, initialValue = emptyMap())

    val offerMap = _contactsList.map { list ->
        list.flatMap { contact ->
            contact.offers.map { offer ->
                offer to contact.id
            }
        }.toMap()
    }.stateIn(scope = this, started = SharingStarted.Eagerly, initialValue = emptyMap())

    val publicKeyMap = _contactsList.map { list ->
        list.flatMap { contact ->
            contact.publicKeys.map { pubKey ->
                pubKey to contact.id
            }
        }.toMap()
    }.stateIn(scope = this, started = SharingStarted.Eagerly, initialValue = emptyMap())

    val contactsWithOfferList = _contactsList.map { contacts ->
        contacts.filter { it.offers.isNotEmpty()  }
    }

    init {
        launch { appDb.monitorContacts().collect { _contactsList.value = it } }
    }

    suspend fun getContactForOffer(offer: OfferTypes.Offer): ContactInfo? {
        return appDb.getContactForOffer(offer.offerId)
    }

    suspend fun saveNewContact(
        name: String,
        photoUri: String?,
        useOfferKey: Boolean,
        offer: OfferTypes.Offer
    ): ContactInfo {
        val contact = ContactInfo(id = UUID.randomUUID(), name = name, photoUri = photoUri, useOfferKey = useOfferKey, offers = listOf(offer))
        appDb.saveContact(contact)
        return contact
    }

    suspend fun updateContact(
        contactId: UUID,
        name: String,
        photoUri: String?,
        useOfferKey: Boolean,
        offers: List<OfferTypes.Offer>
    ): ContactInfo {
        val contact = ContactInfo(id = contactId, name = name, photoUri = photoUri, useOfferKey = useOfferKey, offers = offers)
        appDb.updateContact(contact)
        return contact
    }

    suspend fun getContactForPayerPubkey(payerPubkey: PublicKey): ContactInfo? {
        return appDb.listContacts().firstOrNull { it.publicKeys.contains(payerPubkey) }
    }

    suspend fun deleteContact(contactId: UUID) {
        appDb.deleteContact(contactId)
    }

    suspend fun detachOfferFromContact(offerId: ByteVector32) {
        appDb.deleteOfferContactLink(offerId)
    }

    /**
     * In many cases there's no need to query the database since we have everything in memory.
     */

    fun contactForId(contactId: UUID): ContactInfo? {
        return contactsMap.value[contactId]
    }

    fun contactIdForPayment(payment: WalletPayment): UUID? {
        return if (payment is IncomingPayment) {
            payment.incomingOfferMetadata()?.let { offerMetadata ->
                publicKeyMap.value[offerMetadata.payerKey]
            }
        } else {
            payment.outgoingInvoiceRequest()?.let {invoiceRequest ->
                offerMap.value[invoiceRequest.offer]
            }
        }
    }

    fun contactForPayment(payment: WalletPayment): ContactInfo? {
        return contactIdForPayment(payment)?.let { contactId ->
            contactForId(contactId)
        }
    }
}