package com.example.demo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI blueAlbumOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("BlueAlbum API")
                        .version("v1")
                        .description("BlueAlbum 在线论坛后端接口文档，可直接导入 Apifox。")
                        .contact(new Contact().name("BlueAlbum")))
                .servers(List.of(
                        new Server().url("http://localhost:9000").description("本地网关"),
                        new Server().url("http://localhost:8080").description("本地后端直连")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录后将 accessToken 填入 Apifox 的 Bearer Token。")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    @Bean
    public GroupedOpenApi blueAlbumV1Api() {
        return GroupedOpenApi.builder()
                .group("bluealbum-v1")
                .pathsToMatch("/api/v1/**")
                .packagesToScan("com.example.demo")
                .build();
    }
}
