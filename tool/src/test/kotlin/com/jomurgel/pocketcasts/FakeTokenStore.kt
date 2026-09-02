package com.jomurgel.pocketcasts

import com.jomurgel.pocketcasts.data.AuthTokens
import com.jomurgel.pocketcasts.data.TokenStore

class FakeTokenStore(initial: AuthTokens? = null) : TokenStore {
    var tokens: AuthTokens? = initial
    var writes = 0

    override suspend fun read(): AuthTokens? = tokens

    override suspend fun write(tokens: AuthTokens) {
        this.tokens = tokens
        writes++
    }

    override suspend fun clear() {
        tokens = null
    }
}
