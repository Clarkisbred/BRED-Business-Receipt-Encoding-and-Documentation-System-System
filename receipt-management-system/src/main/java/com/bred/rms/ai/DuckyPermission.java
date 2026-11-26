package com.bred.rms.ai;

/** Ducky's currently supported permission levels. Execute is intentionally unavailable. */
public enum DuckyPermission {
    ASK,
    ASSIST,
    EXECUTE;

    public boolean permitsTools() {
        return this == ASSIST;
    }

    public boolean available() {
        return this != EXECUTE;
    }
}
