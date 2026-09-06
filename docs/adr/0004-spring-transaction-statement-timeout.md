# ADR-0004：Spring 事务时限与 JDBC Statement 配置边界

- 状态：已接受
- 日期：2026-09-06
- 影响版本：0.2.4-SNAPSHOT

## 背景

ADR-0002 已确定 SKIS 的 statement timeout、fetch size 和 max rows 由不可变
`ExecutionOptions` 在参数绑定后、执行前应用。`SpringConnectionProvider` 使用
`DataSourceUtils` 获取和释放事务绑定连接，但此前没有参与 Statement 配置。

Spring 将事务剩余时限保存在绑定到 DataSource 的连接资源中。仅取得事务连接不会自动把该时限设置到普通 JDBC
Statement；调用方需要对每个 Statement 应用事务 timeout。JDBC 核心不能依赖 Spring，Spring 适配逻辑也不能通过
类型判断进入 `JdbcExecutor`。

## 问题

需要同时解决：

1. 在不让 `skis-jdbc` 依赖 Spring 的前提下，为外部事务管理器提供语句配置边界。
2. 明确 Spring 事务剩余时限与 SKIS statement timeout 冲突时的优先规则。
3. 事务已经过期时必须在执行 SQL 前失败；配置失败仍要保持 Statement 和 Connection 的关闭、异常阶段及
   suppressed 语义。
4. 新边界不能破坏现有 `ConnectionProvider` 实现或让默认 Fast Path 分配临时对象。

## 候选方案

### 方案一：只在文档中要求 `TransactionAwareDataSourceProxy`

无需修改 SKIS，但普通 DataSource 加 `SpringConnectionProvider` 是正式支持的装配方式。把正确性依赖于调用方额外包装
会使 `@Transactional(timeout=...)` 在不同装配中表现不一致。

### 方案二：`JdbcExecutor` 识别 Spring Provider

可以直接调用 `DataSourceUtils`，但会让 JDBC 核心依赖 Spring 或使用运行时类型判断，违反模块边界，也无法服务其他外部
事务管理器。

### 方案三：在 `ConnectionProvider` 增加默认 Statement 配置方法

核心只定义 JDBC 生命周期时点和 timeout 上界，Spring Provider 在该时点应用事务剩余时限。默认方法保持已有实现的
源码和二进制兼容。

## 决策

采用方案三：

- `ConnectionProvider` 新增默认 `configureStatement(PreparedStatement, ExecutionContext, int)`。
  `JdbcExecutor` 在参数绑定和内置 `ExecutionOptions` 之后、Statement 执行之前调用它。
- 第三个参数是 SKIS 已解析的 timeout：`-1` 表示未配置，`0` 表示显式使用 JDBC unlimited，正数表示 SKIS
  允许的最大秒数。Provider 可以缩短正数 timeout，但不得延长。
- `SpringConnectionProvider` 先调用 `DataSourceUtils.applyTransactionTimeout` 应用当前事务剩余秒数。若 SKIS
  正 timeout 更短，再恢复该更短值，因此最终约束为两者中更短者。
- SKIS 的显式零只覆盖 SKIS 执行器或 Session 默认值，不能取消外部 Spring 事务时限。未配置 SKIS timeout 时，
  Spring 事务时限仍独立生效。
- Spring 事务已经过期时，沿用 Spring 的 `TransactionTimedOutException` 并将事务资源标记为 rollback-only；SQL
  不得执行。
- Provider 抛出的 `SQLException` 继续归类为 `statement-configuration` 阶段。prepare 失败会关闭 Statement，随后
  按既有所有权释放 Connection；关闭和释放失败保留既定 suppressed 顺序。
- `JdbcTransaction` 的单连接 Provider 将配置调用委托给原始所有者，避免本地事务重新绑定连接后丢失自定义 Provider
  约束。

## 后果

- 普通 DataSource、`JdbcTransactionManager` 与 `SpringConnectionProvider` 的标准组合会自动遵守
  `@Transactional`/`TransactionTemplate` timeout。
- timeout 冲突不会延长任何一方的正限制；调用方仍可用更短的单语句 timeout 收紧事务内某条 SQL。
- Provider 获得一个窄的执行前扩展点，但不得执行、关闭 Statement 或修改参数绑定。
- 其他外部事务集成可以实现同一默认方法，不需要修改 JDBC 核心。

## 兼容性

新增的是接口默认方法，已有 `ConnectionProvider` 实现无需修改或重新编译。现有构造器、查询、mutation 和事务入口不变。
未实现该方法的 Provider 保持原行为。新增方法属于公共扩展 SPI，后续不得删除或改变 `-1/0/正数` 的语义。

## 性能影响

每次 Statement 增加一次可预测的默认接口调用。普通 Provider 的实现为空，不分配对象、不读取事务状态。Spring Provider
每次 Statement 执行一次 Spring 事务资源查询；只有存在正 SKIS timeout 时才读取当前 JDBC timeout 并可能恢复更短值。
这些操作发生在数据库执行前，不进入逐行解码热路径，也不改变计划缓存键。

## 安全影响

扩展点只接收已准备的 Statement、执行上下文和经过验证的整数 timeout，不接收新的 SQL 文本或绑定参数，不增加 SQL
注入或敏感参数泄漏面。更短优先规则避免外部事务时限被单语句配置意外放宽。

## 回滚方案

公共默认方法一旦进入开发线不能删除；如某个 Spring/驱动组合存在兼容问题，可以把
`SpringConnectionProvider` 的实现临时退回无操作，并保留方法与语义文档，直到加入针对性降级。JDBC 核心的调用点和
默认 Provider 行为无需回滚。
