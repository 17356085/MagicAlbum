# 从 Spring Data JPA 迁移到 MyBatis-Plus 技术方案

本文档用于指导项目从 Spring Data JPA 渐进式迁移到 MyBatis-Plus（简称 MP），覆盖选型理由、关键差异、迁移策略、步骤与时间线、配置与代码样例、风险与回滚、验收标准等。

## 迁移目标与原则
- 目标：在不影响上层服务/控制器接口的前提下，引入并逐步采用 MP，提升对 SQL 的可控性与复杂查询性能，同时保留可维护性与一致性。
- 原则：
  - 渐进式迁移，**并行共存**一段时间：新模块/复杂报表优先使用 MP，稳定后逐步替换 JPA 仓储。
  - 服务与控制器层接口**尽量不变**，只替换数据访问实现。
  - 迁移优先级：**复杂列表/统计/联表/窗口函数场景** → 普通 CRUD → 复杂聚合根最后评估是否保留 JPA。

## 关键差异与影响面
- 抽象层级：
  - JPA：ORM 驱动，强调实体关系与仓储接口；自动 SQL 生成，关联维护与生命周期管理。
  - MP：SQL 驱动，强调 Mapper 与显式 SQL/条件构造器；更自由地使用数据库方言与优化。
- 关联与加载：
  - JPA 支持 `@OneToMany/@ManyToOne`、懒加载；迁移后应改为**显式查询组合**，避免隐式 N+1。
- 分页与查询：
  - JPA 使用 `Pageable` 与派生查询；MP 使用 `Page<T>` 与 `QueryWrapper/LambdaQueryWrapper`，分页插件统一处理。
- 审计/逻辑删除/乐观锁：
  - JPA 有审计注解与乐观锁支持；MP 使用 `MetaObjectHandler` 自动填充、`@TableLogic` 逻辑删除、`@Version` 乐观锁插件。
- 缓存：
  - 避免同时使用 JPA 二级缓存与 MP 缓存策略，建议统一到 Redis 或 Spring Cache。

## 迁移策略与步骤
> 建议 4 周路线图，可根据人力调整。

### 第 0 周：引入与基础配置
1. 在 `pom.xml` 添加 MP 依赖：
   ```xml
   <dependency>
     <groupId>com.baomidou</groupId>
     <artifactId>mybatis-plus-boot-starter</artifactId>
     <version>3.5.5</version>
   </dependency>
   <!-- 可选扩展 -->
   <dependency>
     <groupId>com.baomidou</groupId>
     <artifactId>mybatis-plus-extension</artifactId>
     <version>3.5.5</version>
   </dependency>
   ```
2. 新增 MP 拦截器配置：分页与乐观锁。
   ```java
   @Configuration
   public class MybatisPlusConfig {
     @Bean
     public MybatisPlusInterceptor mybatisPlusInterceptor() {
       MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
       interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
       interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
       return interceptor;
     }
   }
   ```
3. 统一字段自动填充（创建/更新时间）：
   ```java
   @Component
   public class MyMetaObjectHandler implements MetaObjectHandler {
     @Override
     public void insertFill(MetaObject metaObject) {
       this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
       this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
     }
     @Override
     public void updateFill(MetaObject metaObject) {
       this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
     }
   }
   ```
4.（可选）统一逻辑删除：
   - 实体字段加 `@TableLogic`，约定 `0/1` 表示未删/已删，校验历史数据一致性。

### 第 1 周：并行验证（新接口用 MP）
1. 在新需求或性能瓶颈的**列表/分页检索**接口使用 MP：
   - 保持 Service/Controller 接口签名不变，底层数据访问改为 Mapper + Wrapper。
2. 对比 SQL 与执行计划，完善索引。

### 第 2–3 周：替换高收益仓储
1. 选择 2–3 个 JPA 仓储（复杂报表/联表/统计），用 MP 重写为 Mapper + 注解/ XML。
2. 将 JPA 的隐式关联加载改成**显式查询 + DTO 投影**，避免一次性加载大对象。
3. 回归测试：数据一致性、分页/排序一致性、性能对比。

### 第 4 周：评估复杂聚合与清理
1. 对复杂聚合根（强关系、生命周期管理）评估：保留 JPA 或重构为显式查询。
2. 清理未使用的 JPA 仓储与注解，统一缓存策略，整理文档与规范。

## 配置与代码样例

### 实体映射（JPA → MP）
```java
// JPA
@Entity
@Table(name = "threads")
public class ThreadEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String title;
  private String content;
}

// MP
@TableName("threads")
public class ThreadEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String title;
  private String content;
  @Version
  private Integer version; // 如需乐观锁
  @TableLogic
  private Integer deleted; // 如需逻辑删除
}
```

