package com.example.chatprocess;

import com.example.demo.chat.controller.ChatController;
import com.example.demo.chat.remote.RemoteChatDirectory;
import com.example.demo.chat.repo.ChatRepository;
import com.example.demo.chat.service.ChatChangePublisher;
import com.example.demo.chat.service.ChatService;
import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.common.GlobalExceptionHandler;
import com.example.demo.config.RequestCorrelationFilter;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit allow-list for components shared from the backend jar. */
@Configuration(proxyBeanMethods = false)
@Import({ChatController.class, ChatService.class, ChatRepository.class, ChatChangePublisher.class,
        DistributedRateLimiter.class, GlobalExceptionHandler.class})
public class ChatProcessConfiguration {

    @Bean
    FilterRegistrationBean<RequestCorrelationFilter> chatRequestCorrelation() {
        var registration = new FilterRegistrationBean<>(new RequestCorrelationFilter("chat-service"));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public RemoteChatDirectory chatDirectory(JdbcTemplate jdbc,
            @Value("${chat.core-url:http://localhost:8080}") String coreUrl,
            @Value("${chat.internal-service-token:}") String internalServiceToken) {
        return new RemoteChatDirectory(jdbc, coreUrl, internalServiceToken);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rabbit.enabled", havingValue = "true")
    public FanoutExchange chatRealtimeExchange() {
        return new FanoutExchange(ChatChangePublisher.EXCHANGE, true, false);
    }

}
