package dev.winniesi.zbox.core

/** 对称加解密抽象：用于把链接里的 hash 凭证加密后落盘。 */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(stored: String): String
}
