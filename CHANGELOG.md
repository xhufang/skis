# 变更日志

本文件记录 SKIS 的重要变化。格式参考 Keep a Changelog；正式版本遵循语义化版本。

## [Unreleased]

### Added

- 新增统一的真实 PostgreSQL `findById` JMH 基准，对比手写 JDBC、SKIS、Jimmer、MyBatis、
  MyBatis-Flex、MyBatis-Plus 和 jOOQ，并保存平均耗时、分配量及 GC 数据报告。
- 添加 benchmark 数据库环境变量、运行命令、比较框架版本和公平性边界说明；凭据不写入仓库。
- 新增完整的 `SKISxxx` APT 错误指南，为每个稳定错误码提供原因、错误/正确示例、修复步骤和首次公开版本。
- 添加真实 Lombok 处理器协作、最终轮失败、全量生成源码字节稳定性及跨模块实体/投影索引冲突回归测试。

### Changed

- 将 `skis-benchmark` 重构为各框架独立模型/数据访问模块与共享 runner，所有实现使用相同数据表、
  选择列、对象形状、连接池和 JMH 参数，且不计入 Spring Boot 启动及代理成本。
- 将全部 benchmark 子模块排除出公共 API 兼容基线和 Maven Central 发布组件校验，继续只作为仓库内部性能工程模块。
- 收口 Simple Entity 的稳定属性顺序：record component 按声明顺序，Bean 先按 field 声明顺序、再按
  getter-only 属性声明顺序生成 Meta、Table、Binder、RowDecoder 和 RuntimeModel。
- 实体和投影处理器统一等待 Lombok 完成类型结构变换；最终仍未形成受支持可变 Bean 结构时保留最后一个结构诊断并报告
  `SKIS038`/`SKIS217`，处理器主代码继续不依赖 Lombok API。

### Fixed

- 统一查询和 mutation 的 JDBC 失败诊断，保留执行阶段、方言、SQL 指纹、SQLState、
  vendor code 和原始 `SQLException`，且异常消息不记录 SQL 全文或参数值。
- 区分 Connection 获取、JDBC 执行和 Connection 归还失败；在 PreparedStatement、
  ResultSet 和 Connection 异常路径中确定关闭资源，并保留后续失败为 suppressed exception。
- 移除 `findById` 和 mutation Fast Path 运行时错误消息中的 `0.0.6`、`0.0.7`
  历史实现版本提示，改为稳定的能力约束说明。
- 修复投影生成源码仍标记旧 `Projection ABI 2` 的问题，统一使用当前生成 ABI。
- 修复多个依赖索引声明同一 Provider 时被集合静默去重的问题；实体和投影索引现在保留 URL/行号来源，并明确拒绝
  重复 Provider、重复实体 Java 类型、重复投影结果类型以及缺失、重复或不兼容 ABI。

## [0.1.0] - 2026-08-26

SKIS 的第一个完整公开预览版本。发布范围只包含已经实现的核心、生成器、JDBC、查询、写入、
PostgreSQL/H2 方言和 Spring 事务连接适配模块。

### Added

- 添加可直接复制的 Pet + H2 消费者示例、完整 Maven APT 入门文档和 0.1.0 发布检查表。
- 扩展 PostgreSQL 合同测试，覆盖 update/delete、乐观锁冲突和事务回滚。
- 发布流水线增加精确的 16 个 Central 组件白名单校验。
- 添加单表标量投影，以及由 `@SkisProjection` APT 为用户 record/类生成的任意列数强类型
  `*Projection` 映射器；只渲染实际选择列，通过生成 Codec 按列下标完成无反射映射，且不生成 DTO。
  投影计划按实体、生成式强类型映射令牌、选择列和谓词结构进入共享的默认 4096 项有界 LRU，参数值不参与缓存键；
  任意用户别名仍只在不可变查询对象内单槽复用。
- 添加 PostgreSQL 投影合同测试，并验证投影参数始终使用
  PreparedStatement、ResultSet/Statement/Connection
  均可确定关闭。
- 投影计划缓存改用 APT 生成类持有的强类型静态映射令牌，避免公开基础设施入口通过重复类键混用不同结果
  Decoder；
  新增容量和访问过期配置、缓存统计、按实体失效及显式清空能力。
- 投影 APT 支持等待协作处理器在后续轮次生成构造器参数类型；最终未解析时报 `SKIS217`，参数类型无法从生成子包访问时
  报 `SKIS218`。
