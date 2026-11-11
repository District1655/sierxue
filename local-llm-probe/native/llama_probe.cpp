#include <jni.h>
#include <llama.h>
#include <chat.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;

std::atomic<bool> cancelled{false};
llama_model *sessionModel = nullptr;
llama_context *sessionContext = nullptr;
const llama_vocab *sessionVocab = nullptr;
std::string sessionPath;
bool backendInitialized = false;

void closeSession() {
    if (sessionContext != nullptr) llama_free(sessionContext);
    if (sessionModel != nullptr) llama_model_free(sessionModel);
    sessionContext = nullptr;
    sessionModel = nullptr;
    sessionVocab = nullptr;
    sessionPath.clear();
    if (backendInitialized) llama_backend_free();
    backendInitialized = false;
}

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

bool hasThinkingEnd(const std::string &value) {
    static const char *markers[] = {
        "</think>",
        "<｜end▁of▁thinking｜>",
        "<|end▁of▁thinking|>",
        "<|end_of_thinking|>"
    };
    for (const char *marker : markers) {
        if (value.find(marker) != std::string::npos) return true;
    }
    return false;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sierxue_localprobe_MainActivity_nativeGenerate(
    JNIEnv *env,
    jobject,
    jstring modelPath,
    jstring prompt,
    jstring backend,
    jboolean thinkingEnabled,
    jint maxNewTokens
) {
    auto callStart = Clock::now();
    cancelled = false;
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

    long modelLoadMs = 0;
    if (sessionModel == nullptr || sessionPath != path) {
        closeSession();
        llama_backend_init();
        backendInitialized = true;
        auto modelParams = llama_model_default_params();
#ifdef PROBE_VULKAN
        modelParams.n_gpu_layers = -1;
#endif
        auto loadStart = Clock::now();
        sessionModel = llama_model_load_from_file(path.c_str(), modelParams);
        if (sessionModel == nullptr) {
            closeSession();
            return errorJson(env, "模型加载失败，请检查 GGUF 和可用内存");
        }
        auto contextParams = llama_context_default_params();
        contextParams.n_ctx = 4096;
        contextParams.n_batch = 512;
        contextParams.n_threads = 4;
        contextParams.n_threads_batch = 4;
        sessionContext = llama_init_from_model(sessionModel, contextParams);
        if (sessionContext == nullptr) {
            closeSession();
            return errorJson(env, "推理上下文创建失败，可能内存不足");
        }
        sessionVocab = llama_model_get_vocab(sessionModel);
        sessionPath = path;
        modelLoadMs = elapsedMs(loadStart);
    } else {
        llama_memory_clear(llama_get_memory(sessionContext), false);
    }
    llama_model *model = sessionModel;
    llama_context *context = sessionContext;
    const llama_vocab *vocab = sessionVocab;
    auto contextParams = llama_context_default_params();
    contextParams.n_ctx = 4096;
    contextParams.n_batch = 512;

    const bool thinking = thinkingEnabled == JNI_TRUE;
    std::string fullPrompt;
    try {
        auto chatTemplates = common_chat_templates_init(model, "");
        common_chat_templates_inputs inputs;
        inputs.use_jinja = true;
        inputs.enable_thinking = thinking;
        inputs.add_generation_prompt = true;
        common_chat_msg userMessage;
        userMessage.role = "user";
        userMessage.content = input;
        inputs.messages.push_back(userMessage);
        fullPrompt = common_chat_templates_apply(chatTemplates.get(), inputs).prompt.str();
    } catch (const std::exception &error) {
        closeSession();
        return errorJson(env, std::string("聊天模板应用失败：") + error.what());
    }
    if (fullPrompt.empty()) {
        closeSession();
        return errorJson(env, "聊天模板生成了空 prompt");
    }
    std::vector<llama_token> tokens(512);
    int tokenCount = llama_tokenize(
        vocab, fullPrompt.c_str(), static_cast<int32_t>(fullPrompt.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()), true, true
    );
    if (tokenCount < 0) {
        tokens.resize(-tokenCount);
        tokenCount = llama_tokenize(
            vocab, fullPrompt.c_str(), static_cast<int32_t>(fullPrompt.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), true, true
        );
    }
    if (tokenCount < 0 || tokenCount > contextParams.n_batch) {
        closeSession();
        return errorJson(env, "prompt 分词失败或超过 512 token，请缩短输入");
    }
    tokens.resize(tokenCount);
    auto generateStart = Clock::now();
    long generationStartOffsetMs = elapsedMs(callStart);
    if (llama_decode(context, llama_batch_get_one(tokens.data(), tokenCount)) != 0) {
        closeSession();
        return errorJson(env, "prompt 推理失败");
    }

    auto samplerParams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(samplerParams);
    if (sampler != nullptr) llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    if (sampler == nullptr) {
        closeSession();
        return errorJson(env, "采样器初始化失败");
    }

    std::string output;
    long firstTokenMs = -1;
    long thinkingMs = thinking ? -1 : 0;
    auto contentStart = Clock::now();
    int generated = 0;
    std::string stopReason;
    std::vector<long> tokenTimesMs;
    auto firstTokenStart = Clock::now();
    for (int i = 0; maxNewTokens < 0 || i < maxNewTokens; ++i) {
        if (cancelled) {
            stopReason = "cancelled";
            break;
        }
        llama_token token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            stopReason = "eog";
            break;
        }
        char piece[128];
        int length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        std::string pieceText = length > 0 ? std::string(piece, length) : "";
        if (pieceText == "<|im_end|>" || pieceText == "<|endoftext|>") {
            stopReason = "im_end";
            break;
        }
        llama_sampler_accept(sampler, token);
        if (generated == 0) {
            firstTokenMs = elapsedMs(generateStart);
            firstTokenStart = Clock::now();
        }
        if (length > 0) output.append(piece, length);
        generated++;
        tokenTimesMs.push_back(elapsedMs(generateStart));
        if (thinking && thinkingMs < 0 && hasThinkingEnd(output)) {
            thinkingMs = elapsedMs(generateStart);
            contentStart = Clock::now();
        }
        if (tokenCount + generated >= contextParams.n_ctx - 1) {
            stopReason = "context_full";
            break;
        }
        llama_token next = token;
        if (llama_decode(context, llama_batch_get_one(&next, 1)) != 0) {
            llama_sampler_free(sampler);
            closeSession();
            return errorJson(env, "生成过程中推理失败");
        }
    }
    if (stopReason.empty()) stopReason = maxNewTokens < 0 ? "context_full" : "max_tokens";
    long generationMs = elapsedMs(firstTokenStart);
    long allGenerationMs = elapsedMs(generateStart);
    if (thinking) {
        if (thinkingMs < 0) thinkingMs = allGenerationMs;
    }
    long contentGenerationMs = thinking && thinkingMs >= 0 && hasThinkingEnd(output)
        ? elapsedMs(contentStart)
        : (thinking ? 0 : allGenerationMs);
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

    std::string json = "{";
    json += "\"modelLoadMs\":" + std::to_string(modelLoadMs) + ",";
    json += "\"generationStartOffsetMs\":" + std::to_string(generationStartOffsetMs) + ",";
    json += "\"firstTokenMs\":" + std::to_string(firstTokenMs) + ",";
    json += "\"generatedTokens\":" + std::to_string(generated) + ",";
    json += "\"thinkingMs\":" + std::to_string(thinkingMs) + ",";
    json += "\"contentGenerationMs\":" + std::to_string(contentGenerationMs) + ",";
    json += "\"thinkingEnabled\":" + std::string(thinking ? "true" : "false") + ",";
    json += "\"maxNewTokens\":" + std::to_string(maxNewTokens) + ",";
    json += "\"stopReason\":\"" + stopReason + "\",";
    json += "\"tokensPerSecond\":\"" + std::to_string(speed) + "\",";
    json += "\"backend\":\"" + jsonEscape(backendName) + "\",";
    json += "\"tokenTimesMs\":[";
    for (size_t i = 0; i < tokenTimesMs.size(); ++i) {
        if (i > 0) json += ",";
        json += std::to_string(tokenTimesMs[i]);
    }
    json += "],";
    json += "\"output\":\"" + jsonEscape(output) + "\"}";
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sierxue_localprobe_MainActivity_nativeCancel(JNIEnv *, jobject) {
    cancelled = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sierxue_localprobe_MainActivity_nativeCloseModel(JNIEnv *, jobject) {
    cancelled = true;
    closeSession();
}
