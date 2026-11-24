package dev.craftcode.ui.model;

public record LinkedEnvironmentView(String id, String label, String status, String access, String expiresAt) {
    public boolean reachable() { return "reachable".equals(status); }
    public boolean readOnly() { return "read-only".equals(access); }
}
