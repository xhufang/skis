# SKIS 运行时错误码与安全诊断

本文以 0.2.7 T06 运行时诊断合同为基线，覆盖当前已接线失败入口。
0.2.9 T03 新增两个分组/聚合语义码和结构诊断，T04 新增两个分组/HAVING 构造码，均已实现并通过当前 CI，见 §6。
Java 调用方使用
`SkisErrorCode` 枚举，日志和用户文档使用显式编码；测试断言 `code()` 与结构化字段，不匹配全文消息。
APT 的 `SKISnnn` 编码独立，见 [`apt-error-codes.md`](apt-error-codes.md)。

## 1. 异常层次

| 类型                                  | 码族    | 用途                                               | 构造                                                                                             |
|-------------------------------------|-------|--------------------------------------------------|------------------------------------------------------------------------------------------------|
| `SkisException`（抽象根，core）           | 全部    | 公共运行时根，提供 `code()`、`diagnostics()`、`errorCode()` | 仅受保护构造，由子类工厂调用                                                                                 |
| `SkisConfigurationException`        | SKISA | 装配、生成索引、配置、实体元数据                                 | `of(code, detail[, cause])` / `of(code, diagnostics[, cause])`                                 |
| `QueryValidationException`（core）    | SKISQ | 查询构造、语义、结果形状、分页、continuation、非空结果                | 同上                                                                                             |
| `NonUniqueResultException`          | SKISQ | `fetchOne` 观察到第二行；`observedCount=2`              | `of(detail[, cause])` / `of(diagnostics[, cause])`                                             |
| `SqlRenderException`（dialect-api）   | SKISD | 方言能力、渲染与结构转换；外部方言唯一的公开构造入口                       | `of(code, dialectId[, missingCapability], detail[, cause])` / `of(code, diagnostics[, cause])` |
| `SkisPersistenceException`（抽象，jdbc） | SKISX | JDBC 执行与资源生命周期                                   | 子类工厂                                                                                           |
| `QueryExecutionException`           | SKISX | 查询执行；`operation=QUERY`、`statementKind=SELECT`    | `from(phase, dialectId, sql, SQLException, category)`                                          |
| `JdbcExecutionException`            | SKISX | 写入执行；`operation=MUTATION`、语句类型来自编译计划             | `from(phase, statementKind, dialectId, sql, SQLException, category)`                           |
| `MutationException`（core）           | SKISM | 写入语义、保护规则、行数合同、必需写入值为空                           | `of(code, detail[, cause])`                                                                    |
| `OptimisticLockException`           | SKISM | 乐观锁冲突                                            | `of(detail[, cause])`                                                                          |
| `TransactionException`              | SKISX | 本地事务/会话生命周期                                      | `of(code, detail[, cause])`                                                                    |

不保留仅接受 message 的构造入口。异常子类在构造时校验码族；错误码、异常类型和字段必须匹配。
原始 `cause` 与 `suppressed` 保留供排障，但默认 message/diagnostics 不展开它们。
数据库错误在 JDBC 边界只分类一次；写入触发的重复键等仍使用 SKISX，不建立同义 SKISM 编码。
数据库错误不被重复包装：写入执行直接抛出带 SKISX 码的 `JdbcExecutionException`。

## 2. 诊断字段与缺省

`SkisDiagnostics` 是不可变分组值对象，只保存结构信息：

| 信息组  | 字段                                                                  | 缺省/约束                                       |
|------|---------------------------------------------------------------------|---------------------------------------------|
| 阶段   | `stage`                                                             | 必需；未显式提供时使用错误码的默认阶段                         |
| 查询位置 | `blockPath`、`clause`、`sourceIndex`、`sourceAlias`、`expressionPath`   | 全未知时整组缺省；索引非负                               |
| 查询语义 | `reason`、`aggregateKind`、`aggregateBlockPath`、`missingDependency`、`missingPropertyOrdinal`、`missingOutputOrdinal` | 仅分组/聚合语义失败使用；原因及必需字段矩阵见 §6 |
| 方言   | `dialectId`、`productName`、`productVersion`、`missingCapability`      | 全未知时整组缺省；不探测数据库                             |
| 执行   | `operation`、`phase`、`statementKind`、`sqlFingerprint`、`dataSourceId` | 全未知时整组缺省；指纹只含模板，不含参数/标签                     |
| 驱动   | `sqlState`、`vendorCode`、`databaseCategory`                          | 全未知时整组缺省；分类只来自 JDBC 边界                      |
| 原因事实 | `expectedType`、`actualType`、`observedCount`、`detail`                | 全未知时整组缺省；`observedCount` 非负；`detail` 只放结构文字 |

