package io.github.azureland;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Request, response and continuation formats for all four providers.
final class AiFormats {
    private AiFormats() { }

    static JsonObject inputPayload(AiSettings settings, List<Conversation.Message> messages) {
        JsonObject body = new JsonObject();
        JsonArray history = new JsonArray();
        JsonArray tools = new JsonArray();
        String effort = settings.reasoningEffort();
        switch (settings.provider()) {
            case "openai-responses" -> {
                body.addProperty("model", settings.model());
                body.addProperty("instructions", settings.systemPrompt());
                body.addProperty("stream", false);
                body.addProperty("store", false);
                body.addProperty("max_output_tokens", settings.maxOutputTokens());
                JsonArray include = new JsonArray();
                include.add("reasoning.encrypted_content");
                body.add("include", include);
                if (!effort.isEmpty()) {
                    JsonObject reasoning = new JsonObject();
                    reasoning.addProperty("effort", effort);
                    body.add("reasoning", reasoning);
                }
                for (Conversation.Message message : messages) {
                    history.add(message(message.role(), message.content()));
                }
                body.add("input", history);
                if (settings.webSearchEnabled()) {
                    JsonObject tool = new JsonObject();
                    tool.addProperty("type", "web_search");
                    tools.add(tool);
                }
                if (settings.pluginListEnabled()) {
                    JsonObject function = pluginFunction();
                    function.addProperty("type", "function");
                    function.addProperty("strict", true);
                    JsonObject schema = schema();
                    schema.addProperty("additionalProperties", false);
                    function.add("parameters", schema);
                    tools.add(function);
                }
            }
            case "openai" -> {
                body.addProperty("model", settings.model());
                body.addProperty("stream", false);
                body.addProperty("store", false);
                body.addProperty("max_completion_tokens", settings.maxOutputTokens());
                if (!effort.isEmpty()) {
                    body.addProperty("reasoning_effort", effort);
                }
                if (!settings.systemPrompt().isEmpty()) {
                    history.add(message("system", settings.systemPrompt()));
                }
                for (Conversation.Message message : messages) {
                    history.add(message(message.role(), message.content()));
                }
                body.add("messages", history);
                if (settings.webSearchEnabled()) {
                    body.add("web_search_options", new JsonObject());
                }
                if (settings.pluginListEnabled()) {
                    JsonObject function = pluginFunction();
                    function.addProperty("strict", true);
                    JsonObject schema = schema();
                    schema.addProperty("additionalProperties", false);
                    function.add("parameters", schema);
                    JsonObject tool = new JsonObject();
                    tool.addProperty("type", "function");
                    tool.add("function", function);
                    tools.add(tool);
                }
            }
            case "anthropic" -> {
                body.addProperty("model", settings.model());
                body.addProperty("stream", false);
                body.addProperty("max_tokens", settings.maxOutputTokens());
                if (!settings.systemPrompt().isEmpty()) {
                    body.addProperty("system", settings.systemPrompt());
                }
                if (!effort.isEmpty()) {
                    JsonObject thinking = new JsonObject();
                    thinking.addProperty("type", "adaptive");
                    body.add("thinking", thinking);
                    JsonObject config = new JsonObject();
                    config.addProperty("effort", effort);
                    body.add("output_config", config);
                }
                for (Conversation.Message message : messages) {
                    history.add(message(message.role(), message.content()));
                }
                body.add("messages", history);
                if (settings.webSearchEnabled()) {
                    JsonObject tool = new JsonObject();
                    tool.addProperty("type", "web_search_20250305");
                    tool.addProperty("name", "web_search");
                    tool.addProperty("max_uses", 5);
                    tools.add(tool);
                }
                if (settings.pluginListEnabled()) {
                    JsonObject function = pluginFunction();
                    function.add("input_schema", schema());
                    tools.add(function);
                }
            }
            case "gemini" -> {
                if (!settings.systemPrompt().isEmpty()) {
                    body.add("systemInstruction", geminiContent("system", settings.systemPrompt()));
                }
                for (Conversation.Message message : messages) {
                    history.add(geminiContent(message.role().equals("assistant") ? "model" : "user",
                            message.content()));
                }
                body.add("contents", history);
                JsonObject generation = new JsonObject();
                generation.addProperty("maxOutputTokens", settings.maxOutputTokens());
                if (!effort.isEmpty()) {
                    JsonObject thinking = new JsonObject();
                    if (effort.matches("-?[0-9]+")) {
                        thinking.addProperty("thinkingBudget", Integer.parseInt(effort));
                    } else {
                        thinking.addProperty("thinkingLevel", effort.toUpperCase(Locale.ROOT));
                    }
                    generation.add("thinkingConfig", thinking);
                }
                body.add("generationConfig", generation);
                if (settings.webSearchEnabled()) {
                    JsonObject tool = new JsonObject();
                    tool.add("googleSearch", new JsonObject());
                    tools.add(tool);
                }
                if (settings.pluginListEnabled()) {
                    JsonObject tool = new JsonObject();
                    JsonArray functions = new JsonArray();
                    functions.add(pluginFunction());
                    tool.add("functionDeclarations", functions);
                    tools.add(tool);
                }
                if (settings.webSearchEnabled() && settings.pluginListEnabled()) {
                    JsonObject config = new JsonObject();
                    config.addProperty("includeServerSideToolInvocations", true);
                    body.add("toolConfig", config);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported provider");
        }
        if (!tools.isEmpty()) {
            body.add("tools", tools);
        }
        return body;
    }

    private static JsonObject message(String role, String content) {
        JsonObject item = new JsonObject();
        item.addProperty("role", role);
        item.addProperty("content", content);
        return item;
    }

    private static JsonObject geminiContent(String role, String text) {
        JsonObject item = new JsonObject();
        // systemInstruction only accepts text parts; its role is not needed.
        if (!role.equals("system")) {
            item.addProperty("role", role);
        }
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        parts.add(part);
        item.add("parts", parts);
        return item;
    }

    private static JsonObject pluginFunction() {
        JsonObject function = new JsonObject();
        function.addProperty("name", "get_server_plugins");
        function.addProperty("description", "获取本服务器已加载的插件名称、版本和启用状态。需要了解服务器插件时调用；不读取配置或执行命令。");
        return function;
    }

    private static JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.add("required", new JsonArray());
        return schema;
    }

    static JsonObject countPayload(AiSettings settings, JsonObject body) {
        if (settings.provider().equals("gemini")) {
            JsonObject counted = new JsonObject();
            JsonObject request = body.deepCopy();
            request.addProperty("model", "models/" + settings.model());
            counted.add("generateContentRequest", request);
            return counted;
        }
        JsonObject counted = new JsonObject();
        List<String> fields = switch (settings.provider()) {
            case "openai-responses" -> List.of("model", "instructions", "input", "tools");
            case "openai" -> List.of("model", "messages", "tools", "web_search_options");
            case "anthropic" -> List.of("model", "system", "messages", "tools", "thinking");
            default -> throw new IllegalArgumentException("Unsupported provider");
        };
        for (String field : fields) {
            if (body.has(field)) {
                counted.add(field, body.get(field));
            }
        }
        return counted;
    }

    static long parseInputCount(String provider, String json) throws IOException {
        try {
            Long count = tokenCount(JsonParser.parseString(json).getAsJsonObject(),
                    provider.equals("gemini") ? "totalTokens" : "input_tokens");
            if (count == null) {
                throw new IllegalArgumentException("Missing input token count");
            }
            return count;
        } catch (RuntimeException ex) {
            throw new AiClient.ApiException("AI token 计数接口返回了无效数据。");
        }
    }

    static void continueResponse(String provider, JsonObject body, AiClient.Response response, String pluginList) {
        if (response.functionCalls().isEmpty()) {
            // Claude pause_turn resumes without a local function result.
            appendClaudeOutput(body, response.output());
            return;
        }
        switch (provider) {
            case "openai-responses" -> {
                JsonArray history = body.getAsJsonArray("input");
                // Preserve reasoning, search and function call items for the stateless continuation.
                history.addAll(response.output());
                for (AiClient.FunctionCall call : response.functionCalls()) {
                    JsonObject result = new JsonObject();
                    result.addProperty("type", "function_call_output");
                    result.addProperty("call_id", call.id());
                    result.addProperty("output", pluginList);
                    history.add(result);
                }
            }
            case "openai" -> {
                JsonArray history = body.getAsJsonArray("messages");
                history.add(response.output().get(0));
                for (AiClient.FunctionCall call : response.functionCalls()) {
                    JsonObject result = message("tool", pluginList);
                    result.addProperty("tool_call_id", call.id());
                    history.add(result);
                }
            }
            case "anthropic" -> {
                JsonArray history = body.getAsJsonArray("messages");
                JsonArray results = new JsonArray();
                appendClaudeOutput(body, response.output());
                for (AiClient.FunctionCall call : response.functionCalls()) {
                    JsonObject result = new JsonObject();
                    result.addProperty("type", "tool_result");
                    result.addProperty("tool_use_id", call.id());
                    result.addProperty("content", pluginList);
                    results.add(result);
                }
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.add("content", results);
                history.add(message);
            }
            case "gemini" -> {
                JsonArray history = body.getAsJsonArray("contents");
                JsonArray results = new JsonArray();
                // Keep ALL original parts, including thought signatures and server-side tools.
                history.add(response.output().get(0));
                for (AiClient.FunctionCall call : response.functionCalls()) {
                    JsonObject result = new JsonObject();
                    result.addProperty("name", call.name());
                    if (!call.id().isEmpty()) {
                        result.addProperty("id", call.id());
                    }
                    result.add("response", JsonParser.parseString(pluginList));
                    JsonObject part = new JsonObject();
                    part.add("functionResponse", result);
                    results.add(part);
                }
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.add("parts", results);
                history.add(message);
            }
            default -> throw new IllegalArgumentException("Unsupported provider");
        }
        // Only one plugin-list call is needed per question; server-side search stays available.
        JsonArray tools = new JsonArray();
        for (JsonElement element : body.getAsJsonArray("tools")) {
            JsonObject tool = element.getAsJsonObject();
            boolean local = switch (provider) {
                case "anthropic" -> string(tool, "name").equals("get_server_plugins");
                case "gemini" -> tool.has("functionDeclarations");
                default -> string(tool, "type").equals("function");
            };
            if (!local) {
                tools.add(tool);
            }
        }
        if (tools.isEmpty()) {
            body.remove("tools");
        } else {
            body.add("tools", tools);
        }
    }

    private static void appendClaudeOutput(JsonObject body, JsonArray output) {
        JsonArray history = body.getAsJsonArray("messages");
        JsonObject last = history.get(history.size() - 1).getAsJsonObject();
        if (string(last, "role").equals("assistant")) {
            last.getAsJsonArray("content").addAll(output);
        } else {
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.add("content", output.deepCopy());
            history.add(message);
        }
    }

    static AiClient.Response parseResponse(String provider, String json, Map<String, AiClient.Source> sources)
            throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            AiClient.TokenUsage usage = usage(provider, root);
            Long total = usage == null ? null : Math.addExact(usage.inputTokens(), usage.outputTokens());
            if (total == null || provider.equals("openai-responses")) {
                String field = provider.equals("gemini") ? "usageMetadata" : "usage";
                if (root.has(field) && root.get(field).isJsonObject()) {
                    Long reported = tokenCount(root.getAsJsonObject(field),
                            provider.equals("gemini") ? "totalTokenCount" : "total_tokens");
                    if (reported != null) {
                        total = reported;
                    }
                }
            }
            List<AiClient.FunctionCall> calls = new ArrayList<>();
            JsonArray output = new JsonArray();
            String reply;
            boolean error = false;
            boolean paused = false;
            if (root.has("error") && !root.get("error").isJsonNull()) {
                reply = "AI 服务未能完成回答，请稍后重试。";
                error = true;
            } else {
                switch (provider) {
                    case "openai-responses" -> {
                        output = array(root, "output");
                        for (JsonElement element : output) {
                            JsonObject item = element.getAsJsonObject();
                            if (string(item, "type").equals("function_call")) {
                                calls.add(call(string(item, "call_id"), string(item, "name"), true));
                            }
                        }
                        String status = string(root, "status");
                        reply = responsesText(root, sources);
                        if (!status.equals("completed")) {
                            calls.clear();
                        }
                        if (status.equals("failed") || status.equals("cancelled")) {
                            reply = "AI 服务未能完成回答，请稍后重试。";
                            error = true;
                        } else if (reply.isBlank() && calls.isEmpty()) {
                            reply = status.equals("incomplete")
                                    ? "AI 回答未完成，请管理员增加输出 token 上限或降低思考强度。"
                                    : "AI 服务没有返回文本回答。";
                            error = true;
                        } else if (status.equals("incomplete")) {
                            reply += "\n[回答未完成，可能已达到输出 token 上限。]";
                        }
                    }
                    case "openai" -> {
                        JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
                        JsonObject message = choice.getAsJsonObject("message");
                        JsonObject continued = new JsonObject();
                        for (String field : List.of("role", "content", "tool_calls", "reasoning_content", "refusal")) {
                            if (message.has(field)) {
                                continued.add(field, message.get(field));
                            }
                        }
                        output.add(continued);
                        for (JsonElement element : array(message, "tool_calls")) {
                            JsonObject call = element.getAsJsonObject();
                            calls.add(call(string(call, "id"), string(call.getAsJsonObject("function"), "name"), true));
                        }
                        JsonObject text = new JsonObject();
                        text.addProperty("text", chatText(message.get("content")));
                        if (message.has("annotations")) {
                            text.add("annotations", message.get("annotations"));
                        }
                        reply = citedText(text, sources);
                        if (reply.isBlank()) {
                            reply = string(message, "refusal");
                        }
                        if (string(choice, "finish_reason").equals("length")) {
                            error = reply.isBlank();
                            reply += "\n[回答未完成，已达到输出 token 上限。]";
                            calls.clear();
                        }
                        if (string(choice, "finish_reason").equals("content_filter")) {
                            reply = "AI 服务未能提供此回答。";
                            error = true;
                            calls.clear();
                        }
                    }
                    case "anthropic" -> {
                        output = root.getAsJsonArray("content");
                        StringBuilder text = new StringBuilder();
                        boolean searchError = false;
                        for (JsonElement element : output) {
                            JsonObject block = element.getAsJsonObject();
                            if (string(block, "type").equals("tool_use")) {
                                calls.add(call(string(block, "id"), string(block, "name"), true));
                            } else if (string(block, "type").equals("text")) {
                                text.append(string(block, "text"));
                                for (JsonElement citation : array(block, "citations")) {
                                    JsonObject source = citation.getAsJsonObject();
                                    int number = addSource(sources, string(source, "title"), string(source, "url"));
                                    if (number > 0) {
                                        text.append('[').append(number).append(']');
                                    }
                                }
                            } else if (string(block, "type").equals("web_search_tool_result")
                                    && block.get("content").isJsonObject()) {
                                searchError = true;
                            }
                        }
                        paused = string(root, "stop_reason").equals("pause_turn");
                        if (string(root, "stop_reason").equals("max_tokens")) {
                            error = text.toString().isBlank();
                            text.append("\n[回答未完成，已达到输出 token 上限。]");
                            calls.clear();
                        }
                        if (searchError) {
                            error = error || (text.toString().isBlank() && calls.isEmpty() && !paused);
                            text.append("\n[联网搜索未完成，请稍后重试。]");
                        }
                        reply = text.toString();
                        if (string(root, "stop_reason").equals("refusal")) {
                            error = true;
                        }
                    }
                    case "gemini" -> {
                        JsonArray candidates = array(root, "candidates");
                        if (candidates.isEmpty()) {
                            reply = "AI 服务没有返回回答，问题可能被服务端拦截。";
                            error = true;
                        } else {
                            JsonObject candidate = candidates.get(0).getAsJsonObject();
                            JsonObject content = candidate.has("content") ? candidate.getAsJsonObject("content") : new JsonObject();
                            output.add(content);
                            for (JsonElement element : array(content, "parts")) {
                                JsonObject part = element.getAsJsonObject();
                                if (part.has("functionCall")) {
                                    JsonObject call = part.getAsJsonObject("functionCall");
                                    calls.add(call(string(call, "id"), string(call, "name"), false));
                                }
                            }
                            reply = geminiText(candidate, content, sources);
                            String reason = string(candidate, "finishReason");
                            if (reason.equals("MAX_TOKENS")) {
                                error = reply.isBlank();
                                reply += "\n[回答未完成，已达到输出 token 上限。]";
                                calls.clear();
                            } else if (!reason.isEmpty() && !reason.equals("STOP")) {
                                reply = "AI 服务未能完成回答（" + reason + "）。";
                                error = true;
                                calls.clear();
                            }
                        }
                    }
                    default -> throw new IllegalArgumentException("Unsupported provider");
                }
            }
            if (reply.isBlank() && calls.isEmpty() && !paused) {
                reply = "AI 服务没有返回文本回答，请检查模型和输出 token 上限。";
                error = true;
            }
            return new AiClient.Response(reply, total, usage, error, List.copyOf(sources.values()), output, calls, paused);
        } catch (RuntimeException ex) {
            throw new AiClient.ApiException("AI 服务返回的 " + provider + " JSON 格式无效。");
        }
    }

    private static AiClient.FunctionCall call(String id, String name, boolean requireId) {
        if (name.isBlank() || (requireId && id.isBlank())) {
            throw new IllegalArgumentException("Invalid function call");
        }
        return new AiClient.FunctionCall(id, name);
    }

    private static AiClient.TokenUsage usage(String provider, JsonObject root) {
        String field = provider.equals("gemini") ? "usageMetadata" : "usage";
        if (!root.has(field) || !root.get(field).isJsonObject()) {
            return null;
        }
        JsonObject counts = root.getAsJsonObject(field);
        Long input;
        Long output;
        long cached;
        switch (provider) {
            case "openai-responses" -> {
                input = tokenCount(counts, "input_tokens");
                output = tokenCount(counts, "output_tokens");
                cached = counts.has("input_tokens_details") && counts.get("input_tokens_details").isJsonObject()
                        ? count(counts.getAsJsonObject("input_tokens_details"), "cached_tokens") : 0;
            }
            case "openai" -> {
                input = tokenCount(counts, "prompt_tokens");
                output = tokenCount(counts, "completion_tokens");
                cached = counts.has("prompt_tokens_details") && counts.get("prompt_tokens_details").isJsonObject()
                        ? count(counts.getAsJsonObject("prompt_tokens_details"), "cached_tokens") : 0;
            }
            case "anthropic" -> {
                input = tokenCount(counts, "input_tokens");
                output = tokenCount(counts, "output_tokens");
                cached = count(counts, "cache_read_input_tokens");
                if (input != null) {
                    input = Math.addExact(input, Math.addExact(cached, count(counts, "cache_creation_input_tokens")));
                }
            }
            case "gemini" -> {
                input = tokenCount(counts, "promptTokenCount");
                output = tokenCount(counts, "candidatesTokenCount");
                cached = count(counts, "cachedContentTokenCount");
                if (input != null) {
                    input = Math.addExact(input, count(counts, "toolUsePromptTokenCount"));
                }
                if (output != null) {
                    output = Math.addExact(output, count(counts, "thoughtsTokenCount"));
                }
            }
            default -> throw new IllegalArgumentException("Unsupported provider");
        }
        return input == null || output == null ? null : new AiClient.TokenUsage(input, output, cached);
    }

    private static long count(JsonObject object, String field) {
        Long count = tokenCount(object, field);
        return count == null ? 0 : count;
    }

    private static JsonArray array(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? new JsonArray() : value.getAsJsonArray();
    }

    private static String chatText(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement element : content.getAsJsonArray()) {
            JsonObject part = element.getAsJsonObject();
            if (string(part, "type").equals("text")) {
                text.append(string(part, "text"));
            }
        }
        return text.toString();
    }

    private static String responsesText(JsonObject root, Map<String, AiClient.Source> sources) {
        List<String> parts = new ArrayList<>();
        for (JsonElement element : array(root, "output")) {
            JsonObject item = element.getAsJsonObject();
            if (!string(item, "type").equals("message")) {
                continue;
            }
            for (JsonElement part : item.getAsJsonArray("content")) {
                JsonObject block = part.getAsJsonObject();
                String text = switch (string(block, "type")) {
                    case "output_text" -> citedText(block, sources);
                    case "refusal" -> string(block, "refusal");
                    default -> "";
                };
                if (!text.isBlank()) {
                    parts.add(text);
                }
            }
        }
        return parts.isEmpty() ? string(root, "output_text") : String.join("\n", parts);
    }

    private static String geminiText(JsonObject candidate, JsonObject content, Map<String, AiClient.Source> sources) {
        JsonObject grounding = candidate.has("groundingMetadata")
                ? candidate.getAsJsonObject("groundingMetadata") : new JsonObject();
        List<Integer> numbers = new ArrayList<>();
        for (JsonElement element : array(grounding, "groundingChunks")) {
            JsonObject chunk = element.getAsJsonObject();
            JsonObject web = chunk.has("web") ? chunk.getAsJsonObject("web") : new JsonObject();
            numbers.add(addSource(sources, string(web, "title"), string(web, "uri")));
        }
        record Citation(int end, String marker) { }
        StringBuilder text = new StringBuilder();
        JsonArray parts = array(content, "parts");
        for (int index = 0; index < parts.size(); index++) {
            JsonObject part = parts.get(index).getAsJsonObject();
            if (!part.has("text") || (part.has("thought") && part.get("thought").getAsBoolean())) {
                continue;
            }
            String original = string(part, "text");
            byte[] bytes = original.getBytes(StandardCharsets.UTF_8);
            List<Citation> citations = new ArrayList<>();
            for (JsonElement element : array(grounding, "groundingSupports")) {
                JsonObject support = element.getAsJsonObject();
                JsonObject segment = support.getAsJsonObject("segment");
                int partIndex = segment.has("partIndex") ? segment.get("partIndex").getAsInt() : 0;
                int end = segment.has("endIndex") ? segment.get("endIndex").getAsInt() : 0;
                if (partIndex != index || end < 0 || end > bytes.length) {
                    continue;
                }
                StringBuilder marker = new StringBuilder();
                for (JsonElement chunk : array(support, "groundingChunkIndices")) {
                    int chunkIndex = chunk.getAsInt();
                    if (chunkIndex >= 0 && chunkIndex < numbers.size() && numbers.get(chunkIndex) > 0) {
                        marker.append('[').append(numbers.get(chunkIndex)).append(']');
                    }
                }
                citations.add(new Citation(new String(bytes, 0, end, StandardCharsets.UTF_8).length(), marker.toString()));
            }
            citations.sort(Comparator.comparingInt(Citation::end).reversed());
            StringBuilder cited = new StringBuilder(original);
            for (Citation citation : citations) {
                cited.insert(citation.end(), citation.marker());
            }
            text.append(cited);
        }
        return text.toString();
    }

    private static String citedText(JsonObject block, Map<String, AiClient.Source> sources) {
        String text = string(block, "text");
        if (!block.has("annotations") || block.get("annotations").isJsonNull()) {
            return text;
        }
        record Citation(int end, int number) { }
        List<Citation> citations = new ArrayList<>();
        for (JsonElement element : block.getAsJsonArray("annotations")) {
            JsonObject annotation = element.getAsJsonObject();
            if (!string(annotation, "type").equals("url_citation")) {
                continue;
            }
            if (annotation.has("url_citation")) {
                annotation = annotation.getAsJsonObject("url_citation");
            }
            int number = addSource(sources, string(annotation, "title"), string(annotation, "url"));
            if (number == 0) {
                continue;
            }
            int start = annotation.get("start_index").getAsInt();
            int end = annotation.get("end_index").getAsInt();
            if (start >= 0 && end >= start && end <= text.length()) {
                citations.add(new Citation(end, number));
            }
        }
        // Insert right to left without deleting the original cited text.
        citations.sort(Comparator.comparingInt(Citation::end).reversed());
        StringBuilder result = new StringBuilder(text);
        for (Citation citation : citations) {
            result.insert(citation.end(), "[" + citation.number() + "]");
        }
        return result.toString();
    }

    private static int addSource(Map<String, AiClient.Source> sources, String title, String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) {
                return 0;
            }
        } catch (IllegalArgumentException ex) {
            return 0;
        }
        sources.putIfAbsent(url, new AiClient.Source(title.isBlank() ? url : title, url));
        return new ArrayList<>(sources.keySet()).indexOf(url) + 1;
    }

    private static Long tokenCount(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Invalid token count");
        }
        long value = new BigDecimal(element.getAsString()).longValueExact();
        if (value < 0) {
            throw new IllegalArgumentException("Negative token count");
        }
        return value;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
