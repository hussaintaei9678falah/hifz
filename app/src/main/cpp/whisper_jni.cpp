#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hifz_checker_Whisper_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) ctx;
}

JNIEXPORT jbyteArray JNICALL
Java_com_hifz_checker_Whisper_nativeRun(JNIEnv *env, jobject, jlong ptr, jfloatArray audio, jint threads) {
    auto *ctx = (struct whisper_context *) ptr;
    jsize n = env->GetArrayLength(audio);
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_progress = false;
    wp.print_special = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.translate = false;
    wp.language = "ar";
    wp.detect_language = false;
    wp.n_threads = threads;
    wp.no_timestamps = true;
    wp.single_segment = true;
    wp.no_context = true;
    wp.temperature_inc = 0.0f;
    wp.greedy.best_of = 1;

    std::string out;
    int rc = whisper_full(ctx, wp, data, n);
    if (rc == 0) {
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) out += whisper_full_get_segment_text(ctx, i);
    }
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);

    jbyteArray res = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(res, 0, (jsize) out.size(), (const jbyte *) out.data());
    return res;
}

}
