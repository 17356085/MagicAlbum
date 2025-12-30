# Docker 容器化部署指南

## 1. 核心概念

Docker 是一个开源的应用容器引擎，让开发者可以打包他们的应用以及依赖包到一个可移植的容器中，然后发布到任何流行的 Linux 机器上。

### 1.1 三大组件
- **Image (镜像)**: 只读模板，用于创建容器。类似于面向对象中的“类”。
- **Container (容器)**: 镜像的运行实例。类似于“对象”。容器之间相互隔离。
- **Repository (仓库)**: 存放镜像的地方（如 Docker Hub, Aliyun ACR）。

## 2. Dockerfile 最佳实践

Dockerfile 是用于构建镜像的脚本文件。

### 2.1 常用指令
- `FROM`: 指定基础镜像 (如 `openjdk:17-jdk-alpine`)。
- `WORKDIR`: 设置工作目录。
- `COPY` / `ADD`: 复制文件到镜像中。
- `RUN`: 构建时执行的命令 (如 `apt-get install`).
- `CMD` / `ENTRYPOINT`: 容器启动时执行的命令。
- `ENV`: 设置环境变量。
- `EXPOSE`: 声明暴露端口。

### 2.2 优化建议
- **选择最小基础镜像**: 如 `alpine` 版本，减少体积。
- **减少层数**: 合并多个 `RUN` 指令。
- **利用缓存**: 将变动少的文件（如依赖安装）放在前面，代码复制放在后面。
- **多阶段构建**: 编译环境和运行环境分离，进一步减小镜像体积。

```dockerfile
# 示例：Spring Boot 多阶段构建
# Build Stage
FROM maven:3.8-openjdk-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# Run Stage
FROM openjdk:17-jdk-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## 3. 容器管理与网络

### 3.1 常用命令
- `docker build -t name:tag .`: 构建镜像。
- `docker run -d -p 80:8080 --name myapp myimage`: 运行容器。
- `docker ps`: 查看运行中的容器。
- `docker logs -f myapp`: 查看日志。
- `docker exec -it myapp sh`: 进入容器内部。

### 3.2 网络模式
- **bridge**: 默认模式，通过网桥连接，需端口映射。
- **host**: 容器共享宿主机网络栈，性能高但端口冲突风险大。
- **none**: 无网络。
- **container**: 共享其他容器的网络。

## 4. 容器编排 (Docker Compose)

Docker Compose 用于定义和运行多容器 Docker 应用程序。

### 4.1 docker-compose.yml 结构
```yaml
version: '3.8'
services:
  app:
    build: .
    ports:
      - "8080:8080"
    depends_on:
      - db
      - redis
    environment:
      - SPRING_DATASOURCE_URL=jdbc:mysql://db:3306/mydb
  
  db:
    image: mysql:8.0
    environment:
      - MYSQL_ROOT_PASSWORD=secret
    volumes:
      - db_data:/var/lib/mysql

  redis:
    image: redis:alpine

volumes:
  db_data:
```

### 4.2 常用命令
- `docker-compose up -d`: 后台启动所有服务。
- `docker-compose down`: 停止并移除容器、网络。
- `docker-compose logs -f`: 查看聚合日志。

## 5. 环境一致性与部署流程

### 5.1 环境一致性
通过 Docker，确保开发、测试、生产环境使用相同的镜像，消除“在我机器上能跑”的问题。

### 5.2 部署流程
1. **开发**: 编写代码和 Dockerfile。
2. **构建**: CI/CD 流水线构建镜像。
3. **推送**: 将镜像推送到私有仓库。
4. **部署**: 生产服务器拉取镜像并运行（使用 Docker Compose 或 K8s）。

## 6. 本项目应用计划
- 为后端 Spring Boot 应用编写 Dockerfile。
- 为前端 Vue 应用编写 Dockerfile (使用 Nginx 托管静态文件)。
- 编写 `docker-compose.yml` 编排 MySQL, Redis, RabbitMQ, Backend, Frontend。
