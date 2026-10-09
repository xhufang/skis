# ADR-0007：共享查询计划结构键与缓存治理

- 状态：已接受
- 日期：2026-09-27
- 影响版本：0.2.6-SNAPSHOT
- 决策范围：通用查询计划 L1 缓存的身份、所有权、并发、驱逐、失效、统计、旁路和生命周期
- 落地状态：T03—T06 已实现并通过验证；T05 完成 catalog 所有的有界 L1 容器及审查修复，包括
  per-key single-flight、有界编译 admission、统一 failure outcome、generation、完整依赖键、值安全载体、统计、clear、
  显式依赖失效和访问过期/容量驱逐，用户于 2026-10-08 确认 CI 已通过。T06 的统一键组装与普通查询
  L0/L1/L2 接线、审查修复及补证已由用户于 2026-10-09 确认 CI 通过；未提供作业链接。本 ADR 继续保持“已接受”，
  因其身份与治理合同已经落地，但 0.2.6 里程碑尚未整体关闭。
- 审查修复：2026-10-09 清理三个仅测试 catalog 入口，测试改经真实 assembler/resolver；补强嵌套非根实体失效、
  count 裁剪依赖、同对象分页并发、逐终止操作统计及真实 L1 解码/生命周期合同，相关 CI 已通过。T07 compilation context
  与单次 analysis 接线、COUNT review 修复及补证源码完成，待其自身 CI。

## 背景

SKIS 已经把查询结构与普通值分开：查询条件最终形成无值 `ParameterSlot`，值由 `QueryParameters` 或查询对象的快照
环境保存。ADR-0005 又保证内置可变 JDBC 值只在捕获边界快照，并让同一个不可变查询对象复用局部分析和计划。

本 ADR 形成时，复用仍停留在单个查询对象。应用在方法体中重新构造等价查询时，新的查询对象不能命中已有计划；执行器级
`ProjectionPlanCache` 只是保留配置和公共统计入口的占位对象，所有统计恒为零。另一方面，0.2.5 已经通过查询块作用域
分析产生 `ResolvedStructureKey`：它包含稳定查询块路径、来源 occurrence、表达式、选择、排序和分页结构，不包含普通
参数值或 JVM 对象地址。

共享计划缓存会跨查询对象保存 SQL 模板、Binder 和 Decoder。错误的等价关系会让一个查询执行另一个查询的 SQL、
Binder 或 Decoder，因此缓存键和生命周期属于必须通过 ADR 固定的正确性边界，而不是普通实现细节。

本决策延续以下既有约束：

- ADR-0003：生成式投影使用稳定 `ProjectionMapping.mappingId()`，不使用映射对象地址。
- ADR-0005：参数值在捕获边界快照，但普通值不进入 AST、结构指纹或计划。
- ADR-0006：方言 lowering 只依赖结构与方言；Renderer 不承担临时语义改写。lowering 尚未落地，但其能力版本必须进入
  共享计划身份。

## 问题

需要同时确定：

1. 两个不同查询对象何时可以共享同一个 `CompiledQueryPlan`。
2. content、ordered content、count、limit、offset 和 keyset 等最终语句如何隔离。
3. 结果 Decoder、参数 Binder、Codec 和方言差异如何进入身份，同时不保存运行时对象地址。
4. 谁拥有共享缓存，以及应用 ClassLoader 卸载时怎样避免静态引用泄漏。
5. 并发 miss、编译失败、clear、按实体失效、过期和驱逐发生竞态时采用什么语义。
6. hit/miss/eviction/invalidation 如何计数，哪些查询必须明确旁路。
7. 出现正确性问题时如何关闭共享层，而不破坏 Fast Path、查询对象局部复用或 JDBC 执行。

## 候选方案

### 方案一：直接使用 AST、查询对象或 Codec/Decoder 对象作为键

实现改动最少，但部分节点和运行时行为仍可能使用对象身份。两个重建的等价查询无法稳定相等，缓存还会直接持有
Codec、元数据或生成类对象，使 ClassLoader 生命周期和键正确性绑在一起。该方案也无法安全表达结果形状和最终参数
Binder 的差异。

