package dev.t3mc.ui.model;

import java.util.Objects;

public record ConnectionStatus(ConnectionState state, String message) {
    public ConnectionStatus {
        state = Objects.requireNonNullElse(state, ConnectionState.DISCONNECTED);
        message = Objects.requireNonNullElse(message, "");
    }

    public static ConnectionStatus disconnected() {
        return new ConnectionStatus(ConnectionState.DISCONNECTED, "");
    }
}
