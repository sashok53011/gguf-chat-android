package com.devhorizon.online.ggufchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Markdown message body: prose is rendered by [MarkdownText]; fenced ```code blocks```
 * become standalone [CodeBlock]s with syntax highlighting and a copy-to-clipboard button.
 */
@Composable
fun MarkdownMessage(
    text: String,
    textColor: Color,
    l: (String) -> String,
    modifier: Modifier = Modifier
) {
    val segments = remember(text) { splitCodeFences(text) }
    Column(modifier = modifier) {
        segments.forEachIndexed { i, seg ->
            val topPad = if (i == 0) Modifier else Modifier.padding(top = 8.dp)
            when (seg) {
                is Segment.Text -> MarkdownText(
                    text = seg.content,
                    textColor = textColor,
                    modifier = topPad
                )
                is Segment.Code -> CodeBlock(
                    code = seg.code,
                    language = seg.language,
                    l = l,
                    modifier = topPad
                )
            }
        }
    }
}

private sealed interface Segment {
    data class Text(val content: String) : Segment
    data class Code(val language: String, val code: String) : Segment
}

/** Matches ```lang\n ... ``` fences. */
private val FENCE = Regex("```[ \\t]*([^\\n`]*)\\r?\\n?([\\s\\S]*?)```")

private fun splitCodeFences(text: String): List<Segment> {
    val out = mutableListOf<Segment>()
    var last = 0
    for (m in FENCE.findAll(text)) {
        if (m.range.first > last) {
            val t = text.substring(last, m.range.first).trim('\n')
            if (t.isNotBlank()) out.add(Segment.Text(t))
        }
        out.add(Segment.Code(language = m.groupValues[1].trim(), code = m.groupValues[2].trimEnd('\n')))
        last = m.range.last + 1
    }
    if (last < text.length) {
        val t = text.substring(last).trim('\n')
        if (t.isNotBlank()) out.add(Segment.Text(t))
    }
    if (out.isEmpty()) out.add(Segment.Text(text))
    return out
}

@Composable
private fun CodeBlock(
    code: String,
    language: String,
    l: (String) -> String,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val highlighted = remember(code, language) { highlightCode(code, language) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CodeBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.ifBlank { "code" },
                color = CodeMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                    scope.launch {
                        delay(1500)
                        copied = false
                    }
                },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (copied) l("copied") else l("copy"),
                    tint = if (copied) CodeAccent else CodeMuted,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = highlighted,
                color = CodeText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 2.dp, bottom = 10.dp)
            )
        }
    }
}

private val CodeBackground = Color(0xFF1E1E1E)
private val CodeText = Color(0xFFE6E6E6)
private val CodeMuted = Color(0xFF9AA0A6)
private val CodeAccent = Color(0xFF7FD4C1)

private val STYLE_COMMENT = SpanStyle(color = Color(0xFF6A9955))
private val STYLE_STRING = SpanStyle(color = Color(0xFFCE9178))
private val STYLE_NUMBER = SpanStyle(color = Color(0xFFB5CEA8))
private val STYLE_KEYWORD = SpanStyle(color = Color(0xFF569CD6))

private val KEYWORDS = setOf(
    "if", "else", "elif", "for", "while", "do", "switch", "case", "default", "break",
    "continue", "return", "def", "function", "fn", "fun", "class", "struct", "enum",
    "interface", "object", "new", "delete", "import", "from", "package", "include", "define",
    "public", "private", "protected", "internal", "static", "final", "const", "val", "var",
    "let", "void", "int", "float", "double", "char", "bool", "boolean", "long", "short",
    "unsigned", "signed", "string", "str", "true", "false", "null", "nil", "none", "None",
    "True", "False", "and", "or", "not", "in", "is", "as", "try", "catch", "except",
    "finally", "throw", "throws", "raise", "with", "yield", "async", "await", "lambda",
    "this", "self", "super", "extends", "implements", "override", "abstract", "sealed", "data",
    "when", "match", "type", "sizeof", "namespace", "using", "template", "typename", "auto",
    "extern", "inline", "register", "volatile", "goto", "echo", "print", "end", "then",
    "begin", "module", "where", "select", "insert", "update", "join", "on", "group", "order",
    "by", "having", "suspend", "companion", "trait", "impl"
)

private val HASH_COMMENT_LANGS = setOf(
    "py", "python", "sh", "bash", "shell", "zsh", "yml", "yaml", "rb", "ruby", "toml", "conf"
)

private val regexCache = mutableMapOf<String, Regex>()

private fun tokenRegex(language: String): Regex {
    val hashPart = if (language.lowercase() in HASH_COMMENT_LANGS) "#[^\\n]*|" else ""
    return Regex(
        "(${hashPart}//[^\\n]*|/\\*[\\s\\S]*?\\*/)" + // 1 comment
            "|(\"(?:\\\\.|[^\"\\\\])*\")" +           // 2 double-quoted string
            "|('(?:\\\\.|[^'\\\\])*')" +              // 3 single-quoted string
            "|(\\b\\d+(?:\\.\\d+)?\\b)" +             // 4 number
            "|([A-Za-z_][A-Za-z0-9_]*)"               // 5 identifier/keyword
    )
}

private fun highlightCode(code: String, language: String): AnnotatedString {
    val regex = regexCache.getOrPut(language.lowercase()) { tokenRegex(language) }
    return buildAnnotatedString {
        var index = 0
        for (m in regex.findAll(code)) {
            if (m.range.first > index) append(code.substring(index, m.range.first))
            val text = m.value
            val style = when {
                m.groups[1] != null -> STYLE_COMMENT
                m.groups[2] != null || m.groups[3] != null -> STYLE_STRING
                m.groups[4] != null -> STYLE_NUMBER
                m.groups[5] != null && text in KEYWORDS -> STYLE_KEYWORD
                else -> null
            }
            if (style != null) withStyle(style) { append(text) } else append(text)
            index = m.range.last + 1
        }
        if (index < code.length) append(code.substring(index))
    }
}