不保存完整 SQL、参数值、结果值、AST、JDBC 资源或任意 Object；不调用参数/结果对象的 `toString()`。
未知信息明确缺省，不伪造产品版本、数据源、SQL 指纹或精确总行数；`fetchOne` 只记录至少两行。

## 3. 完整错误码表

`SKISX` 的数据库分类码与 `SqlExceptionCategory` 固定映射；执行阶段使用分类码，连接获取/配置/绑定/关闭/释放使用阶段码。
未使用的编号可以留空，但已发布编号不得删除后复用于其他语义。

| 编码         | 枚举                                     | 码族        | 默认阶段                | 含义                                                                           | 必需/建议字段                                                                    | 触发类别                        |
|------------|----------------------------------------|-----------|---------------------|------------------------------------------------------------------------------|----------------------------------------------------------------------------|-----------------------------|
| `SKISA001` | `ASSEMBLY_MODEL_MISSING`               | ASSEMBLY  | ASSEMBLY            | runtime model is not registered for the requested entity                     | detail；有原始异常时保留 cause                                                      | 装配、配置或元数据声明                 |
| `SKISA002` | `ASSEMBLY_MODEL_DUPLICATE`             | ASSEMBLY  | ASSEMBLY            | runtime model or mutation plan set is registered more than once              | detail；有原始异常时保留 cause                                                      | 装配、配置或元数据声明                 |
| `SKISA003` | `ASSEMBLY_INDEX_INVALID`               | ASSEMBLY  | ASSEMBLY            | generated provider index is missing or malformed                             | detail；有原始异常时保留 cause                                                      | 装配、配置或元数据声明                 |
| `SKISA004` | `ASSEMBLY_ABI_INCOMPATIBLE`            | ASSEMBLY  | ASSEMBLY            | generated code is not compatible with the runtime ABI                        | detail；有原始异常时保留 cause                                                      | 装配、配置或元数据声明                 |
| `SKISA005` | `ASSEMBLY_PROVIDER_INVALID`            | ASSEMBLY  | ASSEMBLY            | generated provider cannot be loaded or initialized                           | detail；有原始异常时保留 cause                                                      | 装配、配置或元数据声明                 |
| `SKISA006` | `ASSEMBLY_CONFIGURATION_INVALID`       | ASSEMBLY  | CONFIGURATION       | required runtime configuration is missing or invalid                         | detail；有原始异常时保留 cause                                                      | 装配、配置或元数据声明                 |
| `SKISA007` | `CACHE_CONFIGURATION_INVALID`          | ASSEMBLY  | CACHE_CONFIGURATION | plan cache configuration is invalid                                          | detail；有原始异常时保留 cause                                                      | 装配、配置或元数据声明                 |
| `SKISA008` | `METADATA_INVALID`                     | ASSEMBLY  | METADATA            | entity metadata violates a structural contract                               | detail；有原始异常时保留 cause                                                      | 装配、配置或元数据声明                 |
| `SKISA009` | `METADATA_PRIMARY_KEY_REQUIRED`        | ASSEMBLY  | METADATA            | writable entity metadata requires a primary key                              | detail；有原始异常时保留 cause                                                      | 装配、配置或元数据声明                 |
| `SKISA010` | `METADATA_VERSION_INVALID`             | ASSEMBLY  | METADATA            | optimistic-version metadata is invalid                                       | detail；有原始异常时保留 cause                                                      | 装配、配置或元数据声明                 |
| `SKISA011` | `MUTATION_BINDERS_MISSING`             | ASSEMBLY  | ASSEMBLY            | generated mutation bindings are missing or incompatible                      | detail；有原始异常时保留 cause                                                      | 装配、配置或元数据声明                 |
| `SKISQ001` | `QUERY_SOURCE_OUT_OF_SCOPE`            | QUERY     | SEMANTIC_VALIDATION | query source is not visible in the final query scope                         | detail；必要时 query 位置                                                        | 查询构造/语义/结果合同                |
| `SKISQ002` | `QUERY_WHERE_CONTRACT`                 | QUERY     | QUERY_CONSTRUCTION  | WHERE construction violates its single-predicate contract                    | detail；必要时 query 位置                                                        | 查询构造/语义/结果合同                |
| `SKISQ003` | `QUERY_ORDER_BY_CONTRACT`              | QUERY     | QUERY_CONSTRUCTION  | ORDER BY construction is empty, repeated, or inconsistent                    | detail；必要时 query 位置                                                        | 查询构造/语义/结果合同                |
| `SKISQ004` | `QUERY_ORDER_BY_NOT_SELECTED`          | QUERY     | SEMANTIC_VALIDATION | ORDER BY expression is not part of the selected DISTINCT result              | detail；必要时 query 位置                                                        | 查询构造/语义/结果合同                |
| `SKISQ005` | `QUERY_JOIN_CONTRACT`                  | QUERY     | QUERY_CONSTRUCTION  | JOIN construction violates the selected JOIN contract                        | detail；必要时 query 位置                                                        | 查询构造/语义/结果合同                |
| `SKISQ006` | `QUERY_SELECTION_SHAPE`                | QUERY     | SEMANTIC_VALIDATION | selected expressions and their resolved result shape do not match            | detail；必要时 expectedType/actualType                                         | 查询构造/语义/结果合同                |
| `SKISQ007` | `QUERY_DERIVED_SHAPE`                  | QUERY     | SEMANTIC_VALIDATION | derived output or derived relation shape is invalid                          | detail；必要时 query 位置                                                        | 查询构造/语义/结果合同                |
| `SKISQ008` | `QUERY_TYPE_MISMATCH`                  | QUERY     | SEMANTIC_VALIDATION | expression or parameter Java/SQL type does not match the query contract      | detail、expectedType/actualType                                             | 查询构造/语义/结果合同                |
| `SKISQ009` | `QUERY_NULLABILITY_CONTRACT`           | QUERY     | SEMANTIC_VALIDATION | declared nullability does not match the effective query expression           | detail；必要时 query 位置                                                        | 查询构造/语义/结果合同                |
| `SKISQ010` | `QUERY_PARAMETER_MISSING`              | QUERY     | PARAMETER_BINDING   | a declared query parameter has no binding                                    | detail（参数名/类型，不含值）                                                         | 查询构造/语义/结果合同                |
| `SKISQ011` | `QUERY_PARAMETER_DECLARATION`          | QUERY     | PARAMETER_BINDING   | a query parameter binding is undeclared or duplicated                        | detail（参数名/类型，不含值）                                                         | 查询构造/语义/结果合同                |
| `SKISQ012` | `QUERY_PARAMETER_VALUE`                | QUERY     | PARAMETER_BINDING   | a query parameter value violates its declared type or nullability            | detail、expectedType/actualType；不记录值                                        | 查询构造/语义/结果合同                |
| `SKISQ013` | `QUERY_PARAMETER_LAYOUT`               | QUERY     | COMPILATION         | logical or JDBC parameter layout does not match the compiled statement       | detail、必要的计数                                                               | 查询构造/语义/结果合同                |
| `SKISQ014` | `QUERY_PROJECTION_MAPPING`             | QUERY     | RESULT_SHAPE        | generated projection mapping does not match its selected parameters          | detail、参数序号/类型                                                             | 查询构造/语义/结果合同                |
| `SKISQ015` | `QUERY_RESULT_MAPPING`                 | QUERY     | RESULT_SHAPE        | resolved value mapping or generated decoder is inconsistent                  | detail、expectedType/actualType                                             | 查询构造/语义/结果合同                |
| `SKISQ016` | `QUERY_NON_NULL_RESULT`                | QUERY     | RESULT_DECODING     | a non-null query result decoded SQL NULL                                     | detail、expectedType；不记录值                                                   | 查询构造/语义/结果合同                |
| `SKISQ017` | `QUERY_MULTIPLE_ROWS`                  | QUERY     | RESULT_DECODING     | a query requiring at most one row observed more than one                     | observedCount>=2、detail；不伪造总行数                                             | 结果合同：多行                     |
| `SKISQ018` | `QUERY_PAGINATION_ORDER_REQUIRED`      | QUERY     | PAGINATION          | pagination requires an explicit stable ORDER BY                              | detail；必要时 query 位置                                                        | 分页/排序稳定性                    |
| `SKISQ019` | `QUERY_PAGINATION_UNSTABLE_ORDER`      | QUERY     | PAGINATION          | pagination ordering is not stable for a table source                         | detail、来源序号/别名                                                             | 分页/排序稳定性                    |
| `SKISQ020` | `QUERY_PAGINATION_UNSUPPORTED`         | QUERY     | PAGINATION          | pagination shape is not supported for this query                             | detail；必要时 query 位置                                                        | 分页/排序稳定性                    |
| `SKISQ021` | `QUERY_PAGINATION_OVERFLOW`            | QUERY     | PAGINATION          | page size or slice offset exceeds the supported numeric range                | detail                                                                     | 分页/排序稳定性                    |
| `SKISQ022` | `QUERY_CONTINUATION_INVALID`           | QUERY     | CONTINUATION        | slice continuation format, ABI, order, or parameter digest does not match    | detail；必要时 continuation 结构字段                                               | continuation 解码/匹配          |
| `SKISQ023` | `QUERY_CONTINUATION_VALUE`             | QUERY     | CONTINUATION        | slice continuation ordering value is missing, null, or of the wrong type     | detail；必要时 continuation 结构字段                                               | continuation 解码/匹配          |
| `SKISQ024` | `QUERY_COUNT_CONTRACT`                 | QUERY     | SEMANTIC_VALIDATION | explicit or automatic count cannot preserve the query semantics              | detail；必要时 query 位置                                                        | 查询构造/语义/结果合同                |
| `SKISQ025` | `QUERY_UNSUPPORTED_STRUCTURE`          | QUERY     | QUERY_CONSTRUCTION  | query structure is not supported by the current portable contract            | detail；必要时 query 位置                                                        | 查询构造/语义/结果合同                |
| `SKISQ026` | `QUERY_CAPTURED_VALUE`                 | QUERY     | QUERY_CONSTRUCTION  | reusable query description captured an ordinary value                        | detail；不记录捕获值                                                              | 查询构造/语义/结果合同                |
| `SKISQ027` | `QUERY_CURSOR_THREAD`                  | QUERY     | USAGE               | cursor or stream was used outside its resource-owning thread                 | detail（操作名）                                                                | 查询构造/语义/结果合同                |
| `SKISQ028` | `QUERY_EXPRESSION_IDENTITY`            | QUERY     | SEMANTIC_VALIDATION | expression or table occurrence identity does not belong to this query        | detail、来源序号/别名                                                             | 查询构造/语义/结果合同                |
| `SKISQ029` | `QUERY_COMPILED_SHAPE`                 | QUERY     | COMPILATION         | compiled structure or generated mapping count does not match the description | detail、必要的计数                                                               | 查询构造/语义/结果合同                |
| `SKISQ030` | `QUERY_METADATA_REQUIRED`              | QUERY     | SEMANTIC_VALIDATION | query operation requires primary-key or version metadata that is absent      | detail、实体/来源标识                                                             | 查询构造/语义/结果合同                |
| `SKISQ031` | `QUERY_GROUPING_CONTRACT` | QUERY | SEMANTIC_VALIDATION | grouping keys or grouped expressions violate the query semantics | detail、完整 query 检测位置、querySemantics；缺失列依赖带块/来源/列 ordinal | 分组键及分组安全 |
| `SKISQ032` | `QUERY_AGGREGATE_CONTRACT` | QUERY | SEMANTIC_VALIDATION | aggregate clause, nesting, or query-block ownership is invalid | detail、完整 query 检测位置、querySemantics；聚合种类/书写块 | 聚合子句、嵌套及归属 |
| `SKISD001` | `DIALECT_FEATURE_UNSUPPORTED`          | DIALECT   | DIALECT_VALIDATION  | dialect does not support the required capability                             | dialectId、missingCapability、detail                                         | 方言能力缺失                      |
| `SKISD002` | `DIALECT_RESERVED_STRUCTURE`           | DIALECT   | DIALECT_VALIDATION  | dialect cannot render reserved structure before its capability is enabled    | dialectId、missingCapability、detail                                         | 方言渲染/结构转换                   |
| `SKISD003` | `DIALECT_INVALID_STRUCTURE`            | DIALECT   | DIALECT_VALIDATION  | structure cannot be rendered by the selected dialect                         | dialectId、detail                                                           | 方言渲染/结构转换                   |
| `SKISD004` | `DIALECT_NULLS_ORDERING_UNSUPPORTED`   | DIALECT   | DIALECT_VALIDATION  | dialect requires native NULLS FIRST/LAST for this ordering                   | dialectId、missingCapability、detail                                         | 方言渲染/结构转换                   |
| `SKISD005` | `DIALECT_PARAMETER_SHAPE`              | DIALECT   | RENDERING           | dialect rendered a parameter shape that does not match the AST               | dialectId、detail                                                           | 方言渲染/结构转换                   |
| `SKISD006` | `DIALECT_RENDER_FAILURE`               | DIALECT   | RENDERING           | dialect rendering failed                                                     | dialectId、detail                                                           | 方言渲染/结构转换                   |
| `SKISX001` | `JDBC_CONNECTION_ACQUIRE_FAILED`       | EXECUTION | RESOURCE_ACQUIRE    | JDBC connection could not be acquired                                        | dialectId、phase、operation、sqlFingerprint；SQLState/vendor code 可缺省          | JDBC 资源/参数生命周期              |
| `SKISX002` | `JDBC_STATEMENT_CONFIGURE_FAILED`      | EXECUTION | EXECUTION           | JDBC statement could not be configured                                       | dialectId、phase、operation、sqlFingerprint；SQLState/vendor code 可缺省          | JDBC 资源/参数生命周期              |
| `SKISX004` | `JDBC_RESULT_SET_CLOSE_FAILED`         | EXECUTION | RESOURCE_CLOSE      | JDBC result set could not be closed                                          | dialectId、phase、operation、sqlFingerprint；SQLState/vendor code 可缺省          | JDBC 资源/参数生命周期              |
| `SKISX005` | `JDBC_STATEMENT_CLOSE_FAILED`          | EXECUTION | RESOURCE_CLOSE      | JDBC statement could not be closed                                           | dialectId、phase、operation、sqlFingerprint；SQLState/vendor code 可缺省          | JDBC 资源/参数生命周期              |
| `SKISX006` | `JDBC_CONNECTION_RELEASE_FAILED`       | EXECUTION | RESOURCE_CLOSE      | JDBC connection could not be released                                        | dialectId、phase、operation、sqlFingerprint；SQLState/vendor code 可缺省          | JDBC 资源/参数生命周期              |
| `SKISX007` | `JDBC_PARAMETER_BINDING_FAILED`        | EXECUTION | EXECUTION           | compiled JDBC parameter binder violated its parameter contract               | dialectId、phase、operation、sqlFingerprint；SQLState/vendor code 可缺省          | JDBC 资源/参数生命周期              |
| `SKISX010` | `DB_DUPLICATE_KEY`                     | EXECUTION | EXECUTION           | a unique or primary key was duplicated                                       | dialectId、phase、operation、sqlFingerprint、category；SQLState/vendor code 可缺省 | 数据库分类：DUPLICATE_KEY         |
| `SKISX011` | `DB_FOREIGN_KEY_VIOLATION`             | EXECUTION | EXECUTION           | a foreign-key relationship was violated                                      | dialectId、phase、operation、sqlFingerprint、category；SQLState/vendor code 可缺省 | 数据库分类：FOREIGN_KEY_VIOLATION |
| `SKISX012` | `DB_CONSTRAINT_VIOLATION`              | EXECUTION | EXECUTION           | an integrity constraint was violated                                         | dialectId、phase、operation、sqlFingerprint、category；SQLState/vendor code 可缺省 | 数据库分类：CONSTRAINT_VIOLATION  |
| `SKISX013` | `DB_CONNECTION_FAILURE`                | EXECUTION | EXECUTION           | the database connection failed or became unusable                            | dialectId、phase、operation、sqlFingerprint、category；SQLState/vendor code 可缺省 | 数据库分类：CONNECTION_FAILURE    |
| `SKISX014` | `DB_TIMEOUT`                           | EXECUTION | EXECUTION           | statement execution exceeded a database or JDBC timeout                      | dialectId、phase、operation、sqlFingerprint、category；SQLState/vendor code 可缺省 | 数据库分类：TIMEOUT               |
| `SKISX015` | `DB_QUERY_CANCELED`                    | EXECUTION | EXECUTION           | statement execution was canceled                                             | dialectId、phase、operation、sqlFingerprint、category；SQLState/vendor code 可缺省 | 数据库分类：QUERY_CANCELED        |
| `SKISX016` | `DB_LOCK_NOT_AVAILABLE`                | EXECUTION | EXECUTION           | a required database lock could not be acquired                               | dialectId、phase、operation、sqlFingerprint、category；SQLState/vendor code 可缺省 | 数据库分类：LOCK_NOT_AVAILABLE    |
| `SKISX017` | `DB_DEADLOCK`                          | EXECUTION | EXECUTION           | concurrent transactions formed a deadlock                                    | dialectId、phase、operation、sqlFingerprint、category；SQLState/vendor code 可缺省 | 数据库分类：DEADLOCK              |
| `SKISX018` | `DB_SERIALIZATION_FAILURE`             | EXECUTION | EXECUTION           | a transaction could not be serialized                                        | dialectId、phase、operation、sqlFingerprint、category；SQLState/vendor code 可缺省 | 数据库分类：SERIALIZATION_FAILURE |
| `SKISX019` | `DB_UNCATEGORIZED`                     | EXECUTION | EXECUTION           | database failure could not be classified safely                              | dialectId、phase、operation、sqlFingerprint、category；SQLState/vendor code 可缺省 | 数据库分类：UNCATEGORIZED         |
| `SKISX020` | `TRANSACTION_EXTERNAL_OWNERSHIP`       | EXECUTION | TRANSACTION         | connection provider delegates transaction ownership to an external manager   | detail；原始 cause 保留                                                         | 事务/会话生命周期                   |
| `SKISX021` | `TRANSACTION_BEGIN_FAILED`             | EXECUTION | TRANSACTION         | JDBC transaction could not begin                                             | detail；原始 cause 保留                                                         | 事务/会话生命周期                   |
| `SKISX022` | `TRANSACTION_COMMIT_OUTCOME_UNKNOWN`   | EXECUTION | TRANSACTION         | JDBC commit failed and the transaction outcome may be unknown                | detail；原始 cause 保留                                                         | 事务/会话生命周期                   |
| `SKISX023` | `TRANSACTION_ROLLBACK_OUTCOME_UNKNOWN` | EXECUTION | TRANSACTION         | JDBC rollback failed and the transaction outcome may be unknown              | detail；原始 cause 保留                                                         | 事务/会话生命周期                   |
| `SKISX024` | `TRANSACTION_NOT_ACTIVE`               | EXECUTION | TRANSACTION         | transaction is not active                                                    | detail；原始 cause 保留                                                         | 事务/会话生命周期                   |
| `SKISX025` | `TRANSACTION_CLOSE_FAILED`             | EXECUTION | TRANSACTION         | transaction cleanup could not restore or release its connection              | detail；原始 cause 保留                                                         | 事务/会话生命周期                   |
| `SKISX026` | `TRANSACTION_AFTER_COMMIT_FAILED`      | EXECUTION | TRANSACTION         | transaction committed but an after-commit callback failed                    | detail；原始 cause 保留                                                         | 事务/会话生命周期                   |
| `SKISX027` | `SESSION_INACTIVE`                     | EXECUTION | TRANSACTION         | SKIS session is no longer active                                             | detail；原始 cause 保留                                                         | 事务/会话生命周期                   |
| `SKISM001` | `MUTATION_ENTITY_READ_ONLY`            | MUTATION  | SEMANTIC_VALIDATION | read-only entity cannot be mutated                                           | detail；实体名                                                                 | 写入语义、保护规则或乐观锁               |
| `SKISM002` | `MUTATION_NO_INSERTABLE_PROPERTIES`    | MUTATION  | SEMANTIC_VALIDATION | entity has no insertable properties                                          | detail；实体名                                                                 | 写入语义、保护规则或乐观锁               |
| `SKISM003` | `MUTATION_NO_UPDATABLE_PROPERTIES`     | MUTATION  | SEMANTIC_VALIDATION | entity has no updatable properties                                           | detail；实体名                                                                 | 写入语义、保护规则或乐观锁               |
| `SKISM004` | `MUTATION_PRIMARY_KEY_REQUIRED`        | MUTATION  | SEMANTIC_VALIDATION | mutation Fast Path requires primary-key metadata                             | detail；实体名                                                                 | 写入语义、保护规则或乐观锁               |
| `SKISM005` | `MUTATION_FAST_PATH_PRIMARY_KEY`       | MUTATION  | SEMANTIC_VALIDATION | mutation Fast Path requires exactly one primary-key property                 | detail；实体名                                                                 | 写入语义、保护规则或乐观锁               |
| `SKISM006` | `MUTATION_UNEXPECTED_ROW_COUNT`        | MUTATION  | EXECUTION           | mutation affected an unexpected number of rows                               | detail、expected/actual 行数                                                  | 写入语义、保护规则或乐观锁               |
| `SKISM007` | `MUTATION_ENTITY_TYPE_MISMATCH`        | MUTATION  | INPUT_CONSTRUCTION  | mutation entity value does not match the generated entity type               | detail、expectedType/actualType                                             | 写入语义、保护规则或乐观锁               |
| `SKISM008` | `MUTATION_VALUE_TYPE_MISMATCH`         | MUTATION  | INPUT_CONSTRUCTION  | mutation value does not match the property Java type                         | detail、expectedType/actualType；不记录值                                        | 写入语义、保护规则或乐观锁               |
| `SKISM009` | `MUTATION_OPTIMISTIC_LOCK_CONFLICT`    | MUTATION  | EXECUTION           | version-checked update no longer matches the stored entity version           | detail；实体名                                                                 | 写入语义、保护规则或乐观锁               |
| `SKISM010` | `MUTATION_VERSION_READ_FAILED`         | MUTATION  | EXECUTION           | optimistic version could not be read from the entity                         | detail；实体名；原始 cause 保留                                                     | 写入语义、保护规则或乐观锁               |
| `SKISM011` | `MUTATION_VERSION_TYPE_MISMATCH`       | MUTATION  | SEMANTIC_VALIDATION | optimistic version value does not match the declared version type            | detail、expectedType/actualType                                             | 写入语义、保护规则或乐观锁               |
| `SKISM012` | `MUTATION_PARAMETER_SHAPE`             | MUTATION  | COMPILATION         | dialect rendered an unexpected mutation parameter shape                      | detail、语句类型                                                                | 写入语义、保护规则或乐观锁               |
| `SKISM013` | `MUTATION_REQUIRED_VALUE_NULL`         | MUTATION  | INPUT_CONSTRUCTION  | required mutation property value is null                                     | detail、属性名/索引；不记录值                                                         | 写入语义、保护规则或乐观锁               |

