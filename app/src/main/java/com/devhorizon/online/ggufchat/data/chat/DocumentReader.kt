package com.devhorizon.online.ggufchat.data.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/** Raised for expected document problems; [messageKey] is a localization key. */
class DocumentException(val messageKey: String) : Exception(messageKey)

data class RawDocument(
    val name: String,
    val ext: String,
    val sizeBytes: Long,
    val text: String
)

/**
 * Reads a user-picked document and prepares its text for the chat context.
 *
 * Supports plain-text-like formats only; HTML is reduced to visible text.
 * The document text is truncated elsewhere against the model context budget.
 */
object DocumentReader {
    val ALLOWED_EXTENSIONS = setOf("txt", "md", "markdown", "json", "htm", "html", "csv")

    /** Hard ceiling so a huge/binary file cannot exhaust memory. */
    private const val MAX_READ_BYTES = 8 * 1024 * 1024

    /** Fraction of the context window reserved for document text. */
    private const val CONTEXT_FRACTION = 0.5

    fun budgetTokens(contextLength: Int): Int = (contextLength * CONTEXT_FRACTION).toInt()

    /** Rough token estimate; Cyrillic tokenizes much worse than Latin. */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cyrillic = 0
        for (c in text) if (c in '\u0400'..'\u04FF') cyrillic++
        val cyrRatio = cyrillic.toDouble() / text.length
        val charsPerToken = if (cyrRatio > 0.2) 1.8 else 3.5
        return (text.length / charsPerToken).toInt() + 1
    }

    private fun charsPerToken(text: String): Double {
        if (text.isEmpty()) return 3.5
        var cyrillic = 0
        for (c in text) if (c in '\u0400'..'\u04FF') cyrillic++
        return if (cyrillic.toDouble() / text.length > 0.2) 1.8 else 3.5
    }

    /**
     * Truncates [text] so it fits [remainingTokens]. Returns the (possibly
     * shortened) text plus whether truncation happened.
     */
    fun truncate(text: String, remainingTokens: Int): Pair<String, Boolean> {
        if (remainingTokens <= 0) return "" to true
        val maxChars = (remainingTokens * charsPerToken(text)).toInt()
        if (text.length <= maxChars) return text to false
        return text.substring(0, maxChars.coerceAtLeast(0)) + "\n…" to true
    }

    fun read(context: Context, uri: Uri, displayName: String? = null): RawDocument {
        val name = displayName
            ?: queryName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "document"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED_EXTENSIONS) throw DocumentException("doc_unsupported")

        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (total < MAX_READ_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val take = minOf(n, MAX_READ_BYTES - total)
                    if (take > 0) out.write(buf, 0, take)
                    total += n
                }
                out.toByteArray()
            } ?: throw DocumentException("doc_read_error")
        } catch (e: DocumentException) {
            throw e
        } catch (e: Exception) {
            throw DocumentException("doc_read_error")
        }

        val size = querySize(context, uri) ?: bytes.size.toLong()
        var text = String(bytes, Charsets.UTF_8)
        if (ext == "htm" || ext == "html") text = stripHtml(text)
        text = cleanup(text)
        return RawDocument(name = name, ext = ext, sizeBytes = size, text = text)
    }

    private fun querySize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun queryName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun cleanup(text: String): String =
        text.replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    private fun stripHtml(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?is)</(p|div|li|tr|h[1-6]|section|article)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
        s = s.replace(Regex("[ \\t]{2,}"), " ")
        s = s.replace(Regex(" *\n *"), "\n")
        return s.trim()
    }
}
