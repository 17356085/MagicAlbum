package com.example.demo.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AiChatRequest(
        @NotEmpty(message = "messages 不能为空")
        @Size(max = 20, message = "单次最多发送 20 条消息")
        List<@Valid AiChatMessage> messages,
        Integer maxOutputTokens
) {
    public record AiChatMessage(
            @NotBlank(message = "消息角色不能为空")
            String role,
            @NotBlank(message = "消息内容不能为空")
            @Size(max = 4000, message = "单条消息不能超过 4000 字")
            String content
    ) {
    }
}