## 4. JDBC 分类与 Spring 映射

| 数据库类别                 | 错误码                        | Spring 异常                            |
|-----------------------|----------------------------|--------------------------------------|
| DUPLICATE_KEY         | `DB_DUPLICATE_KEY`         | `DuplicateKeyException`              |
| FOREIGN_KEY_VIOLATION | `DB_FOREIGN_KEY_VIOLATION` | `DataIntegrityViolationException`    |
| CONSTRAINT_VIOLATION  | `DB_CONSTRAINT_VIOLATION`  | `DataIntegrityViolationException`    |
| TIMEOUT               | `DB_TIMEOUT`               | `QueryTimeoutException`              |
| QUERY_CANCELED        | `DB_QUERY_CANCELED`        | 未分类 `DataAccessException`；不误归超时      |
| LOCK_NOT_AVAILABLE    | `DB_LOCK_NOT_AVAILABLE`    | `CannotAcquireLockException`         |
| CONNECTION_FAILURE    | `DB_CONNECTION_FAILURE`    | `DataAccessResourceFailureException` |
| DEADLOCK              | `DB_DEADLOCK`              | `ConcurrencyFailureException`        |
| SERIALIZATION_FAILURE | `DB_SERIALIZATION_FAILURE` | `ConcurrencyFailureException`        |
| UNCATEGORIZED         | `DB_UNCATEGORIZED`         | 未分类 `DataAccessException`            |

