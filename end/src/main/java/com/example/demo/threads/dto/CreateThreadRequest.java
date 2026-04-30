package com.example.demo.threads.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CreateThreadRequest {
    @NotNull
    private Long sectionId;

    @NotBlank
    @Size(min = 1, max = 256)
    private String title;

    @NotBlank
    private String content;

    @Size(max = 5)
    private java.util.List<@Size(min = 1, max = 32) String> tags;

    public Long getSectionId() { return sectionId; }
    public void setSectionId(Long sectionId) { this.sectionId = sectionId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public java.util.List<String> getTags() { return tags; }
    public void setTags(java.util.List<String> tags) { this.tags = tags; }
}
