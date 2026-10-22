# 开发约定

项目由 Vue 前端、Spring Boot 核心服务、网关、通知服务和 Go 媒体 worker 组成，站点显示名称为「同频」。

- [目录与文件归属](docs/engineering/repository-layout.md)
- [前后端编码与验证规范](docs/engineering/coding-standards.md)
- [产品与界面约定](PRODUCT.md)
- [工程文档索引](docs/engineering/README.md)、[脚本索引](scripts/README.md)、[独立服务](services/README.md)
- [2026-10-17 工程审查与优化记录](docs/engineering/review-2026-10-17.md)

开始修改前，核对工作区已有改动与调用方。目录迁移必须同步更新引用、启动命令和文档；不要把重命名、业务改造、依赖升级和数据迁移混进同一批改动。

在仓库根目录检查文件结构与引用：

```sh
node scripts/Check-Repository.mjs
node scripts/Check-JavaArchitecture.mjs
node --test scripts/__tests__/*.test.mjs
```

在 `frontend/` 运行 lint、类型检查、完整 Vitest 和构建；在根目录运行 `mvn -B test`；在 `services/media-service/` 运行 gofmt 检查、`go vet ./...` 和 `go test -race -count=1 ./...`。Windows 工程 shell 用例需显式选择可用的 Git Bash，Go race 需要 C 编译器。

Python 集成测试的隔离环境、完整回归与 mandatory16 的区别见[测试说明](tests/README.md)。全量执行须关闭 pytest 的提前失败停止，并核对收集数、实际执行数及 skip/failure/error；页面 fixture 测试、后端持久化和外部模型分别报告。真实外部调用和性能实验需要单独条件，不由普通测试命令隐式启动。
