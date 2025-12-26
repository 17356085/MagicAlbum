package com.example.demo.threads.dto;

import jakarta.validation.constraints.Size;

public class UpdateThreadRequest {
    @Size(min = 1, max = 256)
    private String title;
    private String content;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}