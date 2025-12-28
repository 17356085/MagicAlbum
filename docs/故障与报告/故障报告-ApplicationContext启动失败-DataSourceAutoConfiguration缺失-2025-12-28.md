# 故障报告：ApplicationContext 启动失败（DataSourceAutoConfiguration 缺失）

- 时间：2025-12-28
- 模块：后端（end）
- 环境：Spring Boot 4.0.2-SNAPSHOT、Java 17、MyBatis 3.5.19、MyBatis-Spring 4.0.0、MyBatis-Plus 3.5.15
- Profile：`mp-boot4`

## 问题概述
后端应用启动时 `ApplicationContext` 初始化失败，报错显示无法为导入的 `com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration` 生成 Bean 名称。根本原因是类路径中找不到 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`。

## 影响范围
- 开发与本地环境：应用无法启动，阻塞联调与验证。
- 依赖解析：与数据源初始化相关的自动配置均无法完成。
- MyBatis/MyBatis-Plus 集成：自动配置链路被中断，分页插件与会话工厂无法正常装配。

## 现象与错误信息
启动日志核心片段（摘要）：

```
Failed to start ApplicationContext: Unable to generate bean name for imported class 'com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration'.
Caused by: java.lang.ClassNotFoundException: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```

## 背景与变更
- Spring Boot 4 对自动配置包结构进行了调整：`DataSourceAutoConfiguration` 从 `org.springframework.boot.autoconfigure.jdbc` 迁移到 `org.springframework.boot.jdbc.autoconfigure`，并随 `spring-boot-starter-jdbc` 提供。
- 现有 `mp-boot4` 集成使用 `mybatis-plus-spring-boot4-starter:3.5.15`，其自动配置链路中仍显式引用了旧路径的 `DataSourceAutoConfiguration`，加之项目未显式引入 `spring-boot-starter-jdbc`，导致类路径缺失与不兼容叠加出现。

## 根因分析
- 直接原因：类路径中不存在 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`，导致 MyBatis-Plus 自动配置链路断裂。
- 深层原因：
  - Spring Boot 4 将 `DataSourceAutoConfiguration` 迁移至 `org.springframework.boot.jdbc.autoconfigure`，并随 JDBC Starter 提供。
  - 项目未引入 `spring-boot-starter-jdbc`，即使引用路径正确也会缺少相关类与依赖。
  - `mybatis-plus-spring-boot4-starter:3.5.15` 中的自动配置仍依赖旧的包路径，触发 `ClassNotFoundException`。

## 复现步骤
1. 在后端模块启用 `mp-boot4` Profile。
2. 执行 `mvn -P mp-boot4 spring-boot:run` 或以 IDE 运行主应用。
3. 观察启动失败，日志中出现上述 `ClassNotFoundException`。

## 修复过程与改动
为确保兼容 Spring Boot 4 的自动配置结构，并恢复数据源与 MyBatis 集成：

1) 添加 JDBC Starter（引入新的数据源自动配置类）
- 文件：`end/pom.xml`
- 变更：新增 `org.springframework.boot:spring-boot-starter-jdbc`

2) 切换到 MyBatis 官方 Boot 4 Starter（避免不兼容自动配置）
- 文件：`end/pom.xml`
- 变更：将 `com.baomidou:mybatis-plus-spring-boot4-starter` 替换为 `org.mybatis.spring.boot:mybatis-spring-boot-starter:4.0.0`

3) 保留 MyBatis-Plus 扩展能力（分页等）
- 文件：`end/pom.xml`
- 变更：保留 `com.baomidou:mybatis-plus-extension:${mp.boot4.version}` 与 `com.baomidou:mybatis-plus-jsqlparser:${mp.boot4.version}`。

4) 依赖解析修正（此前变更，确保仓库与坐标正确）
- 明确 `mybatis-spring` 的 `groupId` 为 `org.mybatis`。
- 在 POM 中显式声明 Maven Central 仓库（解决部分环境解析受限问题）。

## 关键修改清单（摘要）
- `end/pom.xml`
  - 新增：`org.springframework.boot:spring-boot-starter-jdbc`
  - 替换：`org.mybatis.spring.boot:mybatis-spring-boot-starter:4.0.0`（替代 `mybatis-plus-spring-boot4-starter`）
  - 保留：`com.baomidou:mybatis-plus-extension:3.5.15`、`com.baomidou:mybatis-plus-jsqlparser:3.5.15`
  - 依赖管理：`org.mybatis:mybatis-spring:4.0.0`、`org.mybatis:mybatis:3.5.19`

## 验证结果与方法
- 构建与依赖解析：
  - 执行 `mvn -P mp-boot4 clean package -U`
  - 观察依赖下载与构建成功，无坐标解析错误。
- 运行与日志：
  - 执行 `java -jar target/<artifact>.jar --debug`
  - 验证 `ApplicationContext` 成功启动，自动配置不再报错。
- 若出现 “Failed to configure a DataSource” 提示：
  - 在 `application.yml` 配置数据源：
    - `spring.datasource.url=jdbc:mysql://<host>:<port>/<db>?<params>`
    - `spring.datasource.username=<user>`
    - `spring.datasource.password=<pass>`
    - `spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver`
  - 临时排错手段（不建议长期使用）：在启动类上排除自动配置
    - `@SpringBootApplication(exclude = {org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class})`

## 残留风险与建议
- 数据源未配置将导致运行时初始化失败：请尽快补全 `spring.datasource.*`。
- 不同 Starter 的自动配置行为存在差异：
  - 需要在自定义 `MybatisPlusConfig` 中显式注册分页拦截器等插件，确保扩展能力正常工作。
- 版本兼容性：
  - 优先使用 Spring Boot 4.0.x 正式版本，避免 SNAPSHOT 带来的不确定性。
  - 锁定与审查核心依赖版本，避免隐式升级引入不兼容。
- 测试建议：
  - 添加集成测试覆盖数据源初始化与 MyBatis 映射装配。
  - 在 CI 中加入 `dependency:tree` 检查，及时发现冲突。

## 后续行动项
- 补全或校验 `application.yml` 的数据源配置。
- 在 `MybatisPlusConfig` 中手动注册分页拦截器与必要的插件。
- 引入健康检查与启动时验证逻辑（如 `DataSource` 可用性）。
- 将 Spring Boot 版本固定到稳定版并评估后续升级路径。

## 参考链接
- Spring Boot 4 JDBC 自动配置（包路径迁移相关）
  - https://docs.spring.io/spring-boot/docs/4.0.1/api/
- MyBatis Spring Boot Starter 4.0.0
  - https://github.com/mybatis/spring-boot-starter
- MyBatis-Plus 文档（分页插件）
  - https://baomidou.com/

—— 完 ——