- 0.0.9 投影 APT 将用户结果类型绑定到来源实体，新增属性存在性、类型、可空性及 `@ProjectionProperty`
  映射校验。
- 添加生成式 `ProjectionProvider` 和 `META-INF/skis/projections.idx`，执行器装配时建立按用户结果类型索引的
  不可变投影注册表。
- 添加生成式单实体 `insert`、`updateById`、`deleteById` Fast Path，并通过统一
  `SkisExecutor` 暴露查询和写入门面。
- 添加不可变 INSERT/UPDATE/DELETE AST、版本递增表达式、逻辑 AND 谓词及 PostgreSQL/H2
  portable mutation SQL Renderer。
- 添加 `CompiledMutationPlan` 与 JDBC `executeUpdate`，验证 Binder 参数形状、影响行数和资源释放。
- 添加 `SkisSession`、`beginTransaction`、`inTransaction` 和 `afterCommit`，保证同一事务查询/写入共享
  Connection，异常路径回滚且不发布提交回调。
- 添加 `SpringConnectionProvider`，通过 Spring `DataSourceUtils` 复用外部事务管理器绑定的 Connection。
- 添加 `NUMERIC_INCREMENT` 类型正确的零值初始化、内存版本推进和溢出检查；版本冲突抛出
  `OptimisticLockException`。
- 添加 H2 实库 CRUD、乐观锁、提交和回滚集成测试，以及 PostgreSQL/H2 mutation SQL golden tests。
- 添加共享 `QueryPlanCatalog` 与 `MutationPlanCatalog`，事务 Session 只重新绑定 JDBC 执行器，不重复编译实体计划。
- 为 SELECT/INSERT/UPDATE/DELETE 统一参数 ordinal/描述符校验，并逐项验证 mutation 方言渲染参数形状。
- 添加可构造注入、线程安全的统一 `SkisExecutor`，同时提供 `findById` Fast Path 和最小单表实体 DSL。
- 添加值与 AST 分离的 `QueryColumn.eq(value)`、不可变 `EntitySelectQuery` 及
  `fetchOne/fetchList/fetch` 执行语义。
- 添加 `CompiledQueryPlan` 与 `JdbcExecutor`，统一 PreparedStatement 绑定、按下标解码、结果基数检查和
  JDBC 资源释放。
- 添加 APT 生成的 `EntityRuntimeModelProvider`，通过 `META-INF/skis/entities.idx` 自动加载 Codec 与
  RowDecoder，不扫描类路径或运行时注解。
- 添加按实体属性数量天然有界、使用原子槽位发布的查询计划复用；单主键 Fast Path 在执行器装配时预热。
- 添加 `ConnectionProvider`、`ExecutionContext` 和默认 `DataSourceConnectionProvider`，明确连接获取与归还边界。
- 添加所有公共框架异常的统一非受检基类 `SkisException`。
- 添加无运行时参数值的基础 SQL AST：参数槽、等值谓词和单表 SELECT。
- 添加方言能力、标识符规则、渲染结果和基础 SQL Renderer SPI。
- 添加 PostgreSQL 与 H2 基础方言及单表 SELECT SQL golden tests。
- 添加无需依赖 PostgreSQL JDBC 驱动专有类型的 JSON/JSONB 文本 Codec。

### Changed

- 公共 BOM 和 Central 发布范围收缩为已实现模块；计划模块显式跳过部署。
- Central 首次完整发布改为验证后人工发布，并先创建草稿 GitHub Release。
- `QueryPredicate` 改为 `QueryPredicate<E>`；表参数和 `where` 条件在 Java 编译期保持同一来源实体，同一实体的不同别名
  继续在执行前通过表表达式身份校验。
- `ProjectedSelectQuery<R>` 改为 `ProjectedSelectQuery<E, R>`，让标量和用户投影的 `where` 都保留来源实体类型。
- 生成代码 ABI 更新为 3；`EntityRuntimeModelProvider` 直接携带 mutation Binder 和版本读取器，运行时不按
  生成类名称反射查找写入代码。
