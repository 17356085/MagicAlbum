# 技术扩展：Nginx 反向代理与静态资源

## 目标
- 为前端 `front` 提供高效的静态资源服务。
- 将后端 `end` 的 API 通过统一域名 `https://example.com/api` 暴露。
- 开启 TLS/HTTP2、gzip 压缩、基础限流与缓存控制，提升性能与安全。

## 推荐部署拓扑
- Internet → Nginx（80/443）→
  - `location /` 静态资源指向前端构建目录（如 `/var/www/front`）。
  - `location /api/` 反向代理到后端（如 `http://end:8080/`）。

## 示例配置（参考）
```
server {
    listen 443 ssl http2;
    server_name example.com;

    ssl_certificate     /etc/nginx/certs/example.com.crt;
    ssl_certificate_key /etc/nginx/certs/example.com.key;

    # 前端静态资源
    location / {
        root /var/www/front;
        try_files $uri /index.html;
    }

    # 后端 API 代理
    location /api/ {
        proxy_pass http://end:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 60s;
    }

    # 压缩与缓存
    gzip on;
    gzip_types text/plain text/css application/json application/javascript;
    etag on;

    # 基础限流（示例）
    limit_req_zone $binary_remote_addr zone=api_limit:10m rate=10r/s;
    location /api/ {
        limit_req zone=api_limit burst=20 nodelay;
    }

    # 安全 headers（示例）
    add_header X-Content-Type-Options nosniff;
    add_header X-Frame-Options SAMEORIGIN;
    add_header Referrer-Policy no-referrer-when-downgrade;
}
```

## 与项目集成
- 前端：`front/.env` 配置 `VITE_API_BASE=/api`，前端通过相对路径请求 API。
- 后端：在生产中读取 `X-Forwarded-*` 以识别原始协议与客户端 IP。

## 监控与运维
- 开启 Nginx `status` 或引入可观测方案（如 Prometheus Nginx Exporter）。
- 日志轮转与备份，使用 `logrotate` 或容器日志采集。
- 灰度与滚动升级：通过上游 `upstream` 切换与权重控制。

## 回滚与风险
- 配置变更前保留备份；启用 `nginx -t` 校验后再 `reload`。
- 谨慎设置 `try_files` 与缓存策略，避免刷新失败或旧资源命中。