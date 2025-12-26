# MagicAlbum

一个以 ACG 氛围为灵感的社区式相册与讨论站点，前端基于 Vue 3 + Vite，后端基于 Spring Boot。支持发帖、评论（含楼中楼）、用户注册登录、搜索、主题切换、Markdown 内容与图片上传等。

## 功能特性
- 帖子发布与浏览（含首图预览、文本摘要）
- 评论与楼中楼回复；楼层号基于“原始到达顺序”，排序与分页不改变楼层编号
- 用户系统：注册、登录、个人资料（头像）展示
- 站内搜索：帖子与用户切换搜索
- 主题切换：明暗主题持久化到 `localStorage`
- Markdown 编辑与预览、图片上传
- 前后端分页与总数计算修正（页数基于顶层楼层）

## 技术栈
- 前端：`Vue 3`, `Vite`, `Tailwind CSS`, `vue-router`, `md-editor-v3`, `highlight.js`
- 后端：`Spring Boot 3`, `Flyway`, `MySQL`
- 开发与联调：`Docker Compose`（可选）

## 目录结构
```
BlueAlbum/
├── end/           # 后端（Spring Boot）
├── front/         # 前端（Vue 3 + Vite）
├── docs/          # 设计与接口文档
├── docker-compose.yml
└── README.md      # 项目说明（本文件）
```

## 快速开始

### 前提条件
- Node.js `>=18`
- JDK `>=17`（推荐 21）
- 可选：Docker Desktop（用于本地数据库）

### 启动后端（本地 MySQL 或 Docker MySQL）
1) 本地 MySQL 环境变量（PowerShell，仅当前会话）：
```
$env:DB_HOST="localhost"
$env:DB_PORT="3306"
$env:DB_NAME="MagicAlbum"
$env:DB_USER="root"
$env:DB_PASSWORD="<你的密码>"
```
2) 在 `end` 目录运行：
```
.\mvnw.cmd spring-boot:run
```
3) 成功后端口：`http://localhost:8080/`

> 使用 Docker 启动数据库：在仓库根目录执行 `docker compose up -d mysql`，默认库/用户/密码均为 `MagicAlbum`（详见下文“使用 Docker 本地数据库”）。

### 启动前端
1) 配置 `front/.env`：
```
VITE_API_BASE_URL=http://localhost:8080/api/v1
VITE_USE_API_MOCK=false
```
2) 安装依赖与运行：
```
cd front
npm install
npm run dev
```
3) 打开 `http://localhost:5173/sections`

### 生产构建
- 前端：`npm run build`（在 `front` 目录）
- 本地预览：`npm run preview`
- 后端打包：`.\mvnw.cmd -DskipTests package`（生成 `end/target/*.jar`）

## 使用 Docker 本地数据库（可选）
在仓库根目录：
```
docker compose up -d mysql
docker compose ps
docker logs MagicAlbum-mysql --tail 100
```
默认配置（见 `docker-compose.yml`）：端口 `3306`，库/用户/密码 `MagicAlbum`，数据卷 `mysqldata`。若使用 PostgreSQL：`docker compose up -d postgres`，端口 `5432`。

## 常见问题（FAQ）
- `docker: command not found`：未安装或未启动 Docker Desktop。
- 端口冲突（3306/5432）：修改 `docker-compose.yml` 端口映射或停止本机服务。
- MySQL 认证失败：检查用户名密码与授权；必要时执行 `docker compose down -v && docker compose up -d mysql` 以重建。
- CORS：后端默认允许 `http://localhost:5173`，如有调整请修改后端 WebConfig。

更多细节参见 `docs/环境配置.md`、`docs/API规范.md` 与 `docs/里程碑1开发文档.md`。

## 发布到 GitHub（首次推送）
1) 在 GitHub 网页创建一个空仓库（记住 HTTPS 远程地址）。
2) 在本地仓库根目录执行：
```
git init
git add .
git commit -m "chore: init MagicAlbum"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```
3) 后续更新：
```
git add -A
git commit -m "feat: 更新前端评论与分页逻辑"
git push
```

> 已包含 `.gitignore`；如需 CI/CD、Issue 模板或贡献指南，可在 `docs/` 中补充并在本 README 链接。

## 贡献与许可
- 欢迎通过 Issue 或 PR 贡献改进（代码、文档、样式）。
- 许可证（License）：暂未设置，如需开源请明确采用 `MIT` 或其他许可。

---

视觉与交互参考：Bangumi（风格取向），ACG 氛围灵感取自《白色相簿》《魔法使之夜》。