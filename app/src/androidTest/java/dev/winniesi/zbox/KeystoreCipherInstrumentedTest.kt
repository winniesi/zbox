package dev.winniesi.zbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.winniesi.zbox.platform.KeystoreAesGcmCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 真机 AndroidKeyStore 上的凭证加解密验证（JVM 单测无法覆盖）。 */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherInstrumentedTest {

    private val cipher = KeystoreAesGcmCipher("zbox_test_key")

    private val secret = "FakeHashA/FakeHashB+FakeHashC="

    @Test
    fun encryptThenDecryptRoundTrips() {
        val stored = cipher.encrypt(secret)
        assertNotEquals(secret, stored)
        assertEquals(secret, cipher.decrypt(stored))
    }

    @Test
    fun sameInputEncryptsToDifferentCiphertext() {
        assertNotEquals(cipher.encrypt(secret), cipher.encrypt(secret))
    }

    @Test
    fun tamperedCiphertextFailsToDecrypt() {
        val stored = cipher.encrypt(secret)
        val bytes = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val tampered = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        try {
            cipher.decrypt(tampered)
            fail("篡改后的密文不应解密成功")
        } catch (expected: Exception) {
            // GCM 校验失败抛出 AEADBadTagException（GeneralSecurityException 子类）
        }
    }
}