### Mapper 与 Service（分页搜索示例）
```java
@Mapper
public interface ThreadMapper extends BaseMapper<ThreadEntity> {}

@Service
public class ThreadService {
  @Resource private ThreadMapper mapper;

  public IPage<ThreadEntity> search(String q, int page, int size) {
    Page<ThreadEntity> p = new Page<>(page, size);
    LambdaQueryWrapper<ThreadEntity> w = Wrappers.lambdaQuery();
    if (StringUtils.hasText(q)) {
      w.like(ThreadEntity::getTitle, q);
    }
    w.orderByDesc(ThreadEntity::getUpdatedAt);
    return mapper.selectPage(p, w);
  }
}
```

### 复杂 SQL（联表/报表）
```java
@Mapper
public interface ReportMapper {
  @Select("""
    SELECT t.id, t.title, u.nickname
    FROM threads t
    JOIN users u ON u.id = t.author_id
    WHERE t.deleted = 0
    ORDER BY t.updated_at DESC
    LIMIT #{limit}
  """)
  List<ThreadReportRow> latest(int limit);
}
```

### 事务与缓存建议
- 事务：继续使用 Spring `@Transactional` 在服务层统一边界，迁移不需要改变事务模型。
- 缓存：统一到 Redis 或 Spring Cache，避免 JPA 二级缓存与 MP 缓存混用；在迁移期关闭 JPA 二级缓存更稳妥。

## 风险与回滚策略
- 懒加载“消失”：JPA 的延迟加载在 MP 下需显式查询，易遗漏。对策：梳理用例，明确查询边界与投影。
- ID/版本策略不一致：确保 `IdType` 与数据库自增策略一致；乐观锁列/插件正确启用。
- 分页模型差异：JPA `Pageable` vs MP `Page<T>`，上层返回模型统一包装，减少接口变更。
- 回滚策略：保留 JPA 仓储实现（feature flag 或配置切换），出现问题可快速切回。

## 验收标准
- 功能一致性：查询结果与分页/排序与线上一致；复杂报表结果校验通过。
- 性能提升：目标接口 SQL 执行时间降低或更稳定；执行计划更可控。
- 稳定性：压测通过、错误率不升；日志与监控无异常尖峰。
- 可维护性：SQL 组织规范统一（注解/XML/片段复用），代码审查通过。

## 时间线与角色
- 架构/后端负责人：制定规范、审查 SQL、把控事务/缓存策略。
- 开发工程师：按模块迁移与自测、补充单元测试与回归脚本。
- QA：覆盖功能对比、性能与异常场景测试。
- 预估：4 周完成主流程迁移，复杂聚合根专项评估另行排期。

## 常见坑与最佳实践
- 大查询页使用**投影/DTO**，避免装配完整聚合导致内存与带宽浪费。
- 复杂统计优先写**原生 SQL**（注解/XML），对 `EXPLAIN` 与索引做评审。
- 用 `LambdaQueryWrapper` 构建条件，避免字符串字段名带来的错误与重构风险。
- 规范 SQL 片段复用，减少拷贝粘贴；为关键 SQL 写单元测试（结果与执行计划）。
- 统一错误处理与异常转换，保持与 JPA 时代对外契约一致。

## 接口契约兼容清单（新增）
- 控制器层返回模型：分页统一使用 `{ items, page, size, total }`，字段语义与顺序不变。
- 排序与分页参数：保留 `page/size/sort`；MP 层进行参数映射，避免上层签名变更。
- 错误模型：统一 `{ message, code, details?, traceId? }`；异常转换保持一致。
- 事务边界：服务层 `@Transactional` 不变；JPA → MP 不影响外部事务语义。
- 缓存契约：若原接口声明有缓存注解/约定，迁移后保持同样的键与失效策略。

## 回滚开关与配置（新增）
- 开关策略：
  - 在服务层引入特性开关 `useMp=true|false`，通过配置或环境变量切换：
  - 示例：
    ```yaml
    app:
      data-access:
        use-mp: true
    ```
  - 代码示例：
    ```java
    @Service
    public class ThreadServiceFacade {
      @Value("${app.data-access.use-mp:true}")
      private boolean useMp;
      @Resource private ThreadServiceJpa jpa;
      @Resource private ThreadServiceMp mp;
      public PageDto list(Query q){ return useMp ? mp.list(q) : jpa.list(q); }
    }
    ```
- 回滚流程：
  - 遇到严重异常时，将 `useMp=false` 并回滚至 JPA 实现；
  - 保持两套实现的单元测试与契约测试可随时运行。

## 验收清单（新增）
- 功能对比：关键接口在 `JPA` 与 `MP` 下返回一致（字段/分页/排序）。
- 性能目标：目标接口 P95 延迟下降或稳定；CPU/IO 无异常升高。
- SQL 评审：关键 SQL 均有 `EXPLAIN` 与索引建议；慢查询日志无新增尖峰。
- 监控与日志：错误率不升，新增告警阈值通过。
- 变更记录：在 `docs/API/API规范.md` 标注兼容性变更说明。

## 附录：配置片段
```yaml
# application.yml 片段（示例）
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
    cache-enabled: false
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

---

如需，我可以基于当前后端模块挑选一个“分页列表”接口，先给出完整的 Mapper/Service/测试样板，作为迁移第一步的参考实现，并辅助执行计划优化与索引建议。