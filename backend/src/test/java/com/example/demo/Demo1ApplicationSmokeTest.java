package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;

@SpringBootTest(classes = Demo1Application.class)
@ActiveProfiles("test")
@DisabledInAotMode
class Demo1ApplicationSmokeTest {

    @Test
    void contextLoads() {
        // 如果应用上下文能启动，测试即通过
    }
}