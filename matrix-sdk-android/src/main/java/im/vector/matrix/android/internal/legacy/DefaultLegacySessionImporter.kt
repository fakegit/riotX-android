/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.matrix.android.internal.legacy

import android.content.Context
import im.vector.matrix.android.api.auth.data.Credentials
import im.vector.matrix.android.api.auth.data.DiscoveryInformation
import im.vector.matrix.android.api.auth.data.HomeServerConnectionConfig
import im.vector.matrix.android.api.auth.data.SessionParams
import im.vector.matrix.android.api.auth.data.WellKnownBaseConfig
import im.vector.matrix.android.api.extensions.tryThis
import im.vector.matrix.android.api.legacy.LegacySessionImporter
import im.vector.matrix.android.internal.auth.SessionParamsStore
import im.vector.matrix.android.internal.crypto.store.db.RealmCryptoStoreMigration
import im.vector.matrix.android.internal.crypto.store.db.RealmCryptoStoreModule
import im.vector.matrix.android.internal.database.RealmKeysUtils
import im.vector.matrix.android.internal.legacy.riot.LoginStorage
import im.vector.matrix.android.internal.network.ssl.Fingerprint
import im.vector.matrix.android.internal.util.md5
import io.realm.Realm
import io.realm.RealmConfiguration
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import im.vector.matrix.android.internal.legacy.riot.Fingerprint as LegacyFingerprint
import im.vector.matrix.android.internal.legacy.riot.HomeServerConnectionConfig as LegacyHomeServerConnectionConfig

