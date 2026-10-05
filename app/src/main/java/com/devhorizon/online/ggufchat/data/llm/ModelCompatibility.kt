package com.devhorizon.online.ggufchat.data.llm

/**
 * Single source of truth for what the vendored llama.cpp build can run.
 * The list mirrors llama-arch.cpp of this project's llama.cpp copy
 * (current llama.cpp — includes gemma3/gemma4/qwen3/llama4 etc.).
 */
object ModelCompatibility {

    val SUPPORTED_ARCHS = setOf(
        "afmoe", "apertus", "arcee", "arctic", "arwkv7", "baichuan", "bailingmoe",
        "bailingmoe2", "bailingmoe3", "bert", "bitnet", "bloom", "chameleon",
        "chatglm", "clef", "codeshell", "cogvlm", "cohere2", "cohere2moe",
        "command-r", "dbrx", "deci", "deepseek", "deepseek2", "deepseek2-ocr",
        "deepseek32", "deepseek4", "dflash", "dots1", "dots3note", "dream",
        "eagle3", "eurobert", "exaone", "exaone4", "exaone-moe", "falcon",
        "falcon-h1", "gemma", "gemma2", "gemma3", "gemma3n", "gemma4",
        "gemma4-assistant", "gemma-embedding", "glm4", "glm4moe", "glm5-next",
        "glm-dsa", "gpt2", "gptj", "gptneox", "gpt-oss", "granite", "granitehybrid",
        "granitemoe", "graniteswitch", "grok", "grovemoe", "hunyuan-dense",
        "hunyuan-moe", "internlm2", "jais", "jais2", "jamba", "jina-bert-v2",
        "jina-bert-v3", "kimi-k3", "kimi-linear", "laguna", "lfm2", "lfm2moe",
        "llada", "llada-moe", "llama", "llama4", "llama-embed", "maincoder",
        "mamba", "mamba2", "maple", "mellum", "mimo2", "minicpm", "minicpm3",
        "minimax-01", "minimax-m2", "minimax-m3", "mistral3", "mistral4",
        "modern-bert", "mpt", "muse-glimmer", "nanbeige", "nemotron", "neo-bert",
        "nomic-bert", "nomic-bert-moe", "olmo", "olmo2", "olmoe", "openelm",
        "orion", "paddleocr", "pangu-embedded", "phi2", "phi3", "phimoe", "plamo",
        "plamo2", "plamo3", "plm", "pockettts", "qwen", "qwen2", "qwen2moe",
        "qwen2vl", "qwen3", "qwen35", "qwen35moe", "qwen3moe", "qwen3next",
        "qwen3tts", "qwen3vl", "qwen3vlmoe", "qwen4exp", "refact", "rnd1",
        "rwkv6", "rwkv6qwen2", "rwkv7", "smallthinker", "smollm3", "stablelm",
        "starcoder", "starcoder2", "step35", "t5", "t5encoder", "talkie",
        "wavtokenizer-dec", "xverse"
    )

    /** Hugging Face tags of architectures this build cannot run (none now). */
    private val UNSUPPORTED_ARCH_TAGS = emptySet<String>()

    /** File-name markers of files that are NOT standalone language models. */
    private val NON_MODEL_MARKERS = listOf("mmproj", "lora", "adapter", "vocab", "tokenizer")

    /** Split model parts: name-00001-of-00002.gguf — never usable alone. */
    private val PART_REGEX = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)

    fun isArchSupported(arch: String): Boolean =
        arch.isNotBlank() && arch.lowercase() in SUPPORTED_ARCHS

    /** False for mmproj/lora/adapter/vocab/tokenizer files and split parts. */
    fun isModelFileName(name: String): Boolean {
        val n = name.lowercase()
        if (NON_MODEL_MARKERS.any { n.contains(it) }) return false
        if (PART_REGEX.containsMatchIn(n)) return false
        return true
    }

    /** True for a multimodal projector file (mmproj GGUF). */
    fun isProjectorFileName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".gguf") && n.contains("mmproj")
    }

    /** Architectures that support image input once an mmproj is attached. */
    private val VISION_ARCHS = setOf(
        "qwen2vl", "qwen3vl", "qwen3vlmoe", "gemma3", "gemma3n", "gemma4",
        "llama4", "minicpm", "cogvlm", "pixtral", "mistral3", "internlm2"
    )

    fun isVisionArch(arch: String): Boolean = arch.lowercase() in VISION_ARCHS

    /** Skip whole repositories whose tags declare an unsupported architecture. */
    fun repoHasUnsupportedArchTag(tags: List<String>?): Boolean {
        tags ?: return false
        val lower = tags.map { it.lowercase() }
        return UNSUPPORTED_ARCH_TAGS.any { it in lower }
    }
}
