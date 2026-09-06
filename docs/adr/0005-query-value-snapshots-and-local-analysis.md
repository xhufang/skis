# ADR-0005：查询值快照与查询对象局部分析复用

- 状态：已接受
- 日期：2026-09-06
- 影响版本：0.2.4-SNAPSHOT

## 背景

SKIS 将 SQL 结构和普通参数值分离：`ParameterSlot` 进入 AST 和计划，实际值由查询层保存并在执行时绑定。查询 DSL
对象同时承诺不可变、可复用和线程安全。

此前谓词仅复制 `IN` 的集合容器，`byte[]`、`java.sql.Date`、`Time` 和 `Timestamp` 等可变元素仍按引用保存。
调用方在谓词创建后修改原对象会改变后续绑定值，也可能使 continuation 参数摘要与实际绑定观察到不同内容。

此前同一个不可变查询对象虽然按分页形状复用 `CompiledQueryPlan`，但命中局部计划缓存前仍通过
`QueryStructureCompiler` 重建 ON/WHERE AST、`JoinClause`、`FromClause` 和参数布局。单表 Fast Path 也会重复从谓词
提取参数。

## 问题

需要同时保证：

1. 查询对象捕获的内置 JDBC 值不会被调用方后续修改。
2. 快照只在捕获边界发生，不转移到逐行或每次绑定热路径。
3. 同一个查询对象只分析一次 FROM/Join/ON/WHERE 和普通参数布局，计划缓存命中不再重建这些结构。
4. 参数值仍不进入 AST、结构指纹或 `CompiledQueryPlan`，分页值仍可在相同计划形状之间变化。
5. 自定义 Codec 的可变值边界必须明确，不能假装框架能推断任意类型的深复制方法。

## 候选方案

### 方案一：在每次 JDBC 绑定前复制

无需改变查询构造，但会在每次执行分配对象；复制和调用方并发修改之间仍存在竞态，continuation 摘要也可能早于绑定观察
到另一份内容。

### 方案二：给 `JdbcTypeCodec` 增加通用 snapshot 方法

Codec 最了解自定义类型，但查询列当前只携带结构元数据，不持有运行时 Codec。把 Codec 注入每个生成列会扩大公共 SPI、
生成 ABI 和查询对象体积；这类能力可在后续专门设计，不能作为当前缺陷修复的隐式架构扩张。

### 方案三：内置可变类型捕获时快照，自定义类型要求不可变；查询对象缓存一次分析结果

对 SKIS 已支持且已知复制语义的值集中快照，其他对象保持引用但通过 Codec 合同要求深度不可变。查询对象首次需要编译或
参数时生成不可变分析结果，所有局部计划形状复用它。

## 决策

采用方案三：

- `QueryColumn` 在完成 null 和 Java 类型验证后立即调用集中快照逻辑。数组按运行时组件类型递归复制；
  `java.sql.Date`、`Time` 和 `Timestamp` 使用 `clone()` 保留具体运行时类型及 Timestamp 纳秒精度。
- comparison、between、like 和 `IN`/`NOT IN` 的每个元素都经过同一捕获边界。不可变类型直接复用原对象，不产生额外
  分配。
- `SliceContinuation` 使用同一复制逻辑保存和返回 keyset 锚点，避免可变 SQL 时间值破坏 continuation 的不可变性。
- 自定义 `JdbcTypeCodec` 的谓词值类型必须深度不可变，Codec 的 `bind` 不得修改传入值。当前不新增无法兑现的通用复制
  SPI。
- `DefaultSelectQuery` 首次需要结构时，以每查询对象的同步惰性初始化生成一个 `QueryAnalysis`，其中保存唯一的
  `CompiledQueryStructure` 和无分页基础参数对象。初始化完成后的热路径只做一次 volatile 读取。
- selection、ordered/keyset、count 和复杂实体 Fast Path 编译器都接收已有 `CompiledQueryStructure`；它们不得再次从
  Query DSL 条件构建 FROM/Join/ON/WHERE。
- 无分页执行复用基础参数对象；offset/keyset 命中计划缓存时只组合已快照的普通参数与本次分页值。计划和缓存键继续不
  保存普通值。
- Fast Path 的结构 AST 与计划一同缓存在查询对象内，重复生成 `QueryCompilation` 时不再重建 `SelectStatement`。

## 后果

- 调用方在构造查询后修改数组或 JDBC 时间对象，不再改变 comparison、between、membership、摘要或绑定内容。
- 同一不可变查询对象的重复执行和不同同形分页值不再重建条件及 Join AST。
- 不同查询对象仍各自保存参数和局部分析；本决策不提前引入 0.2.7 的共享通用 `QueryPlanKey` 缓存。
- 自定义可变 Codec 值没有自动快照；违反不可变合同仍属于调用方/Codec 实现错误。

## 兼容性

没有新增或删除公共方法。内置可变参数从“后续修改可见”变为“谓词创建时冻结”，这是修复查询不可变合同的行为变化。
`JdbcTypeCodec` 仅补充现有接口的值所有权文档，不改变实现签名。内部编译器重载和查询分析对象不属于公共 API。

## 性能影响

数组和 JDBC 时间对象在谓词创建时分配一次快照；不可变类型只进行类型分支，不分配。逐次执行、参数绑定和逐行解码不再
复制这些值。

每个查询对象第一次分析使用一次局部同步；之后读取已发布的不可变分析结果。计划缓存命中不再分配条件 AST、JoinClause、
FromClause 或重新遍历谓词值。带分页执行仍需构造包含本次 limit/offset/keyset 值的参数对象，这是值变化边界而非结构
重编译。

## 安全影响

快照不改变参数化 SQL：用户值仍只进入 `PreparedStatement`。复制逻辑不调用用户序列化器、不输出值，也不把值加入异常、
日志、结构指纹或计划缓存键。continuation 继续隐藏锚点值。

## 回滚方案

若特定 JDBC 时间子类的 `clone()` 不兼容，可把该类型改为显式复制策略并保留捕获时快照语义。若局部惰性分析出现并发问题，
可暂时改为构造期分析；不得退回每次缓存命中重新编译或恢复可变引用捕获。