`NonUniqueResultException` 映射为 `IncorrectResultSizeDataAccessException`，只报告期望 1 行、已观察到
2 行；
`OptimisticLockException` 映射为 `OptimisticLockingFailureException`。配置、查询语义、方言和事务失败不强行翻译；
Spring 不重新分类，不解析消息。执行失败为主异常，后续关闭/释放失败加入 `suppressed`；只有释放失败且无主异常时才成为主异常。

## 5. 安全与验证

默认 message、diagnostics、日志和监听事件使用无值安全快照；不展开原始 cause/suppressed。
原始异常链仍可取得，因此不能宣称完整堆栈已脱敏。`ParameterRedactor` 与全仓日志治理属于 0.2.8。

T06 源码验证覆盖：码表唯一性与码族、受约束构造、诊断缺省与不可变、敏感参数/结果/驱动消息、
`toString()` 抛错的参数、JDK Optional 风格与用户回调异常不重分类、JDBC 单次分类、Spring 映射、
cause/suppressed 和资源释放顺序、外部 SPI 仅凭公开合同构造诊断。编译/运行证据按 T09/T10 汇总。

## 6. 0.2.9 分组与聚合语义扩展

下表给出当前分组与聚合诊断；使用边界见[聚合与分组](aggregates-and-grouping.md)。
SKISQ031/032 及其 AST 失败路径、SKISQ033/034 及其 DSL 构造失败路径均已接线，SkisErrorCode 当前共 88 个成员；
新增实现与合同已通过 Java 21/25、PG/H2 及独立消费者验证。
适用异常均为既有 QueryValidationException，family 为 QUERY。

