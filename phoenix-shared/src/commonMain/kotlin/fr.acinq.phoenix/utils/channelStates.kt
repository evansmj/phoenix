package fr.acinq.phoenix.utils

import fr.acinq.eclair.channel.ChannelState
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.channel.Offline
import fr.acinq.eclair.channel.Syncing
import fr.acinq.eclair.transactions.CommitmentSpec


val ChannelState.localCommitmentSpec: CommitmentSpec? get() =
    when (this) {
        is HasCommitments -> commitments.localCommit.spec
        is Offline -> state.localCommitmentSpec
        is Syncing -> state.localCommitmentSpec
        else -> null
    }
