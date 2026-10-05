#include <jni.h>
#include <string>
#include <thread>
#include <mutex>
#include <vector>
#include <atomic>
#include <algorithm>
#include <unordered_map>
#include <memory>
#include <malloc.h>
#include <android/log.h>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LocalLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

struct LlmSession {
    llama_model *model  = nullptr;
    llama_context *ctx  = nullptr;
    const llama_vocab *vocab = nullptr;
    mtmd_context *mtmd  = nullptr;   // multimodal projector (mmproj), optional
    int n_ctx           = 4096;
    int n_threads       = 4;
    bool ready          = false;
    std::atomic<bool> cancel_flag{false};
    std::mutex gen_mutex;   // held during generation; freeModel waits on it
};

static std::mutex g_mutex;
static std::atomic<int64_t> g_next_id{1};
static std::unordered_map<int64_t, std::shared_ptr<LlmSession>> g_sessions;

// JVM + active load-progress callback (valid only during nativeLoadModel,
// which calls the progress callback synchronously on the same thread).
static JavaVM *g_jvm = nullptr;
static jobject g_progress_cb = nullptr;
static jmethodID g_progress_mid = nullptr;

extern "C" jint JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static std::string jstring_to_std(JNIEnv *env, jstring js) {
    if (!js) return "";
    const char *c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

// Progress callback: called by llama.cpp during model load with 0.0..1.0
static bool load_progress_callback(float progress, void *user_data) {
    JNIEnv *e = *static_cast<JNIEnv **>(user_data);
    if (e && g_progress_cb && g_progress_mid) {
        e->CallVoidMethod(g_progress_cb, g_progress_mid, progress);
        if (e->ExceptionCheck()) {
            e->ExceptionClear();
        }
    }
    return true; // continue loading
}

// Build the sampler chain used by both text and vision generation.
static llama_sampler * make_sampler(const LlmSession *s, float temp, float topP) {
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    // Order matters: penalties act on raw logits, before temp/top-p.
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        llama_vocab_n_tokens(s->vocab), -1, 1.1f, 0.3f, 0.3f));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return sampler;
}

