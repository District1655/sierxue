#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <chrono>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;

long elapsedMs(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

std::string jsonEscape(const std::string &value) {
    std::string out;
    out.reserve(value.size() + 16);
    for (unsigned char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) out += ' ';
                else out += static_cast<char>(c);
        }
    }
    return out;
}

jstring errorJson(JNIEnv *env, const std::string &message) {
    std::string json = "{\"error\":\"" + jsonEscape(message) + "\"}";
    return env->NewStringUTF(json.c_str());
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sierxue_localprobe_MainActivity_nativeGenerate(
    JNIEnv *env,
    jobject,
    jstring modelPath,
    jstring prompt,
    jstring backend
) {
    if (modelPath == nullptr || prompt == nullptr) return errorJson(env, "模型路径或 prompt 为空");
    const char *pathChars = env->GetStringUTFChars(modelPath, nullptr);
    const char *promptChars = env->GetStringUTFChars(prompt, nullptr);
    if (pathChars == nullptr || promptChars == nullptr) {
        if (pathChars) env->ReleaseStringUTFChars(modelPath, pathChars);
        if (promptChars) env->ReleaseStringUTFChars(prompt, promptChars);
        return errorJson(env, "无法读取输入");
    }
    std::string path(pathChars);
    std::string input(promptChars);
    env->ReleaseStringUTFChars(modelPath, pathChars);
    env->ReleaseStringUTFChars(prompt, promptChars);

    llama_backend_init();
    auto modelParams = llama_model_default_params();
#ifdef PROBE_VULKAN
    modelParams.n_gpu_layers = -1;
#endif
    auto loadStart = Clock::now();
    llama_model *model = llama_model_load_from_file(path.c_str(), modelParams);
    if (model == nullptr) {
        llama_backend_free();
        return errorJson(env, "模型加载失败，请检查 GGUF 和可用内存");
    }
    auto contextParams = llama_context_default_params();
    contextParams.n_ctx = 1024;
    contextParams.n_batch = 128;
    contextParams.n_threads = 4;
    contextParams.n_threads_batch = 4;
    llama_context *context = llama_init_from_model(model, contextParams);
    if (context == nullptr) {
        llama_model_free(model);
        llama_backend_free();
        return errorJson(env, "推理上下文创建失败，可能内存不足");
    }
    long modelLoadMs = elapsedMs(loadStart);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    std::string fullPrompt = "User: " + input + "\nAssistant:";
    std::vector<llama_token> tokens(512);
    int tokenCount = llama_tokenize(
        vocab, fullPrompt.c_str(), static_cast<int32_t>(fullPrompt.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()), true, true
    );
    if (tokenCount < 0) {
        llama_free(context);
        llama_model_free(model);
        llama_backend_free();
        return errorJson(env, "prompt 分词失败");
    }
    tokens.resize(tokenCount);
    auto generateStart = Clock::now();
    if (llama_decode(context, llama_batch_get_one(tokens.data(), tokenCount)) != 0) {
        llama_free(context);
        llama_model_free(model);
        llama_backend_free();
        return errorJson(env, "prompt 推理失败");
    }

    auto samplerParams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(samplerParams);
    if (sampler != nullptr) llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    if (sampler == nullptr) {
        llama_free(context);
        llama_model_free(model);
        llama_backend_free();
        return errorJson(env, "采样器初始化失败");
    }

    std::string output;
    long firstTokenMs = -1;
    int generated = 0;
    auto firstTokenStart = Clock::now();
    for (int i = 0; i < 96; ++i) {
        llama_token token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        llama_sampler_accept(sampler, token);
        if (generated == 0) {
            firstTokenMs = elapsedMs(generateStart);
            firstTokenStart = Clock::now();
        }
        char piece[128];
        int length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (length > 0) output.append(piece, length);
        generated++;
        llama_token next = token;
        if (llama_decode(context, llama_batch_get_one(&next, 1)) != 0) {
            llama_sampler_free(sampler);
            llama_free(context);
            llama_model_free(model);
            llama_backend_free();
            return errorJson(env, "生成过程中推理失败");
        }
    }
    long generationMs = elapsedMs(firstTokenStart);
    double speed = generated > 1 && generationMs > 0 ? (generated - 1) * 1000.0 / generationMs : 0.0;
    std::string backendName = "unknown";
    if (backend != nullptr) {
        const char *chars = env->GetStringUTFChars(backend, nullptr);
        if (chars != nullptr) {
            backendName = chars;
            env->ReleaseStringUTFChars(backend, chars);
        }
    }

    llama_sampler_free(sampler);
    llama_free(context);
    llama_model_free(model);
    llama_backend_free();

    std::string json = "{";
    json += "\"modelLoadMs\":" + std::to_string(modelLoadMs) + ",";
    json += "\"firstTokenMs\":" + std::to_string(firstTokenMs) + ",";
    json += "\"generatedTokens\":" + std::to_string(generated) + ",";
    json += "\"tokensPerSecond\":\"" + std::to_string(speed) + "\",";
    json += "\"backend\":\"" + jsonEscape(backendName) + "\",";
    json += "\"output\":\"" + jsonEscape(output) + "\"}";
    return env->NewStringUTF(json.c_str());
}