### 方案二：使用最终 SQL 字符串加结果 Java 类型

SQL 可以区分大部分语句形状，但生成 SQL 前仍需执行完整编译，无法让 L1 跳过编译；相同 SQL 还可能对应不同参数
Codec、nullability 或 Decoder。把方言、策略和路由差异补成字符串前缀只会形成另一套难以审计的临时协议。

### 方案三：使用解析后结构键和显式计划维度组成值键，缓存归 catalog 所有

复用已有 `ResolvedStructureKey`，另行加入结果形状、终止操作变体、最终参数 Binder 形状、方言身份和结构上下文签名。
键只保存不可变值；缓存绑定到一份 runtime registry 与一个 dialect 的 `QueryPlanCatalog`。不能形成稳定身份的查询明确
旁路 L1。

## 决策

采用方案三。

### 1. 缓存所有权与三级路径

通用查询采用以下层次，Fast Path 的实体原子槽保持独立：

1. **L0 查询对象最近计划**：服务同一个不可变查询实例的重复终止操作，不承担跨实例共享。
2. **L1 catalog 共享计划**：由一份 `EntityRuntimeRegistry + Dialect` 对应的 `QueryPlanCatalog` 拥有。
3. **L2 完整编译**：L0/L1 未命中或查询明确旁路时执行，成功后按本 ADR 的安全条件发布到 L1/L0。

T06 实现以每种结果模式的最近计划引用作为分页 L0；切换分页形状后回到 L1，L0 命中不增加 L1 统计。
`QueryPlanResolver` 只将不可变计划交给 L1，返回时为当前调用重新附着 AST/参数。clear/实体失效不清除查询对象的 L0。

不得创建 JVM 静态全局计划缓存，也不得让 Spring 容器、线程池、维护线程或其他全局单例反向持有 catalog。Session 只绑定
执行资源，不拥有另一份共享计划缓存。

### 2. 键生成阶段

`QueryPlanKey` 描述一条最终可执行语句的可移植结构。当前 0.2.6 的 content/ordered 计划在最终选择、隐藏选择、排序和
分页 AST 组装完成、完整作用域和语义解析成功后取得 `ResolvedStructureKey`，在 Renderer 之前形成键。count 使用已经
完整解析的原查询/count 来源结构，再由独立 `COUNT` 变体、结果形状和最终参数形状描述 count 转换；在 `CountAst` 纳入
统一解析结构前允许保留不会造成错误复用的冗余原选择维度，最多产生安全的额外 miss，不能省略影响 count SQL 的结构。

未来策略改写和规范化落地后，键必须观察它们产生的最终可移植语义；方言 lowering 的差异通过完整方言身份隔离，不能把
lowered AST 或最终 SQL 字符串当作唯一主键。若在得到安全键之前仍必须执行部分解析，该成本由 T07 的不可变
compilation context 收敛，不能退回对象身份键规避。

### 3. `QueryPlanKey` 的组成

键由以下全部维度组成：

| 维度 | 当前表示 | 目的 |
|---|---|---|
| 最终查询结构 | `ResolvedStructureKey` | 来源 occurrence、Join、谓词、选择、隐藏选择、排序、分页和嵌套查询块 |
| 完整实体依赖 | `Dependencies` | 保存每个查询块路径/来源 ordinal 到 loader-aware 类型令牌及实体/表值身份的映射，不持有 `Class` |
| 结果形状 | `ResultShape(kind, stableId, resultType, decoderIdentity, selectionBindings)` | 区分 required/nullable entity、required/nullable scalar、生成式投影及其 Decoder/Codec 来源 |
| 计划变体 | `PlanVariant(resultMode, QueryPaginationShape)` | 区分 content、ordered content、count、limit、offset 和 keyset |
| 最终逻辑参数 | 有序 `ParameterShape` | 区分稠密 ordinal、Java/SQL 类型、nullability 和稳定 Binder 身份 |
| 方言身份 | `DialectIdentity(id, capabilities, capabilityVersion)` | 用完整能力集合和额外行为版本隔离不同产品、能力、lowering 和渲染合同；32 位版本不单独承担集合等价 |
| 结构上下文 | 名称到稳定签名的不可变 Map | 为策略、Schema 路由、动态表等未来结构影响预留显式维度 |

