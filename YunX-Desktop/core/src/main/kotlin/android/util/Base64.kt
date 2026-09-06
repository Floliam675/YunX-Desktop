/*
 * YunX Desktop - AGPL-3.0. Minimal android.util.Base64 re-implementation on the JVM
 * so that code ported from the Android app compiles unchanged. Android semantics:
 * decode() ignores whitespace and tolerates missing padding; flags DEFAULT/NO_WRAP/NO_PADDING/URL_SAFE.
 */
@file:Suppress("unused", "PackageDirectoryMismatch")
package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    fun encodeToString(input: ByteArray, flags: Int): String = String(encode(input, flags), Charsets.UTF_8)

    fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String =
        encodeToString(input.copyOfRange(offset, offset + len), flags)

    fun encodeToString(input: ByteArray): String = encodeToString(input, DEFAULT)

    fun encode(input: ByteArray, flags: Int): ByteArray = encoder(flags).encode(input)

    fun encode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray =
        encode(input.copyOfRange(offset, offset + len), flags)

    fun decode(str: String, flags: Int): ByteArray = decoder(flags).decode(prepare(str, flags))

    fun decode(str: String): ByteArray = decode(str, DEFAULT)

    fun decode(input: ByteArray, flags: Int): ByteArray = decode(String(input, Charsets.UTF_8), flags)

    fun decode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray =
        decode(String(input.copyOfRange(offset, offset + len), Charsets.UTF_8), flags)

    private fun encoder(flags: Int): java.util.Base64.Encoder =
        if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder().withoutPadding() else java.util.Base64.getEncoder()

    private fun decoder(flags: Int): java.util.Base64.Decoder =
        if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getMimeDecoder()

    /** android.util.Base64 ignores whitespace and tolerates missing padding on decode */
    private fun prepare(str: String, flags: Int): String {
        val cleaned = str.filter { !it.isWhitespace() }
        val padNeeded = (4 - cleaned.length % 4) % 4
        return if (flags and NO_PADDING == 0 && padNeeded != 0) cleaned + "=".repeat(padNeeded) else cleaned
    }
}