- 测试 `Pet` 的版本属性改为 `Long`，用于明确表达“未提供初始版本时从 0 开始”的语义。
- 生成代码 ABI 更新为 2；实体索引条目由元模型类名调整为生成式运行时模型 Provider 类名。
- 生成的 `PetTable` 改为查询 DSL 表达式，参数值只保存在执行参数中，不进入 SQL AST、结构哈希或编译计划。
- 测试示例、共享测试模型和 APT golden 统一由 `Book` 更名为 `Pet`。
- 明确 `skis-test-model` 仅供仓库内部测试使用，不进入公共 BOM、API 兼容检查或 Maven Central 发布内容。
- 同一 SQL AST 逻辑参数序号重复出现时，必须使用一致的 Java 类型和 nullability。

### Fixed

- 修复 Central 发布扩展未被子模块继承、导致只暂存 `skis-parent` 后在 `skis-bom` 失败的问题。
- 不发布的测试和占位模块不再生成源码包、Javadoc 包或 GPG 签名，避免其内部代码阻断正式发布。
- GPG 签名改为从 `MAVEN_GPG_PASSPHRASE` 环境变量读取口令，不再在 POM 中配置敏感值。

### Migration

- 0.0.9 将用户投影入口重构为 `selectProjection(table, ResultType.class)`。用户投影改为
  `@SkisProjection(entity = Pet.class)`，并删除用户侧 `*Projection.of(...)`、列清单和
  `Projection<E, R>` 常量。
  自定义 `QueryOperations`/`SkisExecutor`/`SkisSession` 实现需要将原用户投影 `select`
  重载替换为新入口；显式引用查询
  接口的代码需将 `ProjectedSelectQuery<R>` 更新为 `ProjectedSelectQuery<E, R>`。
- 0.0.8 为 `QueryOperations` 添加标量和用户投影 `select` 重载。自定义
  `QueryOperations`/`SkisExecutor`/`SkisSession` 实现必须补充这两个方法；用户投影类型添加
  `@SkisProjection` 后，改用 APT 生成的 `*Projection.of(...)`。单表投影类型为
  `Projection<E, R>`，来源实体类型 `E` 会一直保留到 `from(QueryTable<E>)`。
- `SkisExecutor` 新增 `queryPlanCacheStatistics()` 和 `clearQueryPlanCache()`；自定义实现需要同步实现。直接使用
  `Projection.generated(...)` 的基础设施代码需先通过 `Projection.mapping(...)` 创建带结果泛型的映射令牌。
- 0.0.9 中直接使用 `Projection.generated(...)` 的基础设施代码还需传入结果类型、来源 `EntityMeta`
  和属性元数据；
  普通应用只使用 `selectProjection(table, ResultType.class)`。
- 0.0.7 有意扩展 `SkisExecutor` 的抽象契约。自定义实现必须重新编译，并实现新增的 `insert`、
  `updateById`、`deleteById`、`beginTransaction` 和 `inTransaction`；通过
  `SkisExecutorFactory` 创建内置执行器的应用无需修改装配代码。

## [0.0.4] - 2026-08-21

> Maven Central 上的 0.0.4 是一次不完整发布，只包含空的 `skis-parent` POM；
> 该版本不作为公共 API 兼容基线。0.1.0 是第一个完整可用的公开版本。

### Added

- 建立 Java 21/25 的 CI 与独立集成测试工作流。
- 建立基于 Pull Request 目标分支的 japicmp public API 兼容门禁。
- 添加 Apache License 2.0、NOTICE、项目与贡献者文档。
- 添加依赖许可证检查和 CycloneDX 1.6 SBOM 产物。
- 添加带版本/标签校验、源码、Javadoc、GPG 签名、Maven Central 和 GitHub Release 的发布流水线。

### Changed

- Maven GroupId 从 `io.skis` 调整为 `io.github.xhufang`，以匹配 Maven Central 已验证命名空间。
- 项目版本统一更新为正式版 `0.0.4`。
- 根 POM 补齐 Maven Central 所需的项目、许可证、开发者和 SCM 元数据。
- GitHub Actions 更新到 Node.js 24 运行时对应的当前主版本。

### Fixed

- 修复局部 Reactor 构建未包含 `skis-processor`，导致集成测试无法解析注解处理器的问题。

### Migration

- Maven 依赖坐标需从 `io.skis:*` 更新为 `io.github.xhufang:*`。
- Java 根包仍为 `io.skis`，源码导入路径无需修改。

[Unreleased]: https://github.com/xhufang/skis/compare/v0.1.0...HEAD

[0.1.0]: https://github.com/xhufang/skis/compare/v0.0.4...v0.1.0

[0.0.4]: https://github.com/xhufang/skis/releases/tag/v0.0.4