结果形状由 `SelectedResult` 的实际种类直接派生，不接受调用方另传 nullable 布尔值。`stableId`、ClassLoader 感知的
`RuntimeTypeIdentity` 和有序选择 Codec 来源按以下规则共同产生：

- 实体：结果种类、已在当前 runtime registry 注册的实体运行时类型令牌和二进制类名；具体选择表达式和表 occurrence
  已在结构键中。
- 标量：结果种类、boxed Java 运行时类型令牌、SQL 类型和所选值的规范 Codec 来源。
- 生成式投影：结果运行时类型令牌、二进制类名、APT 生成的 `ProjectionMapping.mappingId()`、完整有序参数合同，以及
  每个有序选择的规范 Codec 来源。即使 public `generated(...)` 的调用方复用 mapping ID，实际 DecoderFactory 的
  loader-aware 类型仍进入结果身份，携带实例状态的 factory 再使用 mapping-instance 令牌保守隔离。

完整依赖身份与缓存条目保存的规范元数据依赖承担不同职责：前者参与 key 的 `equals/hashCode`，避免同名、不同
ClassLoader 的 JOIN/EXISTS/派生源碰撞；后者服务实体精确失效。依赖不变量检查只负责发现组装错误，不能代替键维度。
键必须保留 `ResolvedSourceIdentity`（查询块路径与来源 ordinal）到实体身份的对应关系，不能只对实体集合去重排序。
两种同名、不同 ClassLoader 的实体同时参与查询而交换来源位置时，依赖集合不变，但隐藏排序列的 Codec/reader 可能改变。
同一实体在多个查询块或多个来源位置出现时，每个 occurrence 都进入键；仅失效用的规范元数据集合按引用身份去重。

`ResultShape` 不接受调用方自行拼接的任意字符串；实体、标量和生成式投影必须通过 `SelectedResult` 和 catalog 的
`IdentityScope` 从规范查询元数据生成。元数据不属于当前 registry，或任一选择没有安全 Codec 身份时，工厂返回
“无安全身份”并由 T06 旁路 L1。这样可以把身份编码和结果种类的权威来源集中起来，避免错误 nullable 标记和第二套协议。

count 仍保留原查询结果形状身份，因为 DISTINCT count 的 SQL 可能依赖原选择形状；同时使用独立 `COUNT` 变体，不能与
普通 `Long` 标量 content 计划混用。ordered content 使用独立结果模式，因为它可能增加隐藏排序列并使用
`OrderedRow` Decoder。

分页只保存结构：NONE、LIMIT、OFFSET，或 KEYSET 加每个锚点是否为 NULL 的 marker。该结构由
`QueryPaginationShape` 统一表示，查询对象局部 L0 与共享 L1 不得各自维护一套分页枚举。limit、offset 和非空 keyset
锚点的具体值不进入键；keyset null marker 会改变 seek 谓词 SQL，所以必须进入键，且 KEYSET 形状至少包含一个 marker。

### 4. 参数与 Codec 身份

最终参数列表按逻辑槽稠密排序。每项保存 Java 类型名、`SqlType`、`Nullability` 和 `BindingIdentity`。内置分页整数/
长整数 Binder 使用框架固定枚举；Codec Binder 使用 catalog 的 `IdentityScope` 产生的规范来源身份。

不得把 `JdbcTypeCodec` 实例、`Class` 对象、`identityHashCode` 或仅有 Codec 实现类名的值放入键。仅使用实现类名无法区分
同一类的不同配置。当前 runtime registry 不可变，且 L1 不跨 catalog 共享，因此规范属性来源使用“已注册实体的
ClassLoader 感知运行时类型令牌 + 属性 ordinal”标识其固定 Codec；派生列和标量子查询递归回到原始输出来源。

`ParameterShape` 只能通过受控工厂从最终 `ParameterSlot` 和对应 `Selectable` 生成；工厂同时验证 Java/SQL 类型并
递归解析规范属性来源，并验证属性实体元数据确实属于当前 runtime registry。若元数据不属于当前 catalog 或来源不受
支持，工厂返回“无安全身份”，由 T06 的统一键组装结果显式选择旁路。