// Decode one token at an explicit position and advance n_past.
static bool decode_one(LlmSession *s, llama_token tok, llama_pos pos) {
    llama_batch b = llama_batch_get_one(&tok, 1);
    b.pos = &pos;
    return llama_decode(s->ctx, b) == 0;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_devhorizon_online_ggufchat_data_llm_LocalLlmNative_nativeLoadModel(
        JNIEnv *env, jobject /* this */,
        jstring modelPath, jint nThreads, jint nCtx,
        jobject progressCallback) {

    std::string path = jstring_to_std(env, modelPath);
    LOGI("Loading model: %s (threads=%d, nCtx=%d)", path.c_str(), nThreads, nCtx);

    static bool backend_inited = false;
    if (!backend_inited) {
        llama_backend_init();
        backend_inited = true;
    }

    auto session = std::make_shared<LlmSession>();
    session->n_threads = nThreads;
    session->n_ctx = nCtx;

    JNIEnv *jniEnv = env;
    g_progress_cb = progressCallback ? env->NewGlobalRef(progressCallback) : nullptr;
    jclass cbClass = progressCallback ? env->GetObjectClass(progressCallback) : nullptr;
    g_progress_mid = cbClass ? env->GetMethodID(cbClass, "onProgress", "(F)V") : nullptr;

    struct llama_model_params mparams = llama_model_default_params();
    mparams.progress_callback = load_progress_callback;
    mparams.progress_callback_user_data = &jniEnv;

    session->model = llama_model_load_from_file(path.c_str(), mparams);

    if (g_progress_cb) {
        env->DeleteGlobalRef(g_progress_cb);
        g_progress_cb = nullptr;
    }
    g_progress_mid = nullptr;

    if (!session->model) {
        LOGE("Failed to load model: %s", path.c_str());
        return -1;
    }

    session->vocab = llama_model_get_vocab(session->model);

    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = nCtx;
    cparams.n_batch = 512;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    session->ctx = llama_init_from_model(session->model, cparams);
    if (!session->ctx) {
        LOGE("Failed to create context");
        llama_model_free(session->model);
        session->model = nullptr;
        return -1;
    }

    session->ready = true;

    std::lock_guard<std::mutex> lock(g_mutex);
    int64_t id = g_next_id.fetch_add(1);
    g_sessions[id] = session;
    LOGI("Model loaded, handle=%ld", (long)id);
    return static_cast<jlong>(id);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_devhorizon_online_ggufchat_data_llm_LocalLlmNative_nativeApplyChatTemplate(
        JNIEnv *env, jobject /* this */,
        jlong handle, jobjectArray roles, jobjectArray contents, jstring templateName) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end() || !it->second->ready) {
        return env->NewStringUTF("[error: invalid model handle]");
    }
    auto &s = it->second;

    const char *tmpl = nullptr;
    std::string tmplName = jstring_to_std(env, templateName);
    if (!tmplName.empty()) {
        tmpl = llama_model_chat_template(s->model, tmplName.c_str());
    } else {
        tmpl = llama_model_chat_template(s->model, nullptr);
    }

    if (!tmpl) {
        LOGE("No chat template found in model metadata");
        return env->NewStringUTF("[error: no chat template]");
    }

    jsize n_msgs = env->GetArrayLength(roles);
    if (n_msgs <= 0 || env->GetArrayLength(contents) != n_msgs) {
        return env->NewStringUTF("[error: no messages parsed]");
    }

    std::vector<std::string> roleStore(n_msgs);
    std::vector<std::string> contentStore(n_msgs);
    std::vector<llama_chat_message> messages(n_msgs);

    for (jsize i = 0; i < n_msgs; i++) {
        auto jr = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto jc = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        roleStore[i] = jstring_to_std(env, jr);
        contentStore[i] = jstring_to_std(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        messages[i].role = roleStore[i].c_str();
        messages[i].content = contentStore[i].c_str();
    }

    int32_t n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
    if (n < 0) {
        LOGE("Failed to apply chat template: %d", n);
        return env->NewStringUTF("[error: apply template failed]");
    }

    std::vector<char> buf(n);
    n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, buf.data(), buf.size());
    if (n < 0) {
        LOGE("Failed to apply chat template (2nd pass): %d", n);
        return env->NewStringUTF("[error: apply template failed]");
    }

    return env->NewStringUTF(std::string(buf.data(), n).c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_devhorizon_online_ggufchat_data_llm_LocalLlmNative_nativeCancel(
        JNIEnv *env, jobject /* this */, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    if (it != g_sessions.end()) {
        it->second->cancel_flag.store(true, std::memory_order_relaxed);
        LOGI("Cancel requested for handle=%ld", (long)handle);
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_devhorizon_online_ggufchat_data_llm_LocalLlmNative_nativeGenerateStreaming(
        JNIEnv *env, jobject /* this */,
        jlong handle, jstring prompt,
        jint maxTokens, jfloat temp, jfloat topP,
        jstring stopToken, jobject callback) {

    std::string sprompt = jstring_to_std(env, prompt);
    std::string sstop   = jstring_to_std(env, stopToken);

    std::shared_ptr<LlmSession> s;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end() || !it->second->ready) {
            return env->NewStringUTF("[error: invalid model handle]");
        }
        s = it->second;
    }
    s->cancel_flag.store(false, std::memory_order_relaxed);

    std::lock_guard<std::mutex> gen_lock(s->gen_mutex);
    if (!s->ready) {
        return env->NewStringUTF("[error: model freed]");
    }

    // Start every generation from a clean KV cache.
    llama_memory_clear(llama_get_memory(s->ctx), true);

    const int n_prompt = -llama_tokenize(
        s->vocab, sprompt.c_str(), sprompt.size(), nullptr, 0, true, true);

    std::vector<llama_token> tokens(n_prompt);
    int n_tokens = llama_tokenize(
        s->vocab, sprompt.c_str(), sprompt.size(), tokens.data(), tokens.size(), true, true);

    if (n_tokens < 0) {
        LOGE("Tokenize failed: %d", n_tokens);
        return env->NewStringUTF("[error: tokenize failed]");
    }

    LOGI("Prompt tokens: %d", n_tokens);

    const int n_batch = 512;
    llama_pos pos = 0;
    bool prompt_cancelled = false;
    for (int i = 0; i < n_tokens; i += n_batch) {
        if (s->cancel_flag.load(std::memory_order_relaxed)) {
            prompt_cancelled = true;
            break;
        }
        int n_cur = std::min(n_batch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n_cur);
        std::vector<llama_pos> poss(n_cur);
        for (int k = 0; k < n_cur; k++) poss[k] = pos + k;
        batch.pos = poss.data();
        if (llama_decode(s->ctx, batch)) {
            LOGE("Failed to eval prompt");
            return env->NewStringUTF("[error: eval prompt failed]");
        }
        pos += n_cur;
    }
    if (prompt_cancelled) {
        LOGI("Cancelled during prompt processing");
        return env->NewStringUTF("");
    }

    std::string response;
    int n_generated = 0;
    llama_sampler *sampler = make_sampler(s.get(), temp, topP);

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "(Ljava/lang/String;)V");
    if (!onTokenMethod || !onCompleteMethod) {
        LOGE("Failed to find callback methods");
        llama_sampler_free(sampler);
        return env->NewStringUTF("[error: invalid callback]");
    }

    static const char *kStopMarkers[] = { "<|im_end|>", "<|eot_id|>", "<|end|>", "<end_of_turn>", "</s>", "<turn|>" };

    llama_token prev_token = -1;
    int repeat_run = 0;
    std::vector<llama_token> recent;

    for (int i = 0; i < maxTokens; i++) {
        if (s->cancel_flag.load(std::memory_order_relaxed)) {
            LOGI("Generation cancelled at token %d", i);
            break;
        }

        llama_token new_token = llama_sampler_sample(sampler, s->ctx, -1);
        llama_sampler_accept(sampler, new_token);

        if (new_token == llama_vocab_eos(s->vocab) || llama_vocab_is_eog(s->vocab, new_token)) {
            LOGI("EOS reached at token %d", i);
            break;
        }

        if (new_token == prev_token) {
            if (++repeat_run >= 10) {
                LOGW("Repetition loop detected at token %d, stopping", i);
                break;
            }
        } else {
            prev_token = new_token;
            repeat_run = 0;
        }

        recent.push_back(new_token);
        if (recent.size() >= 15) {
            bool looped = false;
            const int minRepeats = 5;
            const int maxPeriod  = 12;
            const int nr = (int) recent.size();
            for (int period = 1; period <= maxPeriod && !looped; period++) {
                if (period * minRepeats > nr) continue;
                bool match = true;
                for (int r = 1; r < minRepeats && match; r++) {
                    for (int k = 0; k < period; k++) {
                        if (recent[nr - 1 - k] != recent[nr - 1 - k - r * period]) {
                            match = false;
                            break;
                        }
                    }
                }
                if (match) looped = true;
            }
            if (looped) {
                LOGW("Repetition phrase loop detected at token %d, stopping", i);
                break;
            }
        }

        char buf[256];
        int n = llama_token_to_piece(s->vocab, new_token, buf, sizeof(buf), 0, true);
        if (n <= 0) {
            if (!decode_one(s.get(), new_token, pos++)) break;
            continue;
        }
        buf[n] = '\0';
        std::string tokenStr(buf);

        bool stop_marker = false;
        for (const char *marker : kStopMarkers) {
            if (tokenStr.find(marker) != std::string::npos) {
                stop_marker = true;
                break;
            }
        }
        if (stop_marker) {
            LOGI("Stop marker reached at token %d", i);
            break;
        }

        response += buf;

        if (!sstop.empty()) {
            auto p = response.find(sstop);
            if (p != std::string::npos) {
                response = response.substr(0, p);
                break;
            }
        }

        jstring jToken = env->NewStringUTF(tokenStr.c_str());
        env->CallVoidMethod(callback, onTokenMethod, jToken);
        env->DeleteLocalRef(jToken);

        n_generated++;

        if (!decode_one(s.get(), new_token, pos++)) {
            LOGW("decode failed at token %d", i);
            break;
        }
    }

    llama_sampler_free(sampler);

    jstring jFullResponse = env->NewStringUTF(response.c_str());
    env->CallVoidMethod(callback, onCompleteMethod, jFullResponse);
    env->DeleteLocalRef(jFullResponse);

    LOGI("Streaming generated %d tokens, response length: %zu", n_generated, response.size());
    return env->NewStringUTF(response.c_str());
}

// ==================== Multimodal (mtmd) ====================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_devhorizon_online_ggufchat_data_llm_LocalLlmNative_nativeLoadMmproj(
        JNIEnv *env, jobject /* this */, jlong handle, jstring mmprojPath) {
    std::string path = jstring_to_std(env, mmprojPath);
    std::shared_ptr<LlmSession> s;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end() || !it->second->ready) return JNI_FALSE;
        s = it->second;
    }
    if (s->mtmd) {
        mtmd_free(s->mtmd);
        s->mtmd = nullptr;
    }
    mtmd_context_params mp = mtmd_context_params_default();
    mp.n_threads = s->n_threads;
    mp.use_gpu = false;
    mp.print_timings = false;
    s->mtmd = mtmd_init_from_file(path.c_str(), s->model, mp);
    if (!s->mtmd) {
        LOGE("Failed to load mmproj: %s", path.c_str());
        return JNI_FALSE;
    }
    LOGI("mmproj loaded: %s (vision=%d, audio=%d, mrope=%d)",
         path.c_str(),
         mtmd_support_vision(s->mtmd), mtmd_support_audio(s->mtmd),
         mtmd_decode_use_mrope(s->mtmd));
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_devhorizon_online_ggufchat_data_llm_LocalLlmNative_nativeHasMmproj(
        JNIEnv *env, jobject /* this */, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    return (it != g_sessions.end() && it->second->mtmd != nullptr) ? JNI_TRUE : JNI_FALSE;
}

