#include <jni.h>
#include <llama.h>
#include <chat.h>
#include <nlohmann/json.hpp>

#include <atomic>
#include <chrono>
#include <new>
#include <string>
#include <vector>

namespace {
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

std::string errorJson(const std::string &message) {
    return "{\"error\":\"" + jsonEscape(message) + "\"}";
}

bool hasThinkingEnd(const std::string &value) {
    static const char *markers[] = {
        "</think>", "<｜end▁of▁thinking｜>", "<|end▁of▁thinking|>", "<|end_of_thinking|>"
    };
    for (const char *marker : markers) {
        if (value.find(marker) != std::string::npos) return true;
    }
    return false;
}

common_chat_msg message(const std::string &role, const std::string &content) {
    common_chat_msg result;
    result.role = role;
    result.content = content;
    return result;
}

bool decodePrompt(llama_context *context, const std::vector<llama_token> &tokens, int32_t batchSize) {
    llama_batch batch = llama_batch_init(batchSize, 0, 1);
    if (batch.token == nullptr) return false;
    bool ok = true;
    for (size_t offset = 0; offset < tokens.size() && ok; offset += batchSize) {
        int count = static_cast<int>(std::min(static_cast<size_t>(batchSize), tokens.size() - offset));
        batch.n_tokens = count;
        for (int i = 0; i < count; ++i) {
            batch.token[i] = tokens[offset + i];
            batch.pos[i] = static_cast<llama_pos>(offset + i);
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = offset + i == tokens.size() - 1 ? 1 : 0;
        }
        ok = llama_decode(context, batch) == 0;
    }
    llama_batch_free(batch);
    return ok;
}

std::string generateJson(
    const std::string &path,
    const std::string &systemPrompt,
    const std::string &contextJson,
    const std::string &historyJson,
    const std::string &userMessage,
    bool thinking,
    int maxTokens
) {
    cancelled = false;
    long modelLoadMs = 0;
    if (sessionModel == nullptr || sessionPath != path) {
        closeSession();
        llama_backend_init();
        backendInitialized = true;
        auto modelParams = llama_model_default_params();
        modelParams.n_gpu_layers = 0;
        auto loadStart = std::chrono::steady_clock::now();
        sessionModel = llama_model_load_from_file(path.c_str(), modelParams);
        if (sessionModel == nullptr) {
            closeSession();
            return errorJson("模型加载失败，请检查文件和可用内存");
        }
        auto contextParams = llama_context_default_params();
        contextParams.n_ctx = 4096;
        contextParams.n_batch = 512;
        contextParams.n_threads = 4;
        contextParams.n_threads_batch = 4;
        sessionContext = llama_init_from_model(sessionModel, contextParams);
        if (sessionContext == nullptr) {
            closeSession();
            return errorJson("推理上下文创建失败，可能内存不足");
        }
        sessionVocab = llama_model_get_vocab(sessionModel);
        sessionPath = path;
        modelLoadMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - loadStart).count();
    } else {
        llama_memory_clear(llama_get_memory(sessionContext), false);
    }

    std::string fullPrompt;
    try {
        auto templates = common_chat_templates_init(sessionModel, "");
        common_chat_templates_inputs inputs;
        inputs.use_jinja = true;
        inputs.enable_thinking = thinking;
        inputs.add_generation_prompt = true;
        inputs.messages.push_back(message("system", systemPrompt));
        inputs.messages.push_back(message("system", "以下内容为唯一可用的课程资料：\n" + contextJson));
        auto history = nlohmann::json::parse(historyJson);
        if (history.is_array()) {
            for (const auto &item : history) {
                std::string role = item.value("role", "");
                if (role == "user" || role == "assistant") {
                    inputs.messages.push_back(message(role, item.value("content", "")));
                }
            }
        }
        inputs.messages.push_back(message("user", userMessage));
        fullPrompt = common_chat_templates_apply(templates.get(), inputs).prompt.str();
    } catch (const std::exception &error) {
        closeSession();
        return errorJson(std::string("聊天模板或历史解析失败：") + error.what());
    }
    if (fullPrompt.empty()) {
        closeSession();
        return errorJson("聊天模板生成了空 prompt");
    }

