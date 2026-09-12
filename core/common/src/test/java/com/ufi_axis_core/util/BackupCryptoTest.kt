package com.ufi_axis_core.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [BackupCrypto] 的行为锁。
 *
 * 用例统一取 [BackupCrypto.MIN_ITERATIONS] 而非默认 60 万：这里验的是**语义**
 * （往返、错口令、AAD 绑定、下限校验），不是 KDF 强度，用默认轮数只会让测试变慢一个量级。
 */
class BackupCryptoTest {

    private val iterations = BackupCrypto.MIN_ITERATIONS
    private val plain = "备份内容：smtp_pass=hunter2".toByteArray()
    private val aad = """{"version":1,"device_id":"abcd"}""".toByteArray()

    @Test
    fun `seal then open round trips`() {
        val salt = BackupCrypto.newSalt()
        val sealed = BackupCrypto.seal(plain, "correct horse battery".toCharArray(), salt, iterations, aad)
        val opened = BackupCrypto.open(sealed, "correct horse battery".toCharArray(), salt, iterations, aad)
        assertArrayEquals(plain, opened)
    }

    @Test
    fun `wrong passphrase is rejected`() {
        val salt = BackupCrypto.newSalt()
        val sealed = BackupCrypto.seal(plain, "correct horse battery".toCharArray(), salt, iterations, aad)
        try {
            BackupCrypto.open(sealed, "correct horse batterY".toCharArray(), salt, iterations, aad)
            fail("错口令必须抛 InvalidPassphraseException")
        } catch (e: BackupCrypto.InvalidPassphraseException) {
            // 文案不区分「口令错」与「文件损坏」，避免给攻击者确认口令的信号
            assertEquals("口令错误或备份文件已损坏", e.message)
        }
    }

    @Test
    fun `tampered manifest breaks decryption`() {
        // AAD 绑定的意义：改 manifest（哪怕只改一个字符）就解不开，
        // 而不是"解出内容但参数已被换过"。
        val salt = BackupCrypto.newSalt()
        val pass = "correct horse battery".toCharArray()
        val sealed = BackupCrypto.seal(plain, pass, salt, iterations, aad)
        val tampered = """{"version":1,"device_id":"ffff"}""".toByteArray()
        try {
            BackupCrypto.open(sealed, "correct horse battery".toCharArray(), salt, iterations, tampered)
            fail("AAD 不一致必须解密失败")
        } catch (e: BackupCrypto.InvalidPassphraseException) {
            // 预期
        }
    }

    @Test
    fun `tampered ciphertext breaks decryption`() {
        val salt = BackupCrypto.newSalt()
        val sealed = BackupCrypto.seal(plain, "correct horse battery".toCharArray(), salt, iterations, aad)
        sealed.cipherText[0] = (sealed.cipherText[0].toInt() xor 0x01).toByte()
        try {
            BackupCrypto.open(sealed, "correct horse battery".toCharArray(), salt, iterations, aad)
            fail("密文被改必须解密失败")
        } catch (e: BackupCrypto.InvalidPassphraseException) {
            // 预期：GCM 的 tag 校验负责这件事，不需要额外 HMAC
        }
    }

    @Test
    fun `iterations below floor are refused`() {
        // 防「攻击者把包里的轮数改成 1 让口令瞬间可爆破」：
        // 解密参数的下限不该由被解密的文件说了算。
        val salt = BackupCrypto.newSalt()
        val sealed = BackupCrypto.Sealed(ByteArray(BackupCrypto.IV_BYTES), ByteArray(32))
        try {
            BackupCrypto.open(sealed, "correct horse battery".toCharArray(), salt, 1000, aad)
            fail("低于下限的轮数必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `same passphrase produces different ciphertext each time`() {
        // 盐与 IV 都必须每次新生成：GCM 的 IV 复用会直接泄露明文异或值。
        val pass = "correct horse battery"
        val a = BackupCrypto.seal(plain, pass.toCharArray(), BackupCrypto.newSalt(), iterations, aad)
        val b = BackupCrypto.seal(plain, pass.toCharArray(), BackupCrypto.newSalt(), iterations, aad)
        assertFalse("两次加密的 IV 不应相同", a.iv.contentEquals(b.iv))
        assertFalse("两次加密的密文不应相同", a.cipherText.contentEquals(b.cipherText))
    }

    @Test
    fun `iterations above ceiling are refused`() {
        // 反方向的上限：manifest 里的轮数来自不可信的备份文件，没有上限时一个
        // 写成 Int.MAX_VALUE 的包能让 core 单线程跑几十分钟 —— 可被配对端触发的 DoS。
        val salt = BackupCrypto.newSalt()
        val sealed = BackupCrypto.Sealed(ByteArray(BackupCrypto.IV_BYTES), ByteArray(32))
        try {
            BackupCrypto.open(
                sealed, "correct horse battery".toCharArray(), salt,
                BackupCrypto.MAX_ITERATIONS + 1, aad
            )
            fail("高于上限的轮数必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `ceiling and floor leave room for the default`() {
        // 常量之间的关系也要锁住：默认值必须落在 [下限, 上限] 之间，
        // 否则改参数时会出现"自己导出的包自己解不开"。
        assertTrue(BackupCrypto.DEFAULT_ITERATIONS >= BackupCrypto.MIN_ITERATIONS)
        assertTrue(BackupCrypto.DEFAULT_ITERATIONS <= BackupCrypto.MAX_ITERATIONS)
    }

    @Test
    fun `passphrase validation enforces minimum length`() {
        assertNotNull(BackupCrypto.validatePassphrase("short"))
        assertNotNull(BackupCrypto.validatePassphrase("            "))
        assertNull(BackupCrypto.validatePassphrase("correct horse battery"))
        // 恰好等于下限应当通过
        assertNull(BackupCrypto.validatePassphrase("a".repeat(BackupCrypto.MIN_PASSPHRASE_LENGTH)))
    }
}
