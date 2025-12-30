# Nginx 配置与优化指南

## 1. 核心功能

Nginx 是一个高性能的 HTTP 和反向代理 web 服务器。

### 1.1 反向代理 (Reverse Proxy)
- **概念**: 客户端访问 Nginx，Nginx 将请求转发给后端服务器，并将响应返回给客户端。隐藏了真实服务器的 IP。
- **作用**: 安全性、负载均衡、缓存。

### 1.2 负载均衡 (Load Balancing)
- 将流量分发到多个后端服务器，提高并发处理能力。

### 1.3 动静分离
- Nginx 直接处理静态资源（HTML, CSS, JS, 图片），只将动态请求转发给后端（Tomcat/Spring Boot）。

## 2. 常用配置详解

### 2.1 基础结构
```nginx
http {
    upstream backend_servers {
        server 192.168.1.101:8080 weight=1;
        server 192.168.1.102:8080 weight=2;
    }

    server {
        listen 80;
        server_name example.com;

        # 静态资源
        location /static/ {
            root /var/www/html;
            expires 30d;
        }

        # 动态请求反向代理
        location /api/ {
            proxy_pass http://backend_servers;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
        }
    }
}
```

### 2.2 负载均衡策略
- **轮询 (Round Robin)**: 默认，按顺序逐一分配。
- **Weight**: 加权轮询，权重越高分配越多。
- **ip_hash**: 根据客户端 IP Hash 值分配，保证同一 IP 访问同一服务器（解决 Session 问题）。
- **least_conn**: 最少连接数优先。

## 3. 高可用架构 (High Availability)

单点故障风险：如果 Nginx 宕机，整个服务不可用。

### 3.1 Keepalived + Nginx
- **原理**: 使用 VIP (虚拟 IP) 在主备 Nginx 之间漂移。
- **组件**:
  - **Master**: 正常持有 VIP，处理请求。
  - **Backup**: 监听 Master 心跳，Master 宕机时接管 VIP。
- **VRRP 协议**: 用于实现路由器冗余。

## 4. 性能调优

### 4.1 Worker 配置
- `worker_processes`: 设置为 CPU 核心数或 auto。
- `worker_connections`: 单个 worker 最大连接数（如 10240）。

### 4.2 Gzip 压缩
开启 Gzip 可以减少传输数据量，提高加载速度。
```nginx
gzip on;
gzip_types text/plain application/json application/javascript text/css;
```

### 4.3 缓存配置
- **Proxy Cache**: 缓存后端响应。
- **Browser Cache**: 设置 `Expires` 或 `Cache-Control` 头。

## 5. 安全配置

### 5.1 HTTPS 配置
使用 SSL/TLS 加密通信。
```nginx
server {
    listen 443 ssl;
    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;
}
```

### 5.2 访问控制
- **IP 白名单/黑名单**:
  ```nginx
  location /admin/ {
      allow 192.168.1.0/24;
      deny all;
  }
  ```
- **限流**: 使用 `limit_req_zone` 限制请求频率。

## 6. 本项目应用指南
- **前端部署**: 构建 Vue 项目生成的 `dist` 目录，配置 Nginx `root` 指向该目录。
- **API 代理**: 配置 `/api/` 路径转发到 Spring Boot 容器。
- **文件服务**: 配置 `/uploads/` 路径直接服务用户上传的图片。
