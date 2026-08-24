# 变更日志

本文件记录 SKIS 的重要变化。格式参考 Keep a Changelog；正式版本遵循语义化版本。

## [Unreleased]

### Added

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
- 添加值与 AST 分离的 `QueryColumn.eq(value)`、不可变 `EntitySelectQuery` 及 `fetchOne/fetchList/fetch` 执行语义。
- 添加 `CompiledQueryPlan` 与 `JdbcExecutor`，统一 PreparedStatement 绑定、按下标解码、结果基数检查和 JDBC 资源释放。
- 添加 APT 生成的 `EntityRuntimeModelProvider`，通过 `META-INF/skis/entities.idx` 自动加载 Codec 与 RowDecoder，不扫描类路径或运行时注解。
- 添加按实体属性数量天然有界、使用原子槽位发布的查询计划复用；单主键 Fast Path 在执行器装配时预热。
- 添加 `ConnectionProvider`、`ExecutionContext` 和默认 `DataSourceConnectionProvider`，明确连接获取与归还边界。
- 添加所有公共框架异常的统一非受检基类 `SkisException`。
- 添加无运行时参数值的基础 SQL AST：参数槽、等值谓词和单表 SELECT。
- 添加方言能力、标识符规则、渲染结果和基础 SQL Renderer SPI。
- 添加 PostgreSQL 与 H2 基础方言及单表 SELECT SQL golden tests。
- 添加无需依赖 PostgreSQL JDBC 驱动专有类型的 JSON/JSONB 文本 Codec。

### Changed

- 生成代码 ABI 更新为 3；`EntityRuntimeModelProvider` 直接携带 mutation Binder 和版本读取器，运行时不按
  生成类名称反射查找写入代码。
- 测试 `Pet` 的版本属性改为 `Long`，用于明确表达“未提供初始版本时从 0 开始”的语义。
- 生成代码 ABI 更新为 2；实体索引条目由元模型类名调整为生成式运行时模型 Provider 类名。
- 生成的 `PetTable` 改为查询 DSL 表达式，参数值只保存在执行参数中，不进入 SQL AST、结构哈希或编译计划。
- 测试示例、共享测试模型和 APT golden 统一由 `Book` 更名为 `Pet`。
- 明确 `skis-test-model` 仅供仓库内部测试使用，不进入公共 BOM、API 兼容检查或 Maven Central 发布内容。
- 同一 SQL AST 逻辑参数序号重复出现时，必须使用一致的 Java 类型和 nullability。

### Migration

- 0.0.7 有意扩展 `SkisExecutor` 的抽象契约。自定义实现必须重新编译，并实现新增的 `insert`、
  `updateById`、`deleteById`、`beginTransaction` 和 `inTransaction`；通过
  `SkisExecutorFactory` 创建内置执行器的应用无需修改装配代码。

## [0.0.4] - 2026-08-21

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

[Unreleased]: https://github.com/xhufang/skis/compare/v0.0.4...HEAD
[0.0.4]: https://github.com/xhufang/skis/releases/tag/v0.0.4
