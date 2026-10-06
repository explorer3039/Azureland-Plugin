package io.github.azureland;

import java.util.ArrayList;
import java.util.List;

final class Conversation {
    record Message(String role, String content) { }
    record Snapshot(List<Message> messages, long tokens) {
        Snapshot {
            messages = List.copyOf(messages);
        }
    }

    private final List<Message> messages = new ArrayList<>();
    private long tokens;

    Conversation() { }

    Conversation(Snapshot snapshot) {
        messages.addAll(snapshot.messages());
        tokens = snapshot.tokens();
    }

    Snapshot snapshot() {
        return new Snapshot(messages, tokens);
    }

    List<Message> withQuestion(String question) {
        List<Message> input = new ArrayList<>(messages);
        input.add(new Message("user", question));
        return List.copyOf(input);
    }

    void complete(List<Message> input, String answer, long tokenCount) {
        messages.clear();
        messages.addAll(input);
        messages.add(new Message("assistant", answer));
        tokens = tokenCount;
    }

    void clear() {
        messages.clear();
        tokens = 0;
    }
}
