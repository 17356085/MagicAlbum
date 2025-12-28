# 故障报告：AOT 构建失败（spring-boot-maven-plugin:process-aot）

时间：2025-12-28 21:20:45 +08:00

项目：`com.study.spring:demo1`（模块：`end`）

## 概要
- 在执行 `spring-boot-maven-plugin:4.0.1:process-aot` 目标时失败，报错 `MojoExecutionException`。
- 具体异常表现为 Spring AOT 期对 `org.springdoc.core.customizers.QuerydslPredicateOperationCustomizer` 进行反射时失败，触发 `NoClassDefFoundError: org/springframework/data/util/TypeInformation`。
- 根因：AOT 执行期间 `springdoc` 的 Querydsl 自定义器需要 `spring-data-commons` 中的 `TypeInformation`，但该类未在依赖中出现；同时，IDE/命令行直接调用 AOT 目标会绕过 `<executions>` 的跳过配置。

## 触发场景
- 命令：`mvn clean install` 或 IDE 直接执行 `spring-boot:process-aot`

## 错误日志摘录
```
[ERROR] Failed to execute goal org.springframework.boot:spring-boot-maven-plugin:4.0.1:process-aot (process-aot) on project demo1: Process terminated with exit code: 1 -> [Help 1]
...
Exception in thread "main" java.lang.IllegalStateException: Failed to introspect Class [org.springdoc.core.customizers.QuerydslPredicateOperationCustomizer] from ClassLoader [jdk.internal.loader.ClassLoaders$AppClassLoader@6bc7c054]
    at org.springframework.util.ReflectionUtils.getDeclaredMethods(ReflectionUtils.java:483)
    ...
Caused by: java.lang.NoClassDefFoundError: org/springframework/data/util/TypeInformation
    at java.base/java.lang.Class.getDeclaredMethods0(Native Method)
    ...
Caused by: java.lang.ClassNotFoundException: org.springframework.data.util.TypeInformation
    at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641)
    ...
```

## 影响范围
- 构建生命周期中若绑定或直接调用 `process-aot`/`process-test-aot`，会导致构建失败。
- 在未禁用 AOT 的环境/配置下，IDE 可能误触发该目标。

## 原因分析
- `springdoc` 的 `QuerydslPredicateOperationCustomizer` 在 AOT 反射阶段需要 `org.springframework.data.util.TypeInformation`。
- `TypeInformation` 属于 `spring-data-commons`，缺失该依赖会在 AOT 期触发 `NoClassDefFoundError`。
- 仅在 `<executions>` 中设置跳过并不能阻止“直接调用插件目标”的执行；需在插件顶层 `<configuration>` 设置 `<skip>true</skip>` 才能覆盖所有调用路径。

## 修复与变更
1) 依赖层面
- 在 `end/pom.xml` 主 `<dependencies>` 中加入：
  - `org.springframework.data:spring-data-commons`（提供 `TypeInformation`）。

2) 插件管理层面（父 POM `pom.xml`）
- 统一在 `build > pluginManagement > org.springframework.boot:spring-boot-maven-plugin:4.0.1` 增加：
  - 顶层 `<configuration><skip>true</skip></configuration>`（全局禁用所有目标，包括直接调用）。
  - `<executions>` 中保留对 `process-aot`、`process-test-aot` 的显式跳过（双保险）。

3) 子模块清理
- `end/pom.xml` 仅保留 `repackage` 目标绑定；AOT 跳过由父 POM集中管理。

4) 关联问题修复（此前已处理）
- 清理 `end/pom.xml` 中重复的顶级 `<dependencies>` 标签，合并依赖块，避免 POM 解析失败。

## 验证
- 生成有效 POM并检查跳过配置：
  - `mvn help:effective-pom -pl end`
  - 在 `end/effective-pom` 中可见多处 `<skip>true</skip>`（插件顶层与 executions）。
- 显式调用 AOT 验证已被跳过且构建成功：
  - `mvn -DskipTests spring-boot:process-aot -pl end`
- 常规构建：
  - `mvn clean install`
- 如需双重保险，命令行属性禁用：
  - `mvn -Dspring.aot.enabled=false -Dspring.test.aot.enabled=false clean install`

## 预防与建议
- 统一在父 POM管理关键插件与参数，减少子模块配置漂移与误触发。
- 测试/本地开发 Profile 中关闭 `springdoc`：`springdoc.api-docs.enabled=false`（可在 `application-test.yml` 或 Profile 属性中设置）。
- 测试类若涉及 AOT不兼容行为，使用 `@DisabledInAotMode` 避免 AOT期加载。
- IDE 中优先使用 Lifecycle 的 `install`，避免直接点击插件目标。

## 如需启用 AOT（未来）
- 移除父 POM的 `<configuration><skip>true</skip>`（或在子模块覆盖为 `<skip>false</skip>`）。
- 命令行显式打开：`-Dspring.aot.enabled=true -Dspring.test.aot.enabled=true`。
- 若仍受 `springdoc` 影响，建议在 AOT Profile 内暂时禁用 `springdoc` 或移除相关 starter；确认 `spring-data-commons` 在依赖中。

## 参考
- Spring Boot Maven Plugin AOT 文档：`https://docs.spring.io/spring-boot/docs/current/maven-plugin/reference/htmlsingle/#aot`（包含 `process-aot`/`process-test-aot` 与跳过说明）