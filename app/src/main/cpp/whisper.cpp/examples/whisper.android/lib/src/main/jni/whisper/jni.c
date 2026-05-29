#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"
#include <math.h>
#include "sinc_lut.h"


#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    jobject thiz;
    jobject input_stream;

    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);

    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);

    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy;

    return size_to_copy;
}
bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
void inputStreamClose(void * ctx) {

}

JNIEXPORT jlong JNICALL
Java_com_whispercppdemo_whisper_LibWhisper_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream, jboolean use_gpu) {
    UNUSED(thiz);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    loader.eof(loader.context);

    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = use_gpu;
    context = whisper_init_with_params(&loader, params);
    return (jlong) context;
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path,
        bool use_gpu
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = use_gpu;
    return whisper_init_with_params(&loader, params);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str, jboolean use_gpu) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars, use_gpu);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str, jboolean use_gpu) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = use_gpu;
    context = whisper_init_from_file_with_params(model_path_chars, params);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    // The below adapted from the Objective-C iOS sample
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = true;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    whisper_reset_timings(context);

    LOGI("About to run whisper_full");
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGI("Failed to run the model");
    } else {
        whisper_print_timings(context);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

JNIEXPORT jint JNICALL Java_com_whispercpp_whisper_LibWhisper_00024Companion_fullTranscribeWithTokens(
    JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data, jintArray prev_tokens, jint token_count) {
    
    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.no_context = true; 
    params.n_threads = num_threads;
    params.print_progress = false;
    params.print_realtime = true;

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize n_samples = (*env)->GetArrayLength(env, audio_data);
    jint *tokens = NULL;
    
    if (prev_tokens != NULL && token_count > 0) {
        tokens = (*env)->GetIntArrayElements(env, prev_tokens, NULL);
        params.prompt_tokens = (whisper_token *)tokens;
        params.prompt_n_tokens = token_count;
    }

    int result = whisper_full(ctx, params, samples, n_samples);

    (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);
    if (tokens != NULL) {
        (*env)->ReleaseIntArrayElements(env, prev_tokens, tokens, JNI_ABORT);
    }
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_nativeResample(
        JNIEnv *env, jobject thiz, jshortArray input_samples, jint source_sample_rate) {
    UNUSED(thiz);
    
    jsize input_len = (*env)->GetArrayLength(env, input_samples);
    jshort *shorts = (*env)->GetShortArrayElements(env, input_samples, NULL);
    
    double ratio = (double)source_sample_rate / 16000.0;
    jsize output_len = (jsize)(input_len / ratio);
    
    jfloatArray result = (*env)->NewFloatArray(env, output_len);
    jfloat *output_floats = (*env)->GetFloatArrayElements(env, result, NULL);
    
    for (jsize i = 0; i < output_len; ++i) {
        double center_src = i * ratio;
        int center_idx = (int)center_src;
        
        float sample_val = 0.0f;
        float weight_sum = 0.0f;
        
        for (int j = -SINC_ZERO_CROSSINGS; j <= SINC_ZERO_CROSSINGS; ++j) {
            int src_idx = center_idx + j;
            if (src_idx >= 0 && src_idx < input_len) {
                float distance = (float)(center_src - src_idx);
                float abs_distance = fabsf(distance);
                
                int lut_idx = (int)(abs_distance * SINC_OVERSAMPLING);
                if (lut_idx >= 0 && lut_idx < SINC_LUT_SIZE) {
                    float weight = SINC_LUT[lut_idx];
                    sample_val += (shorts[src_idx] / 32768.0f) * weight;
                    weight_sum += weight;
                }
            }
        }
        
        if (weight_sum > 0.0f) {
            sample_val /= weight_sum;
        }
        if (sample_val > 1.0f) sample_val = 1.0f;
        if (sample_val < -1.0f) sample_val = -1.0f;
        
        output_floats[i] = sample_val;
    }
    
    (*env)->ReleaseFloatArrayElements(env, result, output_floats, 0);
    (*env)->ReleaseShortArrayElements(env, input_samples, shorts, JNI_ABORT);
    
    return result;
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_LibWhisper_nativeResampleDirect(
        JNIEnv *env, jobject thiz, jshortArray input_samples, jint source_sample_rate, jobject byte_buffer) {
    UNUSED(thiz);
    
    jsize input_len = (*env)->GetArrayLength(env, input_samples);
    jshort *shorts = (*env)->GetShortArrayElements(env, input_samples, NULL);
    
    // 1. Extract raw native memory pointer addresses out of the pre-allocated JVM Direct Buffer wrapper
    jfloat *output_floats = (jfloat *)(*env)->GetDirectBufferAddress(env, byte_buffer);
    jlong buffer_capacity_bytes = (*env)->GetDirectBufferCapacity(env, byte_buffer);
    
    // Safety check: Gracefully abort execution if memory maps are invalid or null
    if (output_floats == NULL || buffer_capacity_bytes <= 0) {
        (*env)->ReleaseShortArrayElements(env, input_samples, shorts, JNI_ABORT);
        return 0;
    }
    
    double ratio = (double)source_sample_rate / 16000.0;
    jsize output_len = (jsize)(input_len / ratio);
    
    // 2. HARDENED CAPACITY BUFFER BOUNDS GATING: Complete buffer overflow insurance
    jlong max_allowed_floats = buffer_capacity_bytes / sizeof(jfloat);
    if (output_len > max_allowed_floats) {
        output_len = (jsize)max_allowed_floats;
    }
    
    // 3. Auto-vectorized, branchless Windowed-Sinc Lookup calculation loop (-O3)
    for (jsize i = 0; i < output_len; ++i) {
        double center_src = i * ratio;
        int center_idx = (int)center_src;
        
        float sample_val = 0.0f;
        float weight_sum = 0.0f;
        
        for (int j = -SINC_ZERO_CROSSINGS; j <= SINC_ZERO_CROSSINGS; ++j) {
            int src_idx = center_idx + j;
            if (src_idx >= 0 && src_idx < input_len) {
                float distance = (float)(center_src - src_idx);
                float abs_distance = fabsf(distance); // Symmetrical lookups
                
                int lut_idx = (int)(abs_distance * SINC_OVERSAMPLING);
                
                // Double-sided index gate protects the .rodata static matrix structures securely
                if (lut_idx >= 0 && lut_idx < SINC_LUT_SIZE) {
                    float weight = SINC_LUT[lut_idx];
                    sample_val += (shorts[src_idx] / 32768.0f) * weight;
                    weight_sum += weight;
                }
            }
        }
        
        if (weight_sum > 0.0f) sample_val /= weight_sum;
        
        // Inline clamp values directly into the mapped direct address memory zones
        output_floats[i] = fmaxf(-1.0f, fminf(1.0f, sample_val));
    }
    
    // 4. CLEAN SYMMETRIC DISPOSAL: Release short array pinned mappings safely
    (*env)->ReleaseShortArrayElements(env, input_samples, shorts, JNI_ABORT);
    
    // Return explicit count of populated float elements mapped directly back to Kotlin
    return output_len;
}



JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

JNIEXPORT jintArray JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_getTextSegmentTokens(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    
    int n_tokens = whisper_full_n_tokens(context, index);
    jintArray result = (*env)->NewIntArray(env, n_tokens);
    jint *tokens = (*env)->GetIntArrayElements(env, result, NULL);
    
    for (int i = 0; i < n_tokens; ++i) {
        tokens[i] = whisper_full_get_token_id(context, index, i);
    }
    
    (*env)->ReleaseIntArrayElements(env, result, tokens, 0);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_benchMemcpy(JNIEnv *env, jobject thiz,
                                                                      jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_LibWhisper_00024Companion_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                                          jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);
    return string;
}
