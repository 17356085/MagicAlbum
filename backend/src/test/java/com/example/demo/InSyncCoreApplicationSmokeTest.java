package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;

@SpringBootTest(classes = InSyncCoreApplication.class)
@ActiveProfiles("test")
@DisabledInAotMode
class InSyncCoreApplicationSmokeTest {

    @Test
    void contextLoads() {
        // 如果应用上下文能启动，测试即通过
    }
}