如果自定义表达式、Codec 或未来扩展无法提供稳定的结构来源，查询必须旁路 L1，而不是由调用方补一个任意字符串或删除
Binder 维度后冒险共享。
runtime registry 或 Codec 配置变化必须创建新的 `QueryPlanCatalog`，不能在原 catalog 中原地替换。

### 5. 普通值和执行资源禁止入键/值

以下内容不得进入 `QueryPlanKey`：

- 普通参数值、keyset 非空锚点、limit、offset、tenant ID、用户 ID。
- 查询对象、表达式对象、元数据对象、Codec/Decoder 对象的地址或 `identityHashCode`。
- `Connection`、`PreparedStatement`、`ResultSet`、Session、事务或单次 `ExecutionContext`。
- query tag、timeout、fetch size、max rows 等不改变 SQL/Binder/Decoder 结构的执行选项。

共享缓存值只保存不可变 `CompiledQueryPlan`，其中可以包含 SQL 模板、最终参数布局/Binder、RowDecoder、执行提示和
安全诊断；不得保存本次 `StatementAst`、`QueryParameters`、参数 List、查询表、mapping/selection/order 图、Connection
或会话。命中后必须将当前调用的 AST 与参数附着到共享计划，不能把首次编译查询对象的 AST 身份返回给后续调用者。

框架生成的 reader/Decoder 只保留必要 Codec、JDBC 下标、稳定参数合同和预计算诊断值。`ProjectionMapping.generated(...)`
虽然是 public 基础设施入口，其实际 DecoderFactory 合同仍必须进入结果身份；factory 在 mapping 生命周期内必须不可变，
实例状态检测只发生在 mapping 建立阶段，不得进入逐行或命中热路径。

租户/权限规则的**结构版本**可以进入结构上下文，实际租户、用户或权限数据值不得进入。若策略直接产生不同 AST，最终
`ResolvedStructureKey` 还必须反映改写结果。

### 6. 不可变性、hash 与碰撞

`QueryPlanKey` 对列表和 Map 做防御性复制，结构上下文按名称规范化顺序保存。构造时一次性计算组合 hash，命中路径的
`hashCode()` 只返回缓存值。

hash 只用于定位桶，绝不代表完整身份。即使两个键具有相同 hash，也必须比较全部结构、结果形状、变体、参数、方言和
上下文后才能命中。不得用截断 fingerprint、SQL hash 或 `canonicalForm().hashCode()` 代替完整 `equals`。

### 7. ClassLoader 生命周期

键只保存字符串、枚举、数字以及同样只含值数据的 `ResolvedStructureKey`，不直接保存 `Class`、ClassLoader、Codec 或
元数据对象。运行时类型令牌由静态 `ClassValue` 按真实 `Class` 身份分配；值只含单调进程内编号和诊断用二进制类名，
不反向持有 `Class`。因此同名类型来自不同 ClassLoader 时令牌不同，类卸载时对应 `ClassValue` 条目又可随类回收。

一个 registry/catalog 即使登记来自多个 ClassLoader 的模型也不会因同名二进制类而串用结果、依赖或 Codec。缓存值中的
Decoder/Codec 可以合法持有 DTO 或插件 ClassLoader；expire-after-access 是惰性维护，统计读取也不清理，因此二者都不是
卸载屏障。应用卸载或替换结果类型/插件 ClassLoader 前必须先停止并等待受影响查询执行结束，再调用公共 clear 入口；
只有当所有受影响实体已知且 loader 不仅由结果类型持有时，才可使用精确实体失效。clear 后的弱引用合同测试必须证明
loader、结果 Class 和 Decoder 可回收。
运行时令牌只用于进程内缓存身份，不得持久化或作为跨进程协议。

### 8. 并发 miss 与编译失败

L1 命中使用并发 Map 的只读查找，不获取覆盖全缓存的锁，也不为了精确 LRU 在每次命中修改一条全局链表。

