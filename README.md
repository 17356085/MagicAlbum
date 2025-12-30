# BlueAlbum

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](https://github.com/yourusername/BlueAlbum)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-green)](https://spring.io/projects/spring-boot)
[![Vue.js](https://img.shields.io/badge/Vue.js-3.5-4FC08D)](https://vuejs.org/)

## 1. 项目概述
BlueAlbum 是一个现代化的全栈在线论坛/社区平台，致力于提供流畅的交流体验。它结合了最新的 Spring Boot 4 (Experimental) 后端架构与 Vue 3 前端技术，支持富文本/Markdown 发帖、实时评论互动、用户个性化设置以及深色模式等特性。适用于构建技术社区、兴趣小组或内部知识库。

**核心技术栈：**
- **后端**: Spring Boot 4.0.1 (Snapshot), Java 21, MyBatis-Plus / JPA, RabbitMQ, Redis, MySQL 8.0
- **前端**: Vue 3, Vite 5, Tailwind CSS 3, Axios, Md-editor-v3
- **基础设施**: Docker Compose (MySQL, Redis, RabbitMQ)

## 2. 功能特性

### ✅ 已实现功能
- **内容创作**: 
  - 支持 Markdown/富文本发帖与实时预览
  - 图片上传（支持本地存储与 S3 云存储）
  - 内容美化与格式化
- **浏览体验**: 
  - 分区浏览（发现页），支持网格/列表视图
  - 帖子详情页，沉浸式阅读体验
  - 无限滚动加载与分页支持
- **互动交流**: 
  - 评论系统（支持 Markdown、图片混排）
  - 用户提及（@用户）
  - 帖子/评论点赞与收藏（部分实现）
- **用户系统**: 
  - 注册/登录（基于 JWT 的认证鉴权）
  - 个人资料管理（自定义头像、昵称、密码修改）
  - 用户搜索与主页展示
- **个性化与体验**: 
  - 全站深色/浅色模式无缝切换
  - 最近浏览历史记录
  - 响应式设计，适配移动端与桌面端

### ⚠️ 待开发功能
- [ ] **账户安全**: 手机号/邮箱验证与绑定 🚧
- [ ] **AI 增强**: 智能内容推荐与辅助创作 🚧
- [ ] **多因素认证 (MFA)**: 提升账户安全性 🚧
- [ ] **私信系统**: 用户间实时聊天 🚧

## 3. 项目结构

```text
BlueAlbum/
├── end/                 # 后端工程 (Spring Boot)
│   ├── src/main/java    # Java 源代码
│   ├── src/main/resources # 配置文件与数据库迁移脚本
│   └── pom.xml          # Maven 依赖管理
├── front/               # 前端工程 (Vue 3)
│   ├── src/             # Vue 源代码 (组件, 页面, API)
│   ├── public/          # 静态资源
│   └── package.json     # NPM 依赖管理
├── docs/                # 项目文档
│   ├── API/             # API 接口文档
│   ├── 需求与设计/       # 设计文档与 UI 规范
│   └── 故障与报告/       # 问题追踪与修复记录
├── docker-compose.yml   # Docker 基础设施编排
└── README.md            # 项目说明文档
```

## 4. 安装指南

### 系统要求
- **JDK**: 21 或更高版本
- **Node.js**: 18.0.0 或更高版本
- **Docker**: 推荐用于快速启动数据库和中间件

### 1. 启动基础设施
在项目根目录下，使用 Docker Compose 启动 MySQL, Redis 和 RabbitMQ：
```bash
docker-compose up -d
```
*确保端口 3307 (MySQL), 6379 (Redis), 5672/15672 (RabbitMQ) 未被占用。*

### 2. 后端安装 (end/)
```bash
cd end
# 编译并安装依赖（跳过测试以加快速度）
./mvnw clean install -DskipTests

# 启动服务 (默认端口 8080)
./mvnw spring-boot:run
```
*注意：首次启动会自动执行 Flyway 数据库迁移脚本，初始化表结构和种子数据。*

### 3. 前端安装 (front/)
```bash
cd front
# 安装 NPM 依赖
npm install

# 启动开发服务器 (默认端口 5173)
npm run dev
```

## 5. 使用说明

### 基础用法
1. **访问应用**: 打开浏览器访问 `http://localhost:5173`。
2. **注册/登录**: 点击右上角头像或侧边栏进行注册。默认管理员账号（如已预置）通常为 `admin/password`（视种子数据而定）。
3. **浏览帖子**: 在“发现”页面按分区或最新发布浏览。
4. **发布内容**: 点击底部导航栏的 "+" 按钮进入发帖模式，支持 Markdown 语法。

### 高级配置
后端配置文件位于 `end/src/main/resources/application.yml`。
- **数据库连接**: 修改 `spring.datasource` 部分以连接外部数据库。
- **文件上传**: 默认使用本地存储 (`storage.local`)，可配置 AWS S3 (`storage.s3`)。
- **MyBatis-Plus**: 通过 Maven Profile (`mp-boot3` 或 `mp-boot4`) 切换兼容性。
- **安全配置**: 本地开发时，建议创建 `end/src/main/resources/application-secrets.yml` 存放 `ai.api-key` 等敏感信息（该文件已被 gitignore）。

## 6. 贡献指南

我们非常欢迎社区贡献！请遵循以下步骤：

1. **提交 Issue**: 如果发现 Bug 或有新功能建议，请先提交 Issue 讨论。
2. **Fork 仓库**: 将项目 Fork 到您的 GitHub 账户。
3. **创建分支**: `git checkout -b feature/MyFeature`。
4. **提交代码**: 请确保代码风格统一，遵循 Google Java Style 和 Vue 官方风格指南。
5. **提交 PR**: 将更改推送到您的仓库并提交 Pull Request。

### 开发环境设置
- **IDE**: 推荐使用 IntelliJ IDEA (后端) 和 VS Code (前端)。
- **插件**: VS Code 推荐安装 Volar, Tailwind CSS IntelliSense, ESLint。

## 7. 许可证信息

本项目采用 **MIT 许可证** 开源。

```text
MIT License

Copyright (c) 2025 BlueAlbum Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```

---
*文档更新日期: 2025-12-30*
