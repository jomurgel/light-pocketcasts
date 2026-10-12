package com.jomurgel.pocketcasts.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Tokens and account identity persisted between launches. */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val email: String,
    val accountUuid: String,
)

interface TokenStore {
    suspend fun read(): AuthTokens?
    suspend fun write(tokens: AuthTokens)
    suspend fun clear()

    /** Drop the tokens but remember whose data is cached on this device. */
    suspend fun expire()

    /** Identity of the account whose data is cached, signed in or not. */
    suspend fun cachedAccount(): String?
}

private val KEY_ACCESS = stringPreferencesKey("pc_access_token")
private val KEY_REFRESH = stringPreferencesKey("pc_refresh_token")
private val KEY_EMAIL = stringPreferencesKey("pc_email")
private val KEY_UUID = stringPreferencesKey("pc_account_uuid")

/** DataStore-backed implementation on the tool's shared preferences store. */
class DataStoreTokenStore(private val dataStore: DataStore<Preferences>) : TokenStore {

    override suspend fun read(): AuthTokens? {
        val prefs = dataStore.data.first()
        return AuthTokens(
            accessToken = prefs[KEY_ACCESS] ?: return null,
            refreshToken = prefs[KEY_REFRESH] ?: return null,
            email = prefs[KEY_EMAIL] ?: "",
            accountUuid = prefs[KEY_UUID] ?: "",
        )
    }

    override suspend fun write(tokens: AuthTokens) {
        dataStore.edit { prefs ->
            prefs[KEY_ACCESS] = tokens.accessToken
            prefs[KEY_REFRESH] = tokens.refreshToken
            prefs[KEY_EMAIL] = tokens.email
            prefs[KEY_UUID] = tokens.accountUuid
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_ACCESS)
            prefs.remove(KEY_REFRESH)
            prefs.remove(KEY_EMAIL)
            prefs.remove(KEY_UUID)
        }
    }

    override suspend fun expire() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_ACCESS)
            prefs.remove(KEY_REFRESH)
        }
    }

    override suspend fun cachedAccount(): String? {
        val prefs = dataStore.data.first()
        return accountIdentity(prefs[KEY_UUID].orEmpty(), prefs[KEY_EMAIL].orEmpty())
    }
}

/** The account uuid, falling back to the email when the server sent none. */
fun accountIdentity(accountUuid: String, email: String): String? =
    accountUuid.ifBlank { email }.ifBlank { null }
