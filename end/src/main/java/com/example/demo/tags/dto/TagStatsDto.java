package com.example.demo.tags.dto;

public class TagStatsDto {
    private Long id;
    private String name;
    private String type;
    private Long threadCount;

    public TagStatsDto() {
    }

    public TagStatsDto(Long id, String name, String type, Long threadCount) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.threadCount = threadCount;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getThreadCount() { return threadCount; }
    public void setThreadCount(Long threadCount) { this.threadCount = threadCount; }
}