同一键的并发 miss 采用 per-key single-flight：第一个调用者成为编译者，其他调用者等待同一不可变结果。编译在并发 Map
内部锁之外执行，避免慢 Renderer 长时间占有 Map 锁。启用 L1 时，同一线程在本缓存的编译 supplier 内再次发生 miss
必须立即失败，包括不同 key，以及 clear/实体失效已经移除外层 flight 的情况；嵌套读取已有缓存条目仍然允许。
实际活跃的编译 owner 独立于可加入的 flight 记录，在 supplier 的 finally 中清除，不随失效提前删除，也不提前释放 permit。
不同线程可以并行编译不同键，但同时注册的不同 key flight/编译工作受容量对应的 admission 上界约束；等待 admission 的
请求尚未写入 `inFlight`。

- 每个观察到“尚无可用缓存条目”的请求计一次 miss；计数发生在 owner/waiter 注册成功或交接窗口复用已成立之后，
  等待 in-flight 结果不计 hit。测试可以把该计数作为注册完成的观察点，不能在注册前提前递增。
- 成功编译只发布一个条目，等待者收到同一计划。
- flight future 以正常完成的 `FlightOutcome` 承载计划或失败；编译失败不进入缓存，owner 和 waiter 重抛同一个
  `RuntimeException`/`Error` 实例，不无条件解开任意 `CompletionException`。in-flight 占位必须移除，后续调用可以重试。
- 编译失败不能增加 eviction 或 invalidation。
- admission 等待同时观察目标条目是否已经发布；发现条目后回到外层查找，统一检查过期和依赖，复用时计一次 miss。
  读取已发布计划不需要编译 permit，不能继续等待占据名额的无关 key；也不能在 admission 锁内调用 ticker 或编译器。

clear/实体失效与 in-flight 编译竞态通过缓存 generation 处理：编译者记录开始时的全局 generation 和相关实体 generation；
发布前任一 generation 已变化，则本次结果可以返回给已经开始的调用者，但不得重新插入已被清理/失效的 L1。不能让
clear 后完成的旧编译悄悄恢复条目。失效只移除旧代 `inFlight` 注册，不提前归还其 admission；旧 owner 到实际完成或
失败时才释放 permit，使新旧代可能重叠但真实并发编译总量仍不超过 admission 上限。

### 9. 容量、过期和驱逐

启用的 L1 必须有严格正数最大容量和正数 `expireAfterAccess`；`maximumSize == 0` 是关闭整个 L1 的正式配置，
负数仍属非法：

- 一次插入的发布线性化点结束时 `size` 不得超过最大容量；并发插入可以由窄范围维护锁串行化发布，但不得让命中读取
  获取该锁。in-flight 编译占位不属于已缓存计划条目，也不计入 `size`，但正常运行时同时注册的不同 key flight 数同样
  受最大容量 admission 约束，不能形成另一张无界 Map。
- 容量为零时不查找、不创建 in-flight、不发布条目，直接走 L2，并保留查询对象自己的 L0；该路径不产生任何 L1
  hit/miss/eviction/invalidation 活动计数。
- 过期基于单调 ticker，不读取墙上时钟。
- 成功插入和成功命中更新条目的最近访问时间；miss、失败和旁路不更新。
- 容量和过期驱逐可以使用近似并发 recency，不要求精确全局 LRU；命中路径不得使用覆盖整个缓存的
  `synchronized LinkedHashMap`。
- 容量/过期维护可以使用窄范围锁或分段状态，但不得在该锁内编译、渲染或等待 JDBC。

被容量或过期自动移除的条目增加 evictionCount。替换同一键的等价条目不是驱逐；single-flight 正常情况下不会发生这种
替换。

### 10. clear、实体失效与依赖

`clear` 删除调用线性化点之前可见的全部 L1 条目并推进全局 generation。按实体失效删除所有依赖该实体的计划并推进该
实体 generation，依赖范围必须覆盖根来源、Join、子查询和派生表内部来源。

