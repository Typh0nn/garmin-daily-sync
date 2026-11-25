package pl.localagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistTest {
    @Test fun banksAndPasswordsBlocked() {
        listOf("pl.pkobp.iko", "pl.mbank", "pl.ing.mojeing", "com.revolut.revolut",
            "com.x8bit.bitwarden", "com.google.android.apps.authenticator2")
            .forEach { assertTrue(it, Blocklist.isDefaultBlocked(it)) }
    }

    @Test fun ordinaryAppsNotBlocked() {
        listOf("com.instagram.android", "com.google.android.youtube", "com.amazing.thing", "org.telegram.messenger")
            .forEach { assertFalse(it, Blocklist.isDefaultBlocked(it)) }
    }
}
