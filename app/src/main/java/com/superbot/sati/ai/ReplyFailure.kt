package com.superbot.sati.ai

enum class ReplyFailure {
    Generation, Authentication, Balance, RateLimit, Network, Service, InvalidResponse, Storage,
}

/** Safe to display or log: never includes a credential, request, or server response body. */
internal class ReplyException(val failure: ReplyFailure) :
    java.io.IOException("Reply failed: ${failure.name}")