| 编码 | 枚举成员 | 默认阶段 | 原因/触发 | 实现责任 |
| --- | --- | --- | --- | --- |
| SKISQ031 | QUERY_GROUPING_CONTRACT | SEMANTIC_VALIDATION | 非分组依赖、重复分组键、非法键形式 | T03 已验证 |
| SKISQ032 | QUERY_AGGREGATE_CONTRACT | SEMANTIC_VALIDATION | 聚合出现在非法子句、同块嵌套、仅依赖祖先列 | T03 已验证 |
| SKISQ033 | QUERY_GROUP_BY_CONTRACT | QUERY_CONSTRUCTION | groupBy 的 null/空输入、重复声明及非法调用形状 | T04 已验证 |
| SKISQ034 | QUERY_HAVING_CONTRACT | QUERY_CONSTRUCTION | having 重复、缺少 HAVING 的 andHaving/orHaving 及 null 条件 | T04 已验证 |

SkisDiagnostics 已新增不可变 QuerySemantics 组和 QuerySemanticReason 嵌套枚举，完整字段与码/原因/必需字段矩阵
由检测点直接生成。六种原因包括未分组依赖、重复键、非法键、非法聚合子句、同块嵌套及仅依赖祖先列的聚合；
码与原因、必需位置字段在异常构造时一并校验。
该组包含强类型原因、受控聚合种类/书写块、首个缺失依赖位置以及物理属性或派生输出 ordinal；既有 query 组保存检测点。
同码的不同查询块/子句通过字段区分，测试断言 code、reason 和位置，不匹配全文消息。

T03 在 AST 检测点形成语义异常，受控分析及既有 query 原描述分析入口原样传播；T04 已开放公共聚合 DSL，
并在调用捕获点形成构造异常。方言转换及实际结果映射已由 T05/T06 接通并验证。
类型/来源/nullability/参数错误使用既有专门码，count、映射和方言失败也保留自身分类。
新增诊断只在失败时分配，不进入共享计划，不保存 AST、表达式、参数环境、绑定值或资源。
AggregateGroupingTest、SkisDiagnosticsTest、CompilationPipelineTest 与 EntityPlanSetTest 覆盖新增合同，已登记必需回归清单。
运行证据见 PR #91 合入提交 980ad24 的 [CI](https://github.com/xhufang/skis/actions/runs/40284163961)。
本地仅静态核对，未编译运行。