internal class DefaultLegacySessionImporter @Inject constructor(
        private val context: Context,
        private val sessionParamsStore: SessionParamsStore,
        private val realmCryptoStoreMigration: RealmCryptoStoreMigration,
        private val realmKeysUtils: RealmKeysUtils
) : LegacySessionImporter {

    private val loginStorage = LoginStorage(context)

    override fun process() {
        Timber.d("Migration: Importing legacy session")

        val list = loginStorage.credentialsList

        Timber.d("Migration: found ${list.size} session(s).")

        val legacyConfig = list.firstOrNull() ?: return

        runBlocking {
            Timber.d("Migration: importing a session")
            try {
                importCredentials(legacyConfig)
            } catch (t: Throwable) {
                // It can happen in case of partial migration. To test, do not return
                Timber.e(t, "Error importing credential")
            }

            Timber.d("Migration: importing crypto DB")
            try {
                importCryptoDb(legacyConfig)
            } catch (t: Throwable) {
                // It can happen in case of partial migration. To test, do not return
                Timber.e(t, "Error importing crypto DB")
            }

            Timber.d("Migration: clear file system")
            try {
                clearFileSystem(legacyConfig)
            } catch (t: Throwable) {
                // It can happen in case of partial migration. To test, do not return
                Timber.e(t, "Error clearing filesystem")
            }

            Timber.d("Migration: clear shared prefs")
            try {
                clearSharedPrefs()
            } catch (t: Throwable) {
                // It can happen in case of partial migration. To test, do not return
                Timber.e(t, "Error clearing filesystem")
            }
        }
    }

    private suspend fun importCredentials(legacyConfig: LegacyHomeServerConnectionConfig) {
        @Suppress("DEPRECATION")
        val sessionParams = SessionParams(
                credentials = Credentials(
                        userId = legacyConfig.credentials.userId,
                        accessToken = legacyConfig.credentials.accessToken,
                        refreshToken = legacyConfig.credentials.refreshToken,
                        homeServer = legacyConfig.credentials.homeServer,
                        deviceId = legacyConfig.credentials.deviceId,
                        discoveryInformation = legacyConfig.credentials.wellKnown?.let { wellKnown ->
                            // Note credentials.wellKnown is not serialized in the LoginStorage, so this code is a bit useless...
                            if (wellKnown.homeServer?.baseURL != null
                                    || wellKnown.identityServer?.baseURL != null) {
                                DiscoveryInformation(
                                        homeServer = wellKnown.homeServer?.baseURL?.let { WellKnownBaseConfig(baseURL = it) },
                                        identityServer = wellKnown.identityServer?.baseURL?.let { WellKnownBaseConfig(baseURL = it) }
                                )
                            } else {
                                null
                            }
                        }
                ),
                homeServerConnectionConfig = HomeServerConnectionConfig(
                        homeServerUri = legacyConfig.homeserverUri,
                        identityServerUri = legacyConfig.identityServerUri,
                        antiVirusServerUri = legacyConfig.antiVirusServerUri,
                        allowedFingerprints = legacyConfig.allowedFingerprints.map {
                            Fingerprint(
                                    bytes = it.bytes,
                                    hashType = when (it.type) {
                                        LegacyFingerprint.HashType.SHA1,
                                        null                              -> Fingerprint.HashType.SHA1
                                        LegacyFingerprint.HashType.SHA256 -> Fingerprint.HashType.SHA256
                                    }
                            )
                        },
                        shouldPin = legacyConfig.shouldPin(),
                        tlsVersions = legacyConfig.acceptedTlsVersions,
                        tlsCipherSuites = legacyConfig.acceptedTlsCipherSuites,
                        shouldAcceptTlsExtensions = legacyConfig.shouldAcceptTlsExtensions(),
                        allowHttpExtension = false, // TODO
                        forceUsageTlsVersions = legacyConfig.forceUsageOfTlsVersions()
                ),
                // If token is not valid, this boolean will be updated later
                isTokenValid = true
        )

        Timber.d("Migration: save session")
        sessionParamsStore.save(sessionParams)
    }

    private fun importCryptoDb(legacyConfig: LegacyHomeServerConnectionConfig) {
        // Here we migrate the DB, we copy the crypto DB to the location specific to RiotX, and we encrypt it.
        val userMd5 = legacyConfig.credentials.userId.md5()

        val sessionId = legacyConfig.credentials.let { (if (it.deviceId.isNullOrBlank()) it.userId else "${it.userId}|${it.deviceId}").md5() }
        val newLocation = File(context.filesDir, sessionId)

        val keyAlias = "crypto_module_$userMd5"

        // Ensure newLocation does not exist (can happen in case of partial migration)
        newLocation.deleteRecursively()
        newLocation.mkdirs()

        // TODO Check if file exists first?
        Timber.d("Migration: create legacy realm configuration")

        val realmConfiguration = RealmConfiguration.Builder()
                .directory(File(context.filesDir, userMd5))
                .name("crypto_store.realm")
                .modules(RealmCryptoStoreModule())
                .schemaVersion(RealmCryptoStoreMigration.CRYPTO_STORE_SCHEMA_VERSION)
                .migration(realmCryptoStoreMigration)
                // .initialData(CryptoFileStoreImporter(enableFileEncryption, context, credentials))
                .build()

        Timber.d("Migration: copy DB to encrypted DB")
        Realm.getInstance(realmConfiguration).use {
            // Move the DB to the new location, handled by RiotX
            it.writeEncryptedCopyTo(File(newLocation, realmConfiguration.realmFileName), realmKeysUtils.getRealmEncryptionKey(keyAlias))
        }
    }

    // Delete all the files created by Riot Android which will not be used anymore by RiotX
    private fun clearFileSystem(legacyConfig: LegacyHomeServerConnectionConfig) {
        val cryptoFolder = legacyConfig.credentials.userId.md5()

        listOf(
                // Where session store was saved (we do not care about migrating that, an initial sync will be performed)
                File(context.filesDir, "MXFileStore"),
                // Previous (and very old) file crypto store
                File(context.filesDir, "MXFileCryptoStore"),
                // Draft. They will be lost, this is sad TODO handle them?
                File(context.filesDir, "MXLatestMessagesStore"),
                // Media storage
                File(context.filesDir, "MXMediaStore"),
                File(context.filesDir, "MXMediaStore2"),
                File(context.filesDir, "MXMediaStore3"),
                // Ext folder
                File(context.filesDir, "ext_share"),
                // Crypto store
                File(context.filesDir, cryptoFolder)
        ).forEach { file ->
            tryThis { file.deleteRecursively() }
        }
    }

    private fun clearSharedPrefs() {
        // Shared Pref. Note that we do not delete the default preferences, as it should be nearly the same (TODO check that)
        listOf(
                "Vector.LoginStorage",
                "GcmRegistrationManager",
                "IntegrationManager.Storage"
        ).forEach { prefName ->
            context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
        }
    }
}
