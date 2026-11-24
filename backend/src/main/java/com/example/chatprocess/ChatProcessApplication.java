package com.example.chatprocess;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

import java.util.Map;

/**
 * Isolated chat owner entry point.  The shared backend jar contains the chat
 * domain classes, but this application scans only the process package and
 * imports the small allow-list in {@link ChatProcessConfiguration}.
 */
@SpringBootApplication(
        scanBasePackages = "com.example.chatprocess",
        exclude = {HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class,
                MybatisPlusAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
public class ChatProcessApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ChatProcessApplication.class);
        application.setDefaultProperties(Map.of("spring.config.name", "chat-service"));
        application.run(args);
    }
}