实体依赖由解析/编译结果显式收集，不通过字符串搜索 SQL 或 `ResolvedStructureKey.canonicalForm()` 推断。完整来源位置与
loader-aware 实体值身份的映射按查询块路径/来源 ordinal 排序后进入 `QueryPlanKey`；去重的规范 `EntityMeta` 集合另随
flight/缓存条目保存以支持 generation 与精确失效。
后者可以引用 catalog 已拥有的元数据，因为其生命周期不超过 catalog；它不是普通值或单次资源。

`clear` 和按实体失效不重置累计 hit/miss/eviction 统计。每个实际删除的条目使 invalidationCount 增加一；按实体失效的
公共返回值等于本次实际删除条目数。没有匹配项时返回零且不增加计数。

### 11. 统计口径

`QueryPlanCacheStatistics` 保持现有公共字段：

- hitCount：L1 找到一个未过期、可直接使用的条目。
- missCount：请求尝试 L1，但在线性化查找点没有可用条目，并已注册/加入 flight 或在交接窗口复用刚发布计划；加入同键
  in-flight 的请求仍是 miss，等待不同 key admission 尚未完成时不提前计数。
- evictionCount：容量或访问过期自动删除的条目数。
- invalidationCount：clear 或实体失效实际删除的条目数。
- size：生成快照时 L1 中未被删除的条目数。
- maximumSize：配置容量；零表示 L1 已关闭，此时 `size` 和全部活动计数必须为零。

统计为 catalog 生命周期内累计计数。显式旁路不计 hit 或 miss；当前公共结构没有 bypassCount，0.2.7 的诊断扩展可以另行
增加内部/结构化旁路原因，但不能篡改既有计数含义。读取统计不得触发过期维护、编译或事件分配。

### 12. 旁路

出现以下任一情况时必须明确 bypass L1 并走 L2，不能构造一个缺少维度的弱键：

- 结果映射没有稳定 ID。
- Binder/Codec 没有稳定结构来源。
- 方言无法提供稳定能力版本。
- 动态表、Schema、策略或自定义扩展会影响计划，但没有稳定结构签名。
- 编译结果捕获了不能提升到 catalog 生命周期的状态。

旁路不影响语义校验、方言校验或资源关闭。T06 的 `QueryPlanKeyAssembler` 用统一结果表达“可缓存键”或“带原因旁路”，不得在
各终止路径用 `null`、异常或弱键各自决定；旁路原因必须可由测试观察，但本版本不新增公共监听 SPI。

### 13. 分步落地

- T03：新增本 ADR、内部 `QueryPlanKey`、注册表约束且 ClassLoader 感知的结果/参数身份工厂、共享
  `QueryPaginationShape`、`maximumSize == 0` 禁用合同，以及键相等/隔离/碰撞/防御复制测试。
- T04：为 `Dialect` 提供 `capabilityVersion()` 和显式稳定身份 opt-in，把完整能力集合与额外行为版本接入可选
  `DialectIdentity`；不能安全标识的方言保留 L0/L2 并由 T06 旁路 L1；同时完成最大参数数合同。
- T05：按本 ADR 实现 catalog 所有的有界 L1、single-flight、有界 admission、统一 outcome、generation、完整依赖键、
  值安全计划载体、统计、clear、依赖失效和过期/驱逐，并补真实计划并发及 ClassLoader/对象可达性合同测试。
- T06：完成统一键组装器，返回“可缓存键/带原因旁路”结果；接入 L0/L1/L2，并覆盖所有不能稳定标识的形状。
- T07/T08：复用不可变 compilation context、收敛重复 walk 和参数复制，不改变本 ADR 的身份与值安全边界。

## 后果

- 等价查询可以跨对象共享计划，同时参数值仍由每次执行独立提供。
- 键比“结构 hash + 参数数量”更大，但避免了 Decoder、Binder、分页和方言串用；组合 hash 只计算一次。
- 共享缓存不会跨 catalog 复用。两个 catalog 即使键相等也各自编译，这是换取 runtime registry、Codec 和 ClassLoader
  边界清晰的有意选择。
- 不能稳定标识的第三方扩展先旁路，命中率让位于正确性。
- T03/T04 建立键、禁用配置和方言身份合同；T05 容器及审查修复、T06 普通查询统一键组装与 L0/L1/L2 接线均已
  通过 CI。fresh 查询在共享命中前仍需结构解析与完整校验；T07 已用不可变 compilation context 让内置方言校验与
  Renderer 复用同一查询块分析，源码及合同测试待 CI。

