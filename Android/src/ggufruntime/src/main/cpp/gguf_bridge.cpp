#include "ai_chat.cpp"
#include "mtmd.h"
#include "mtmd-helper.h"

static mtmd_context * g_mtmd_context = nullptr;

static void close_projector() {
    if (g_mtmd_context != nullptr) {
        mtmd_free(g_mtmd_context);
        g_mtmd_context = nullptr;
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_loadProjector(
        JNIEnv * env,
        jobject,
        jstring jprojector_path) {
    close_projector();
    if (g_model == nullptr) {
        return 1;
    }
    const char * projector_path = env->GetStringUTFChars(jprojector_path, nullptr);
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.print_timings = false;
    g_mtmd_context = mtmd_init_from_file(projector_path, g_model, params);
    env->ReleaseStringUTFChars(jprojector_path, projector_path);
    return g_mtmd_context == nullptr ? 2 : 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unloadProjector(JNIEnv *, jobject) {
    close_projector();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processImagePrompt(
        JNIEnv * env,
        jobject,
        jstring juser_prompt,
        jbyteArray jimage_bytes,
        jint n_predict) {
    if (g_mtmd_context == nullptr || g_context == nullptr || g_model == nullptr) {
        return 1;
    }
    reset_short_term_states();

    const char * user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    std::string content = std::string(mtmd_default_marker()) + "\n" + user_prompt;
    std::string formatted = chat_add_and_format(ROLE_USER, content);
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    const jsize image_size = env->GetArrayLength(jimage_bytes);
    if (image_size <= 0) {
        return 2;
    }
    jbyte * image_data = env->GetByteArrayElements(jimage_bytes, nullptr);
    mtmd_helper_bitmap_wrapper bitmap_wrapper =
            mtmd_helper_bitmap_init_from_buf(
                    g_mtmd_context,
                    reinterpret_cast<const unsigned char *>(image_data),
                    static_cast<size_t>(image_size),
                    false);
    env->ReleaseByteArrayElements(jimage_bytes, image_data, JNI_ABORT);
    if (bitmap_wrapper.bitmap == nullptr) {
        return 3;
    }

    mtmd_input_text text{};
    text.text = formatted.c_str();
    text.add_special = chat_msgs.size() <= 1;
    text.parse_special = true;
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[] = { bitmap_wrapper.bitmap };
    const int tokenize_result =
            mtmd_tokenize(g_mtmd_context, chunks, &text, bitmaps, 1);
    if (tokenize_result != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap_wrapper.bitmap);
        return 4;
    }

    llama_pos new_position = current_position;
    const int eval_result =
            mtmd_helper_eval_chunks(
                    g_mtmd_context,
                    g_context,
                    chunks,
                    current_position,
                    0,
                    BATCH_SIZE,
                    true,
                    &new_position);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap_wrapper.bitmap);
    if (eval_result != 0) {
        return 5;
    }

    current_position = new_position;
    stop_generation_position = current_position + n_predict;
    return 0;
}
