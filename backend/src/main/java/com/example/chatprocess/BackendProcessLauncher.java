package com.example.chatprocess;

import com.example.demo.Demo1Application;

/** Entry point for the shared image. Each process creates its own application context. */
public final class BackendProcessLauncher {
    private BackendProcessLauncher() {}

    public static void main(String[] args) {
        switch (System.getenv().getOrDefault("BLUEALBUM_PROCESS", "core")) {
            case "core" -> Demo1Application.main(args);
            case "chat" -> ChatProcessApplication.main(args);
            default -> throw new IllegalStateException("BLUEALBUM_PROCESS must be core or chat");
        }
    }
}