// The media placeholder the Kotlin prompt builder must insert where the image goes.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_devhorizon_online_ggufchat_data_llm_LocalLlmNative_nativeMediaMarker(
        JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(mtmd_default_marker());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_devhorizon_online_ggufchat_data_llm_LocalLlmNative_nativeGenerateStreamingVision(
        JNIEnv *env, jobject /* this */,
        jlong handle, jstring prompt, jbyteArray rgbBytes, jint width, jint height,
        jint maxTokens, jfloat temp, jfloat topP,
        jstring stopToken, jobject callback) {

    std::string sprompt = jstring_to_std(env, prompt);
    std::string sstop   = jstring_to_std(env, stopToken);

    std::shared_ptr<LlmSession> s;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end() || !it->second->ready) {
            return env->NewStringUTF("[error: invalid model handle]");
        }
        s = it->second;
    }
    if (!s->mtmd) return env->NewStringUTF("[error: no mmproj]");

    s->cancel_flag.store(false, std::memory_order_relaxed);
    std::lock_guard<std::mutex> gen_lock(s->gen_mutex);
    if (!s->ready) return env->NewStringUTF("[error: model freed]");

    jsize nbytes = env->GetArrayLength(rgbBytes);
    if (nbytes <= 0 || width <= 0 || height <= 0) {
        return env->NewStringUTF("[error: empty image]");
    }
    std::vector<unsigned char> rgb((size_t) nbytes);
    env->GetByteArrayRegion(rgbBytes, 0, nbytes, reinterpret_cast<jbyte *>(rgb.data()));

    llama_memory_clear(llama_get_memory(s->ctx), true);

    mtmd_bitmap *bitmap = mtmd_bitmap_init((uint32_t) width, (uint32_t) height, rgb.data());
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    mtmd_input_text itext{ sprompt.c_str(), sprompt.size(), /*add_special*/ true, /*parse_special*/ true };
    const mtmd_bitmap *bitmaps[1] = { bitmap };

    int32_t tok_res = mtmd_tokenize(s->mtmd, chunks, &itext, bitmaps, 1);
    if (tok_res != 0) {
        LOGE("mtmd_tokenize failed: %d", tok_res);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        return env->NewStringUTF("[error: tokenize failed]");
    }

    if (s->cancel_flag.load(std::memory_order_relaxed)) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        return env->NewStringUTF("");
    }

    const int n_batch = 512;
    llama_pos n_past = 0;
    int32_t ev = mtmd_helper_eval_chunks(
        s->mtmd, s->ctx, chunks, 0, /*seq_id*/ 0, n_batch, /*logits_last*/ true, &n_past);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);

    if (ev != 0) {
        LOGE("mtmd_helper_eval_chunks failed: %d", ev);
        return env->NewStringUTF("[error: eval prompt failed]");
    }
    if (s->cancel_flag.load(std::memory_order_relaxed)) {
        LOGI("Vision cancelled after image encode");
        return env->NewStringUTF("");
    }
    LOGI("Vision prompt ready: n_past=%d", (int) n_past);

    // ---- generation ----
    std::string response;
    int n_generated = 0;
    llama_sampler *sampler = make_sampler(s.get(), temp, topP);

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "(Ljava/lang/String;)V");
    if (!onTokenMethod || !onCompleteMethod) {
        llama_sampler_free(sampler);
        return env->NewStringUTF("[error: invalid callback]");
    }

    static const char *kStopMarkers[] = { "<|im_end|>", "<|eot_id|>", "<|end|>", "<end_of_turn>", "</s>", "<turn|>" };

    llama_token prev_token = -1;
    int repeat_run = 0;
    std::vector<llama_token> recent;

    for (int i = 0; i < maxTokens; i++) {
        if (s->cancel_flag.load(std::memory_order_relaxed)) break;

        llama_token new_token = llama_sampler_sample(sampler, s->ctx, -1);
        llama_sampler_accept(sampler, new_token);

        if (new_token == llama_vocab_eos(s->vocab) || llama_vocab_is_eog(s->vocab, new_token)) break;

        if (new_token == prev_token) {
            if (++repeat_run >= 10) break;
        } else {
            prev_token = new_token;
            repeat_run = 0;
        }

        recent.push_back(new_token);
        if (recent.size() >= 15) {
            bool looped = false;
            const int minRepeats = 5, maxPeriod = 12;
            const int nr = (int) recent.size();
            for (int period = 1; period <= maxPeriod && !looped; period++) {
                if (period * minRepeats > nr) continue;
                bool match = true;
                for (int r = 1; r < minRepeats && match; r++) {
                    for (int k = 0; k < period; k++) {
                        if (recent[nr - 1 - k] != recent[nr - 1 - k - r * period]) { match = false; break; }
                    }
                }
                if (match) looped = true;
            }
            if (looped) break;
        }

        char buf[256];
        int n = llama_token_to_piece(s->vocab, new_token, buf, sizeof(buf), 0, true);
        if (n <= 0) {
            if (!decode_one(s.get(), new_token, n_past++)) break;
            continue;
        }
        buf[n] = '\0';
        std::string tokenStr(buf);

        bool stop_marker = false;
        for (const char *marker : kStopMarkers) {
            if (tokenStr.find(marker) != std::string::npos) { stop_marker = true; break; }
        }
        if (stop_marker) break;

        response += buf;
        if (!sstop.empty()) {
            auto p = response.find(sstop);
            if (p != std::string::npos) { response = response.substr(0, p); break; }
        }

        jstring jToken = env->NewStringUTF(tokenStr.c_str());
        env->CallVoidMethod(callback, onTokenMethod, jToken);
        env->DeleteLocalRef(jToken);

        n_generated++;

        if (!decode_one(s.get(), new_token, n_past++)) break;
    }

    llama_sampler_free(sampler);

    jstring jFullResponse = env->NewStringUTF(response.c_str());
    env->CallVoidMethod(callback, onCompleteMethod, jFullResponse);
    env->DeleteLocalRef(jFullResponse);

    LOGI("Vision generated %d tokens, response length: %zu", n_generated, response.size());
    return env->NewStringUTF(response.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_devhorizon_online_ggufchat_data_llm_LocalLlmNative_nativeFreeMemory(
        JNIEnv * /* env */, jobject /* this */) {
    // Return free native-arena memory to the OS (bionic, API 26+). Safe to call
    // at any time; the allocator only purges pages it no longer uses.
    mallopt(M_PURGE, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_devhorizon_online_ggufchat_data_llm_LocalLlmNative_nativeFreeModel(
        JNIEnv *env, jobject /* this */, jlong handle) {

    std::shared_ptr<LlmSession> s;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) return;
        s = it->second;
        g_sessions.erase(it);
    }

    s->ready = false;
    s->cancel_flag.store(true, std::memory_order_relaxed);

    std::lock_guard<std::mutex> gen_lock(s->gen_mutex);

    if (s->mtmd) {
        mtmd_free(s->mtmd);
        s->mtmd = nullptr;
    }
    if (s->ctx) {
        llama_free(s->ctx);
        s->ctx = nullptr;
    }
    s->vocab = nullptr;
    if (s->model) {
        llama_model_free(s->model);
        s->model = nullptr;
    }
    LOGI("Model freed, handle=%ld", (long)handle);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_devhorizon_online_ggufchat_data_llm_LocalLlmNative_nativeIsModelLoaded(
        JNIEnv *env, jobject /* this */, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_sessions.find(handle);
    return (it != g_sessions.end() && it->second->ready) ? JNI_TRUE : JNI_FALSE;
}
