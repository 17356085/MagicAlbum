# 开发约定

项目由 Vue 前端、Spring Boot 核心服务、网关和通知服务组成，站点显示名称为「同频」。

- [目录与文件归属](docs/engineering/repository-layout.md)
- [前后端编码与验证规范](docs/engineering/coding-standards.md)
- [产品与界面约定](PRODUCT.md)

开始修改前，核对工作区已有改动与调用方。目录迁移必须同步更新引用、启动命令和文档；不要把重命名、业务改造、依赖升级和数据迁移混进同一批改动。

在仓库根目录检查文件结构与引用：

```sh
node scripts/Check-Repository.mjs
node scripts/Check-JavaArchitecture.mjs
node --test scripts/__tests__/*.test.mjs
```

在 `frontend/` 运行 lint、类型检查、测试和构建；在根目录运行 `mvn -B test`。Python 集成测试的环境及执行范围见 [测试说明](tests/README.md)。这些检查覆盖各自的范围，不能替代鉴权、数据隔离和真实持久化流程的验证。