## 兼容性

`QueryPlanKey` 及其组成类型是 `io.skis.query` 包内实现，不新增公共 API，也不改变现有查询 DSL、生成 ABI、
`QueryPlanCacheStatistics` 或清理入口签名。公共容量配置从“必须大于零”扩展为“零表示关闭、正数表示启用”，已有正数
配置语义不变。

T04 已通过兼容默认方法为 `Dialect` 增加能力版本与稳定身份声明。旧第三方方言默认不声明稳定身份，因此不会因继承一个
派生整数而被静默纳入共享 L1；它们仍可通过 L0/L2 正确执行。显式 opt-in 的方言身份保存完整能力集合和额外行为版本，
避免把 32 位派生值当作集合的完整等价依据。T05 容器主体及审查修复源码已把现有统计、清理与实体失效入口背后的
占位行为实现为真实语义并通过 CI；T06 已让普通查询路径使用它且接线验证通过。两步都不删除既有公共方法。

T07 为 `Dialect` 增加携带 `QueryBlockAnalysis` 的默认校验重载及 `supportsResolvedQueryValidation()` opt-in，默认仍调用
既有 `validate(StatementAst)`；为 `SqlRenderer` 增加的 `renderValidated` 默认仍调用 `render`。因此旧第三方实现不会
静默跳过自定义校验或渲染逻辑，只有 PostgreSQL/H2 等显式接线的实现走单次分析路径。T07 review 后，COUNT 构造只保留
局部不变量校验，完整 COUNT 直接进入 query-block 分析，不再产生并丢弃构造期完整分析或分析专用临时 SELECT。
当前 resolved 重载仍接收可任意配对的 `StatementAst + QueryBlockAnalysis`；二者 provenance、可见性与兼容承诺必须在
0.2.7 公共 SPI 冻结前决定，不能用 Renderer 内重新分析恢复重复阶段。公共 SPI 的最终分层与冻结仍归 0.2.7。

## 性能影响

共享命中路径最终只执行 L0 引用读取或 L1 并发 Map 查找与完整键比较，不重新渲染 SQL。键的组合 hash 在构造时计算一次；
键不生成完整 SQL，也不在每次 `hashCode()` 构造 canonical 字符串。

`ResolvedStructureKey` 的首次产生仍需要解析最终结构。T07 将其与语义/方言 compilation context 复用；本 ADR 不通过删除
校验换取表面命中。per-key single-flight 避免同形冷查询并发重复编译，近似 recency 避免精确 LRU 的全局命中锁。

这些是结构性预期，不构成吞吐、延迟或分配量结论。整个 0.2.x 开发期继续禁止 benchmark；最终性能证据仅由独立的
0.3.0 发布前任务建立。

## 安全影响

普通参数、租户 ID、用户 ID 和 keyset 非空锚点不进入键、统计、异常或 `toString()`。结构上下文只能保存版本或不可逆的
结构签名，不保存原始安全上下文。共享计划继续使用 PreparedStatement 参数绑定，不因命中而拼接用户值或跳过语义、
方言及参数形状验证。

缓存键的诊断文本可以包含表、列、类型和结构路径，不能包含普通值。失败编译不缓存，防止短暂的权限/配置失败被当作长期
计划结果。

## 回滚方案

若共享缓存出现正确性或生命周期问题，执行器可以把 `maximumSize` 配置为零整体关闭 L1，或对特定形状旁路，继续使用
L0 查询对象局部复用、实体 Fast Path 和 L2 完整编译。回滚不得重新引入静态缓存、值入键、对象地址键或跳过校验。

部署期可通过现有 clear 入口释放所有 L1 条目；卸载结果类型或插件 ClassLoader 前必须先停稳受影响查询再 clear。
配置/策略更新使用 clear 或精确实体失效。若某个新维度无法安全加入旧键，
先旁路该能力并新增键版本/ADR 修订，再恢复共享，不允许用清空缓存掩盖永久身份缺失。
