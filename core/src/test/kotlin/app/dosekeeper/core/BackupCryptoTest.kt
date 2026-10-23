package app.dosekeeper.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BackupCryptoTest {
    private val plain = """{"profiles":[{"name":"Rex"}]}""".toByteArray()

    @Test fun `round trips with the right password`() {
        val sealed = BackupCrypto.seal(plain, "correct horse".toCharArray())
        assertFalse(sealed.data.contentEquals(plain))
        assertContentEquals(plain, BackupCrypto.open(sealed, "correct horse".toCharArray()))
    }

    @Test fun `wrong password is refused`() {
        val sealed = BackupCrypto.seal(plain, "correct horse".toCharArray())
        assertFailsWith<BackupCrypto.WrongPasswordException> { BackupCrypto.open(sealed, "wrong".toCharArray()) }
    }
}
