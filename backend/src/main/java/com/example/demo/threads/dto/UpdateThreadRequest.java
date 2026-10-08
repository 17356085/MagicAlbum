package com.example.demo.threads.dto;

import jakarta.validation.constraints.Size;

public class UpdateThreadRequest {
    @Size(min = 1, max = 256)
    private String title;
    private String content;
    private Long sectionId;
    @Size(max = 5)
    private java.util.List<@Size(min = 1, max = 32) String> tags;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Long getSectionId() { return sectionId; }
    public void setSectionId(Long sectionId) { this.sectionId = sectionId; }
    public java.util.List<String> getTags() { return tags; }
    public void setTags(java.util.List<String> tags) { this.tags = tags; }
}
