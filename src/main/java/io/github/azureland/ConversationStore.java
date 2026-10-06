package io.github.azureland;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ConversationStore {
    record Loaded(String name, Conversation conversation) { }
    record Summary(String name, int exchanges, long tokens, String lastQuestion) { }
    private record Sessions(Map<String, Conversation.Snapshot> conversations) { }

    private final Path directory;
    private final Gson gson = new Gson();
    private final Map<UUID, Sessions> players = new HashMap<>();
    private boolean closed;

    ConversationStore(Path directory) {
        this.directory = directory;
    }

    synchronized void initialize(UUID player) throws IOException {
        if (closed) {
            throw new IOException("Conversation store is closed");
        }
        if (players.containsKey(player)) {
            return;
        }
        Path file = directory.resolve(player + ".json");
        if (!Files.exists(file)) {
            players.put(player, new Sessions(new LinkedHashMap<>()));
            return;
        }
        try {
            JsonObject json = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            Sessions sessions;
            if (json.has("messages")) {
                Conversation.Snapshot history = gson.fromJson(json, Conversation.Snapshot.class);
                sessions = new Sessions(new LinkedHashMap<>(Map.of("历史会话", history)));
            } else {
                sessions = gson.fromJson(json, Sessions.class);
            }
            if (sessions.conversations() == null) {
                throw new JsonParseException("Invalid conversations");
            }
            for (var entry : sessions.conversations().entrySet()) {
                validateName(entry.getKey());
                Conversation.Snapshot snapshot = entry.getValue();
                if (snapshot == null || snapshot.tokens() < 0) {
                    throw new JsonParseException("Invalid conversation snapshot");
                }
                for (Conversation.Message message : snapshot.messages()) {
                    if (!("user".equals(message.role()) || "assistant".equals(message.role()))
                            || message.content() == null) {
                        throw new JsonParseException("Invalid conversation message");
                    }
                }
            }
            players.put(player, sessions);
        } catch (RuntimeException ex) {
            throw new IOException("Stored conversations are invalid", ex);
        }
    }

    synchronized List<String> names(UUID player) {
        Sessions sessions = players.get(player);
        return sessions == null ? List.of() : List.copyOf(sessions.conversations().keySet());
    }

    synchronized boolean contains(UUID player, String name) {
        return players.get(player).conversations().containsKey(name);
    }

    synchronized List<Summary> summaries(UUID player) {
        return players.get(player).conversations().entrySet().stream().map(entry -> {
            Conversation.Snapshot snapshot = entry.getValue();
            List<Conversation.Message> messages = snapshot.messages();
            String lastQuestion = "尚未提问";
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).role().equals("user")) {
                    lastQuestion = messages.get(i).content();
                    break;
                }
            }
            return new Summary(entry.getKey(), messages.size() / 2, snapshot.tokens(), lastQuestion);
        }).toList();
    }

    synchronized List<Conversation.Message> history(UUID player, String name) {
        Sessions current = players.get(player);
        requireConversation(current, name);
        return current.conversations().get(name).messages();
    }

    static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 32
                || name.codePoints().anyMatch(character -> Character.isWhitespace(character)
                        || Character.isISOControl(character))) {
            throw new IllegalArgumentException("会话名必须为 1–32 个字符，不能包含空白或控制字符。");
        }
    }

    synchronized void create(UUID player, String name) throws IOException {
        Sessions current = players.get(player);
        if (current.conversations().containsKey(name)) {
            throw new IllegalArgumentException("会话“" + name + "”已存在，请通过 /helpme 菜单打开。");
        }
        Map<String, Conversation.Snapshot> conversations = new LinkedHashMap<>(current.conversations());
        conversations.put(name, new Conversation().snapshot());
        persist(player, new Sessions(conversations));
    }

    synchronized Loaded load(UUID player, String name) throws IOException {
        if (closed) {
            throw new IOException("Conversation store is closed");
        }
        Sessions current = players.get(player);
        requireConversation(current, name);
        return new Loaded(name, new Conversation(current.conversations().get(name)));
    }

    synchronized void save(UUID player, Loaded loaded) throws IOException {
        // Disable waits for any active write and prevents old workers overwriting a new plugin instance.
        if (closed) {
            return;
        }
        Sessions current = players.get(player);
        Map<String, Conversation.Snapshot> conversations = new LinkedHashMap<>(current.conversations());
        conversations.put(loaded.name(), loaded.conversation().snapshot());
        persist(player, new Sessions(conversations));
    }

    synchronized void clear(UUID player, String name) throws IOException {
        Sessions current = players.get(player);
        requireConversation(current, name);
        Map<String, Conversation.Snapshot> conversations = new LinkedHashMap<>(current.conversations());
        conversations.put(name, new Conversation().snapshot());
        persist(player, new Sessions(conversations));
    }

    synchronized void delete(UUID player, String name) throws IOException {
        Sessions current = players.get(player);
        requireConversation(current, name);
        Map<String, Conversation.Snapshot> conversations = new LinkedHashMap<>(current.conversations());
        conversations.remove(name);
        persist(player, new Sessions(conversations));
    }

    private void requireConversation(Sessions sessions, String name) {
        if (!sessions.conversations().containsKey(name)) {
            throw new IllegalArgumentException("找不到会话“" + name + "”，请使用 /helpme 查看已有会话。");
        }
    }

    private void persist(UUID player, Sessions sessions) throws IOException {
        if (closed) {
            throw new IOException("Conversation store is closed");
        }
        Files.createDirectories(directory);
        Path file = directory.resolve(player + ".json");
        Path temporary = directory.resolve(player + ".json.tmp");
        Files.writeString(temporary, gson.toJson(sessions), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        players.put(player, sessions);
    }

    synchronized void close() {
        closed = true;
    }
}
