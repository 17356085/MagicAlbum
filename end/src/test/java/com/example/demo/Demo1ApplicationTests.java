package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;

@SpringBootTest
@ActiveProfiles("test")
@DisabledInAotMode
class Demo1ApplicationTests {

    @Test
    void contextLoads() {
    }

}
