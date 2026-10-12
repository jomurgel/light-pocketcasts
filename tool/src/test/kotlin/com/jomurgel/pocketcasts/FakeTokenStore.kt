package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.AuthTokens
import com.jomurgel.pocketcasts.data.TokenStore
import com.jomurgel.pocketcasts.data.accountIdentity

class FakeTokenStore(initial: AuthTokens? = null) : TokenStore {
    var tokens: AuthTokens? = initial
    var cached: String? = initial?.let { accountIdentity(it.accountUuid, it.email) }
    var writes = 0

    override suspend fun read(): AuthTokens? = tokens

    override suspend fun write(tokens: AuthTokens) {
        this.tokens = tokens
        cached = accountIdentity(tokens.accountUuid, tokens.email)
        writes++
    }

    override suspend fun clear() {
        tokens = null
        cached = null
    }

    override suspend fun expire() {
        tokens = null
    }

    override suspend fun cachedAccount(): String? = cached
}