    std::vector<llama_token> tokens(1024);
    int tokenCount = llama_tokenize(sessionVocab, fullPrompt.c_str(), fullPrompt.size(),
        tokens.data(), tokens.size(), true, true);
    if (tokenCount < 0) {
        tokens.resize(-tokenCount);
        tokenCount = llama_tokenize(sessionVocab, fullPrompt.c_str(), fullPrompt.size(),
            tokens.data(), tokens.size(), true, true);
    }
    if (tokenCount < 0) {
        closeSession();
        return errorJson("prompt 分词失败");
    }
    if (tokenCount > 4096 - 64) {
        closeSession();
        return errorJson("教材上下文仍超过 4096 token，请减少当前上下文");
    }
    tokens.resize(tokenCount);
    if (!decodePrompt(sessionContext, tokens, 512)) {
        closeSession();
        return errorJson("prompt 推理失败");
    }

    auto sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (sampler == nullptr) {
        closeSession();
        return errorJson("采样器初始化失败");
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string output;
    std::string stopReason;
    int generated = 0;
    for (int i = 0; i < maxTokens; ++i) {
        if (cancelled) {
            stopReason = "cancelled";
            break;
        }
        llama_token token = llama_sampler_sample(sampler, sessionContext, -1);
        if (llama_vocab_is_eog(sessionVocab, token)) {
            stopReason = "eog";
            break;
        }
        char piece[256];
        int length = llama_token_to_piece(sessionVocab, token, piece, sizeof(piece), 0, true);
        std::string pieceText = length > 0 ? std::string(piece, length) : "";
        if (pieceText == "<|im_end|>" || pieceText == "<|endoftext|>") {
            stopReason = "im_end";
            break;
        }
        llama_sampler_accept(sampler, token);
        if (length > 0) output.append(piece, length);
        generated++;
        if (tokenCount + generated >= 4095) {
            stopReason = "context_full";
            break;
        }
        llama_token next = token;
        if (llama_decode(sessionContext, llama_batch_get_one(&next, 1)) != 0) {
            llama_sampler_free(sampler);
            closeSession();
            return errorJson("生成过程中推理失败");
        }
    }
    if (stopReason.empty()) stopReason = "max_tokens";
    llama_sampler_free(sampler);

    std::string json = "{";
    json += "\"modelLoadMs\":" + std::to_string(modelLoadMs) + ",";
    json += "\"promptTokens\":" + std::to_string(tokenCount) + ",";
    json += "\"generatedTokens\":" + std::to_string(generated) + ",";
    json += "\"thinkingEnabled\":" + std::string(thinking ? "true" : "false") + ",";
    json += "\"stopReason\":\"" + stopReason + "\",";
    json += "\"output\":\"" + jsonEscape(output) + "\"}";
    return json;
}

std::string readString(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) throw std::bad_alloc();
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sierxue_english_data_local_LlamaCppEngine_nativeGenerate(
    JNIEnv *env, jobject, jstring modelPath, jstring systemPrompt, jstring contextJson,
    jstring historyJson, jstring userMessage, jboolean thinking, jint maxTokens
) {
    try {
        std::string result = generateJson(
            readString(env, modelPath), readString(env, systemPrompt), readString(env, contextJson),
            readString(env, historyJson), readString(env, userMessage), thinking == JNI_TRUE, maxTokens
        );
        return env->NewStringUTF(result.c_str());
    } catch (const std::bad_alloc &) {
        closeSession();
        return env->NewStringUTF(errorJson("内存不足，已释放未完成的模型会话").c_str());
    } catch (const std::exception &error) {
        closeSession();
        return env->NewStringUTF(errorJson(std::string("本地推理失败：") + error.what()).c_str());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sierxue_english_data_local_LlamaCppEngine_nativeCancel(JNIEnv *, jobject) {
    cancelled = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sierxue_english_data_local_LlamaCppEngine_nativeCloseModel(JNIEnv *, jobject) {
    cancelled = true;
    closeSession();
}
