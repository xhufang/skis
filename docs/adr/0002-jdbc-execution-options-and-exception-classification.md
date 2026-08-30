# ADR-0002：JDBC 执行选项与方言异常分类边界

- 状态：已接受
- 日期：2026-08-30
- 影响版本：0.2.1-SNAPSHOT

## 背景

0.2.1 开始提供 statement timeout、fetch size、max rows 和 query tag，同时需要让 Spring
`PersistenceExceptionTranslator` 基于数据库错误类别翻译 SKIS 异常。现有 `ExecutionContext` 是空兼容契约，
`Dialect` 只组合标识符、能力和 Renderer，查询与 mutation 已经共享 `JdbcExecutor` 的资源关闭及安全诊断路径。

执行选项必须保持 0.2.0 默认路径不变，并且不能让参数值或每次调用的诊断标签进入查询计划。异常分类属于新的公共方言扩展
SPI，按开发指南需要记录其兼容性、性能和回滚方式。

## 问题

需要同时解决以下问题：

1. 如何在不破坏现有 `ConnectionProvider` 实现的情况下扩展 `ExecutionContext`。
2. 如何表达“未设置”和 JDBC 中有明确含义的零值，并实现单次语句覆盖执行器默认值。
3. 如何避免每次执行为了合并默认选项而分配临时对象。
4. 如何让 PostgreSQL、H2 及后续方言分类 SQLState/vendor code，而不让核心模块依赖 Spring。
5. 如何让 query tag 不污染计划缓存键、不泄漏参数，也不能闭合 SQL 注释或注入第二条语句。

## 候选方案

### 执行选项

1. 直接向所有查询、写入和 `ConnectionProvider` 方法增加独立参数。类型直观，但会扩大公共 API、破坏已有实现并持续增加参数。
2. 使用可变 ThreadLocal 保存当前选项。调用方便，但会在线程池、虚拟线程和嵌套调用中形成泄漏及覆盖风险。
3. 使用不可变 `ExecutionOptions`，由 `ExecutionContext` 默认方法暴露，执行器保存默认值并在读取每个字段时选择单次覆盖值。

### 异常分类

1. 在 `skis-spring` 中硬编码每个数据库的错误码。实现集中，但 Spring 模块会复制方言知识并与核心执行行为漂移。
2. 让 `Dialect` 直接返回 Spring `DataAccessException`。方言模块将依赖 Spring，违反模块边界。
3. 增加只依赖 JDBC 的 `ExceptionClassifier` 和稳定类别枚举，方言负责分类，Spring 适配器负责映射。

## 决策

采用两个方案 3：

- `ExecutionOptions` 是不可变值对象，使用明确的“未设置”状态；零 timeout、零 fetch size 和零 max rows 保留 JDBC
  语义，不与未设置混同。
- `ExecutionContext.executionOptions()` 作为默认方法返回空选项，现有实现无需重新编译或实现新方法。
- 每个非空 `ExecutionOptions` 缓存并复用一个轻量 `ExecutionContext` 视图，重复使用同一选项时不按调用分配包装对象。
- `JdbcExecutor` 持有执行器默认选项。执行时逐字段读取单次选项；单次已设置值优先，否则使用执行器默认值，再否则不调用
  JDBC setter 并保留驱动默认值。普通默认路径不创建合并对象。
- timeout 在构建 `ExecutionOptions` 时转换为 JDBC 整秒；正的亚秒部分向上取整，负数及超过 `Integer.MAX_VALUE`
  秒的值被拒绝。
- `fetchOne()` 为保持“多行失败”契约，会把有效的正数 `maxRows=1` 内部提升到 2；不会向调用方返回第二行。
- query tag 使用独立 `QueryTag` 值对象，只允许受限 ASCII 标签字符且最大 128 个字符。标签在 prepare 前以固定 SQL
  注释附加；普通参数、实体 ID、租户和用户信息不参与生成。tag 不进入 AST、编译计划或计划缓存键，失败诊断仍使用不含 tag
  的结构 SQL 指纹。
- `ExceptionClassifier` 根据 `SQLException` 链返回稳定 `SqlExceptionCategory`。精确 SQLState/vendor code 优先，
  SQLState 类别只作为保守降级；H2 `90067` 和 PostgreSQL `57P01`–`57P05` 等非 `08` 类连接状态也按
  精确码归为连接失败，未知值返回 `UNCATEGORIZED`。
- 查询取消和锁不可用保持独立类别，不冒充超时。由于部分数据库把服务端超时也报告为取消，Spring 适配器对
  `QUERY_CANCELED` 保守返回未分类异常；`LOCK_NOT_AVAILABLE` 映射为 `CannotAcquireLockException`。
- 分类器抛出的 `RuntimeException` 或 `Error` 附加到原始 `SQLException` 的 suppressed 列表并安全降级，
  不得覆盖真正的 JDBC 失败。
- `Dialect.exceptionClassifier()` 是有默认实现的小型扩展点。PostgreSQL/H2 提供各自分类器；核心和方言模块不依赖
  Spring。
- `SkisExceptionTranslator` 位于 `skis-spring`，把分类结果映射为 Spring DAO 异常，并保留 SKIS 异常与原始
  `SQLException` cause 链。

## 后果

- 未配置选项的查询和 mutation 只增加可预测的字段分支，不调用 JDBC setter、不创建合并对象或带 tag SQL。
- 带 query tag 的调用每次生成一个短 SQL 字符串；这是显式诊断功能的成本，不影响默认 Fast Path。
- 查询对象携带不可变 context，可安全复用；transaction Session 复用执行器默认选项与异常分类器。
- 自定义 `Dialect` 因默认方法保持兼容；只有希望提供精确分类的方言才实现新方法。
- 自定义查询/写入门面仍可使用旧抽象方法；新增带选项重载使用默认兼容实现，非空选项需要实现方显式支持。

## 兼容性

现有 `ExecutionContext`、`ConnectionProvider` 和 `Dialect` 实现保持二进制兼容。已有查询、mutation 和事务入口继续有效，
且空选项与 0.2.0 行为相同。新增重载和 fluent `withOptions` 不改变原查询对象。

## 性能影响

默认执行只读取不可变字段并做分支；不合并 Map、不创建 Optional、不解析 Duration、不重新渲染 SQL。timeout 转换和 tag
校验只在选项构建时执行。分类器只运行在失败路径。transaction 通过重新绑定连接提供者复用相同 codec context、默认选项和
分类器。

## 安全影响

Query tag 使用白名单和长度限制，不能包含星号、分号、引号、反斜杠、换行或其他控制字符，因而不能闭合框架注释或形成第二条
SQL。异常消息继续只包含结构指纹、SQLState、vendor code 和类别，不包含 SQL 全文、绑定参数或 query tag。

## 回滚方案

如公开分类类别需要调整，可以保留 `ExceptionClassifier` 默认方法并把具体方言暂时回退为 `UNCATEGORIZED`；Spring
适配器仍可安全返回未分类 DAO 异常。如某个驱动不接受 SQL 注释，可保留 `QueryTag` API 并在该方言中 fail-fast，不能回退为
未验证的字符串拼接。执行选项可回退为只使用空默认值，已有默认方法和重载继续保留。
