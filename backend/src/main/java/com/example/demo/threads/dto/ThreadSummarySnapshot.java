package com.example.demo.threads.dto;

public record ThreadSummarySnapshot(Long id, Long authorId, Long sectionId, long contentVersion,
                                    String status, String content, String taskId,
                                    String summary, String summaryStatus) { }
