package io.github.azureland;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class AiClient {
    record Source(String title, String url) { }
    record TokenUsage(long inputTokens, long outputTokens, long cachedTokens) {
        TokenUsage {
            if (cachedTokens > inputTokens) {
                throw new IllegalArgumentException("Cached tokens exceed input tokens");
            }
        }
        long credits(AiSettings settings) {
            return settings.credits(inputTokens, outputTokens, cachedTokens);
        }
    }
    record FunctionCall(String id, String name) { }
    record Response(String text, Long totalTokens, TokenUsage usage, boolean error,
                    List<Source> sources, JsonArray output, List<FunctionCall> functionCalls, boolean paused) {
        String conversationText() {
            StringBuilder result = new StringBuilder(text);
            for (int i = 0; i < sources.size(); i++) {
                Source source = sources.get(i);
                result.append("\n[").append(i + 1).append("] ").append(source.title())
                        .append(' ').append(source.url());
            }
            return result.toString();
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    Response ask(AiSettings settings, JsonObject body, Map<String, Source> sources)
            throws IOException, InterruptedException {
        HttpResponse<String> response = post(settings, settings.endpoint(), body);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Provider error bodies may contain credentials or private request data.
            boolean rejected = response.statusCode() >= 400 && response.statusCode() < 500
                    && response.statusCode() != 408;
            throw new ApiException("AI 服务返回 HTTP " + response.statusCode() + "，请管理员检查配置或配额。", rejected);
        }
        return AiFormats.parseResponse(settings.provider(), response.body(), sources);
    }

    long countInput(AiSettings settings, JsonObject body) throws IOException, InterruptedException {
        JsonObject counted = AiFormats.countPayload(settings, body);
        // Chat Completions has no native count API; Claude count_tokens rejects server search tools.
        if (settings.provider().equals("openai")
                || (settings.provider().equals("anthropic") && settings.webSearchEnabled())) {
            return estimate(counted);
        }
        URI endpoint = switch (settings.provider()) {
            case "anthropic" -> URI.create(settings.endpoint() + "/count_tokens");
            case "gemini" -> URI.create(settings.endpoint().toString().replace(":generateContent", ":countTokens"));
            default -> URI.create(settings.endpoint() + "/input_tokens");
        };
        HttpResponse<String> response = post(settings, endpoint, counted);
        if (response.statusCode() == 404 || response.statusCode() == 405 || response.statusCode() == 501) {
            // Count tool definitions and results as well as conversation text.
            return estimate(counted);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException("AI token 计数接口返回 HTTP " + response.statusCode() + "，请联系管理员。", true);
        }
        return AiFormats.parseInputCount(settings.provider(), response.body());
    }

    private static long estimate(JsonObject counted) {
        return 1024L + counted.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private HttpResponse<String> post(AiSettings settings, URI endpoint, JsonObject body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        switch (settings.provider()) {
            case "anthropic" -> request.header("x-api-key", settings.apiKey()).header("anthropic-version", "2023-06-01");
            case "gemini" -> request.header("x-goog-api-key", settings.apiKey());
            default -> request.header("Authorization", "Bearer " + settings.apiKey());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static final class ApiException extends IOException {
        private final boolean rejected;

        ApiException(String message) {
            this(message, false);
        }

        ApiException(String message, boolean rejected) {
            super(message);
            this.rejected = rejected;
        }

        boolean rejected() {
            return rejected;
        }
    }
}
