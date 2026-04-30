package com.example.demo.common.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RefreshScope
@RestController
@RequestMapping("/api/v1/system")
public class SystemConfigDemoController {

    @Value("${app.feature.nacos-config-demo:false}")
    private boolean nacosConfigDemo;

    @Value("${app.feature.nacos-config-message:local-default}")
    private String nacosConfigMessage;

    @GetMapping("/config-demo")
    public Map<String, Object> configDemo() {
        return Map.of(
                "nacosConfigDemo", nacosConfigDemo,
                "nacosConfigMessage", nacosConfigMessage
        );
    }
}
