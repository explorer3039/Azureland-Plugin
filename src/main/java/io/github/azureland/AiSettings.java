package io.github.azureland;

import java.net.URI;
import java.util.Locale;
import java.time.ZoneId;
import org.bukkit.configuration.file.FileConfiguration;

record AiSettings(boolean enabled, String provider, URI endpoint, String apiKey, String model,
                  String reasoningEffort, String systemPrompt, int timeoutSeconds,
                  int maxOutputTokens, int maxQuestionLength, int cooldownSeconds,
                  int maxConcurrentRequests, long globalDailyCreditLimit,
                  long playerDailyCreditLimit, long inputCreditsPerToken, long outputCreditsPerToken,
                  long cachedInputCreditsPerToken, long contextTokenLimit, ZoneId resetTimezone,
                  boolean webSearchEnabled, boolean pluginListEnabled) {
    static AiSettings from(FileConfiguration config) {
        String provider = config.getString("ai.provider", "openai-responses").trim().toLowerCase(Locale.ROOT);
        if (provider.equals("openai-compatible")) {
            provider = "openai-responses";
        }
        String defaultBase = switch (provider) {
            case "openai", "openai-responses" -> "https://api.openai.com/v1";
            case "anthropic" -> "https://api.anthropic.com/v1";
            case "gemini" -> "https://generativelanguage.googleapis.com/v1beta";
            default -> throw new IllegalArgumentException(
                    "ai.provider 必须是 openai、openai-responses、anthropic 或 gemini。");
        };
        String model = config.getString("ai.model", "gpt-5-mini").trim();
        if (model.isEmpty()) {
            throw new IllegalArgumentException("ai.model 不能为空。");
        }
        if (provider.equals("gemini")) {
            if (model.startsWith("models/")) {
                model = model.substring(7);
            }
            if (!model.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("Gemini 模型名只能包含字母、数字、点、下划线和连字符。");
            }
        }
        String effort = config.getString("ai.reasoning-effort", "").trim();
        if (provider.equals("gemini") && effort.matches("-?[0-9]+")) {
            try {
                if (Integer.parseInt(effort) < -1) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Gemini 思考预算必须是 -1 或有效的非负整数。");
            }
        }
        String base = config.getString("ai.base-url", defaultBase).trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI endpoint;
        try {
            endpoint = URI.create(base + switch (provider) {
                case "openai" -> "/chat/completions";
                case "anthropic" -> "/messages";
                case "gemini" -> "/models/" + model + ":generateContent";
                default -> "/responses";
            });
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("ai.base-url 不是有效的 API 根地址。");
        }
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !("https".equalsIgnoreCase(endpoint.getScheme())
                || "http".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("ai.base-url 必须是有效的 HTTP(S) API 根地址。");
        }
        String key = config.getString("ai.api-key", "").trim();
        if (key.chars().anyMatch(c -> c < 32 || c > 126)) {
            throw new IllegalArgumentException("ai.api-key 含有非法字符。");
        }
        return new AiSettings(config.getBoolean("ai.enabled", true), provider, endpoint, key, model,
                effort,
                config.getString("ai.system-prompt", ""),
                positive(config, "timeout-seconds", 60),
                positive(config, "max-output-tokens", 4096),
                positive(config, "max-question-length", 1000),
                nonNegative(config, "cooldown-seconds", 10),
                positive(config, "max-concurrent-requests", 4),
                positiveLong(config, "global-daily-credit-limit", 5_000_000),
                positiveLong(config, "player-daily-credit-limit", 100_000),
                nonNegativeLong(config, "credits-per-token.input", 4),
                nonNegativeLong(config, "credits-per-token.output", 12),
                nonNegativeLong(config, "credits-per-token.cached-input", 1),
                positiveLong(config, "context-token-limit", 100_000),
                timezone(config), config.getBoolean("ai.web-search.enabled", true),
                config.getBoolean("ai.plugin-list.enabled", true));
    }

    private static ZoneId timezone(FileConfiguration config) {
        try {
            return ZoneId.of(config.getString("ai.daily-reset-timezone", "Asia/Shanghai"));
        } catch (java.time.DateTimeException ex) {
            throw new IllegalArgumentException("ai.daily-reset-timezone 不是有效时区。");
        }
    }

    private static long positiveLong(FileConfiguration config, String key, long fallback) {
        long value = nonNegativeLong(config, key, fallback);
        if (value == 0) {
            throw new IllegalArgumentException("ai." + key + " 必须是正整数。");
        }
        return value;
    }

    private static long nonNegativeLong(FileConfiguration config, String key, long fallback) {
        Object raw = config.get("ai." + key, fallback);
        if (!(raw instanceof Number number) || number.doubleValue() != number.longValue()
                || number.longValue() < 0) {
            throw new IllegalArgumentException("ai." + key + " 必须是非负整数。");
        }
        return number.longValue();
    }

    long credits(long inputTokens, long outputTokens, long cachedTokens) {
        return Math.addExact(Math.addExact(
                Math.multiplyExact(inputTokens - cachedTokens, inputCreditsPerToken),
                Math.multiplyExact(cachedTokens, cachedInputCreditsPerToken)),
                Math.multiplyExact(outputTokens, outputCreditsPerToken));
    }

    long reservedCredits(long inputTokens) {
        return Math.addExact(Math.multiplyExact(inputTokens,
                Math.max(inputCreditsPerToken, cachedInputCreditsPerToken)),
                Math.multiplyExact(maxOutputTokens, outputCreditsPerToken));
    }

    private static int positive(FileConfiguration config, String key, int fallback) {
        int value = nonNegative(config, key, fallback);
        if (value == 0) {
            throw new IllegalArgumentException("ai." + key + " 必须大于零。");
        }
        return value;
    }

    private static int nonNegative(FileConfiguration config, String key, int fallback) {
        int value = config.getInt("ai." + key, fallback);
        if (value < 0) {
            throw new IllegalArgumentException("ai." + key + " 不能为负数。");
        }
        return value;
    }
}
