#include <jni.h>
#include <atomic>
#include <cstdio>
#include <mutex>
#include <string>

#include "whisper.h"

namespace {

JavaVM * g_vm = nullptr;

struct NativeSession {
    whisper_context * context = nullptr;
    std::atomic_bool cancelled{false};
    std::mutex inference_mutex;
};

struct ProgressContext {
    jobject callback = nullptr;
    jmethodID on_progress = nullptr;
};

NativeSession * session_from(jlong handle) {
    return reinterpret_cast<NativeSession *>(static_cast<intptr_t>(handle));
}

bool on_abort(void * user_data) {
    const auto * session = static_cast<NativeSession *>(user_data);
    return session == nullptr || session->cancelled.load(std::memory_order_relaxed);
}

void on_progress(struct whisper_context *, struct whisper_state *, int progress, void * user_data) {
    auto * progress_context = static_cast<ProgressContext *>(user_data);
    if (progress_context == nullptr || progress_context->callback == nullptr || progress_context->on_progress == nullptr) return;
    JNIEnv * env = nullptr;
    bool detach = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        detach = true;
    }
    env->CallVoidMethod(progress_context->callback, progress_context->on_progress, progress);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) g_vm->DetachCurrentThread();
}

std::string json_escape(const char * value) {
    std::string output;
    if (value == nullptr) return output;
    for (const unsigned char * p = reinterpret_cast<const unsigned char *>(value); *p != 0; ++p) {
        switch (*p) {
            case '\\': output += "\\\\"; break;
            case '"': output += "\\\""; break;
            case '\n': output += "\\n"; break;
            case '\r': output += "\\r"; break;
            case '\t': output += "\\t"; break;
            default:
                if (*p < 0x20) {
                    char buffer[7];
                    snprintf(buffer, sizeof(buffer), "\\u%04x", *p);
                    output += buffer;
                } else output += static_cast<char>(*p);
        }
    }
    return output;
}

jstring error_result(JNIEnv * env, const char * code) {
    return env->NewStringUTF((std::string("{\"error\":\"") + code + "\"}").c_str());
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_atan_starkaudio_transcription_WhisperNativeBridge_createSession(JNIEnv * env, jobject, jstring model_path) {
    if (model_path == nullptr) return 0;
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;
    whisper_context_params params = whisper_context_default_params();
    whisper_context * context = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    if (context == nullptr) return 0;
    auto * session = new NativeSession();
    session->context = context;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

extern "C" JNIEXPORT void JNICALL
Java_com_atan_starkaudio_transcription_WhisperNativeBridge_cancel(JNIEnv *, jobject, jlong handle) {
    if (auto * session = session_from(handle)) session->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_atan_starkaudio_transcription_WhisperNativeBridge_resetSession(JNIEnv *, jobject, jlong handle) {
    if (auto * session = session_from(handle)) session->cancelled.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_atan_starkaudio_transcription_WhisperNativeBridge_destroySession(JNIEnv *, jobject, jlong handle) {
    auto * session = session_from(handle);
    if (session == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(session->inference_mutex);
        if (session->context != nullptr) whisper_free(session->context);
        session->context = nullptr;
    }
    delete session;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_atan_starkaudio_transcription_WhisperNativeBridge_transcribeChunk(
        JNIEnv * env,
        jobject,
        jlong handle,
        jfloatArray pcm,
        jstring language,
        jint threads,
        jobject callback) {
    auto * session = session_from(handle);
    if (session == nullptr || session->context == nullptr || pcm == nullptr) return error_result(env, "invalid_session");
    std::lock_guard<std::mutex> lock(session->inference_mutex);
    session->cancelled.store(false, std::memory_order_relaxed);

    const jsize sample_count = env->GetArrayLength(pcm);
    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) return error_result(env, "pcm_unavailable");

    ProgressContext progress_context;
    if (callback != nullptr) {
        progress_context.callback = env->NewGlobalRef(callback);
        jclass callback_class = env->GetObjectClass(callback);
        progress_context.on_progress = env->GetMethodID(callback_class, "onProgress", "(I)V");
        env->DeleteLocalRef(callback_class);
    }

    std::string language_storage = "auto";
    if (language != nullptr) {
        const char * language_chars = env->GetStringUTFChars(language, nullptr);
        if (language_chars != nullptr && language_chars[0] != '\0') language_storage = language_chars;
        if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 1;
    params.translate = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_context = true;
    params.single_segment = false;
    params.language = language_storage.c_str();
    // "auto" already requests detection. detect_language=true is detection-only
    // and returns before decoding any text.
    params.detect_language = false;
    params.abort_callback = on_abort;
    params.abort_callback_user_data = session;
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &progress_context;

    const int result = whisper_full(session->context, params, samples, sample_count);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (progress_context.callback != nullptr) env->DeleteGlobalRef(progress_context.callback);
    if (result != 0) return error_result(env, session->cancelled.load() ? "cancelled" : "inference_failed");

    const int language_id = whisper_full_lang_id(session->context);
    const char * detected = language_id >= 0 ? whisper_lang_str(language_id) : "";
    std::string json = "{\"detectedLanguage\":\"" + json_escape(detected) + "\",\"segments\":[";
    const int segment_count = whisper_full_n_segments(session->context);
    for (int i = 0; i < segment_count; ++i) {
        if (i > 0) json += ',';
        json += "{\"startMs\":" + std::to_string(whisper_full_get_segment_t0(session->context, i) * 10);
        json += ",\"endMs\":" + std::to_string(whisper_full_get_segment_t1(session->context, i) * 10);
        json += ",\"text\":\"" + json_escape(whisper_full_get_segment_text(session->context, i)) + "\"}";
    }
    json += "]}";
    return env->NewStringUTF(json.c_str());
}
