package com.superbot.sati.ui

import com.superbot.sati.R
import com.superbot.sati.ai.ReplyFailure

internal fun ReplyFailure.messageResource(): Int = when (this) {
    ReplyFailure.Generation -> R.string.error_generation
    ReplyFailure.Authentication -> R.string.error_cloud_auth
    ReplyFailure.Balance -> R.string.error_cloud_balance
    ReplyFailure.RateLimit -> R.string.error_cloud_rate
    ReplyFailure.Network -> R.string.error_cloud_network
    ReplyFailure.Service -> R.string.error_cloud_service
    ReplyFailure.InvalidResponse -> R.string.error_cloud_response
    ReplyFailure.Storage -> R.string.error_key_storage
}
