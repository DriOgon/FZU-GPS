package com.driogon.fzucheckin.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.driogon.fzucheckin.core.Credentials
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a connected device. Uses an isolated namespace; never clears the real account. */
@RunWith(AndroidJUnit4::class)
class CredentialStoreDeviceTest {
    @Test fun credentialsAreEncryptedSurviveRecreationAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedCredentialStore(context, "device_test")
        val account = Credentials("synthetic-device-account", "synthetic-device-password")
        try {
            store.save(account)
            assertEquals(account, EncryptedCredentialStore(context, "device_test").load())
            val blob = context.getSharedPreferences("encrypted_device_test", Context.MODE_PRIVATE).getString("blob", "")!!
            assertFalse(blob.contains(account.username))
            assertFalse(blob.contains(account.password))
            store.clear()
            assertNull(store.load())
        } finally { store.clear() }
    }
}
