# ADR-0008：最终查询参数载体与 Binder 调用期所有权

- 状态：Implemented（已实现）
- 日期：2026-10-10
- 影响版本：0.2.6-SNAPSHOT
- 决策范围：最终语句值载体、分页 provenance、编译交接校验及 `ParameterBinder` 参数所有权
- 落地状态：T08 实现与 review R1/R2 修复已通过 CI（PR #57 已合入 `main`）；本地开发过程未编译运行

## 背景

ADR-0005 已固定查询值的捕获时快照和查询对象局部分析复用：内置可变值在进入 `QueryParameters` 时快照，普通值不进入
AST、结构指纹或编译计划。ADR-0007 又固定共享计划只能保存值无关结构，L0/L1 命中后必须附着本次调用的参数。

T08 之前，普通参数先形成中间 `List`，编译器再同步维护一份值列表并在追加 offset/keyset 时重包整张参数表。该路径既有
可删除的复制，也把结构布局和值组装混在 `InputsBuilder` 中。首轮 T08 把它改为普通数组与分页尾段组成的
`QueryArguments`，但载体只保存尾段值数量，无法证明同长度 keyset 尾段与正在编译的 null-marker 位置来自同一分页结构。
同时，公共 `ParameterBinder` 的参数留存边界需要明确，避免共享计划把单次调用值放进跨执行状态。

## 问题

需要同时确定：

1. 普通值、count 裁剪值和分页值如何形成一条最终 SQL 的稠密逻辑槽，而不重复快照或复制普通参数整表。
2. 编译器如何在进入 resolver、计划缓存和 JDBC 之前证明值载体属于当前分页结构，特别是识别同长度但 keyset
   null-marker 位置不同的错配。
3. 共享 `CompiledQueryPlan` 中的 `ParameterBinder` 是否可以留存参数或使用跨调用 scratch storage。
4. 如何保持普通参数值不进入结构身份，并且不扩大公共查询参数 API 或生成 ABI。

## 候选方案

### 方案一：保留单一复制 List，由编译器同时构造结构和值

交接简单，但普通参数会在最终语句组装时再次复制；结构编译和值生命周期继续耦合，count、content 和分页路径难以独立证明
只携带最终保留的槽位。

### 方案二：使用分段载体，只校验总数和分段长度

普通数组可以被分页尾段复用，但长度不能表达 provenance。两个 keyset 结构可能拥有相同的非 null anchor 数量，却把值放在
不同排序列位置；仅比较总数和 ordinary 数量会静默接受这种错配。

### 方案三：分段载体携带值无关分页 shape，并在 resolver 前精确校验

载体保存普通数组、分页尾段以及 `QueryPaginationShape`。shape 只包含 NONE/LIMIT/OFFSET 或 KEYSET 的 null-marker
序列，不包含 limit、offset 或非 null anchor 值。编译器独立构造最终结构后，核对总数、ordinary 数量和完整 shape；
Binder 只在本次调用中同步消费载体。

## 决策

采用方案三。

### 1. 最终槽位载体

- `QueryParameters.valuesFor(...)` 把已经捕获的普通值直接投影到一个新数组；`QueryArguments` 接管该数组，不再建立中间
  值 List，也不再次快照元素。
- limit、offset 和 keyset 的非 null anchor 保存为独立尾段；追加分页只引用原普通数组，不复制普通参数整表。
- count 按最终保留的参数引用独立投影；引用及顺序完全一致时可以复用无分页基础载体。
- `QueryArguments` 继续实现只读 `List` 供 Binder 按逻辑 ordinal 读取，但不提供仅供测试使用的 `empty()` 或 `values()`
  便利入口。测试通过真实 `QueryParameters` 投影或只读 List 合同观察载体。

### 2. 分页 provenance 与失败关闭

- 每个载体保存一个值无关 `QueryPaginationShape`。KEYSET shape 精确保留每个 anchor 的 null-marker；普通值、limit 和
  offset 不进入 shape。
- 载体构造时核对分页尾段长度是否与 shape 可推导的值数量一致。
- `QueryPlanCompiler` 在调用 `QueryPlanResolver` 前核对最终参数总数、ordinary 数量以及载体 shape 与当前
  `QueryPagination` 产生的 shape 完全相等。count 必须匹配 NONE。
- 同 shape 的不同普通值仍可复用同一计划；同长度但 marker 位置不同的载体必须在 JDBC 前以不包含参数值的确定诊断失败。
- 分页尾段数量不再作为独立校验，因为总数与 ordinary 数量已经能算术推导它；shape 校验承担真实的 provenance 证明。

### 3. Binder 所有权

- `ParameterBinder` 的参数是 invocation-scoped 输入。Binder 可以在调用期间同步读取，但不得在返回后留存参数对象，
  不得把它放入共享 `CompiledQueryPlan` 或跨调用 scratch storage。
- 当前不引入共享或可复用对象缓冲。未来若有测量证据支持临时缓冲，只能由单次调用或明确非线程安全的 session 所有，
  并在成功或异常退出时释放其中的对象引用。
- `JdbcExecutor.prepare(...)` 仍在返回 cursor/resource 前同步完成绑定；资源对象不保存 `QueryArguments`。

### 4. 计划和值隔离

`QueryPaginationShape` 可以进入 L0/L1 的结构身份和本次载体 provenance；实际 limit、offset、非 null keyset anchor、普通条件值
以及参数容器对象均不得进入缓存键或缓存值。缓存命中后继续为每次调用附着当前 AST 和当前参数载体。

## 后果

- content、ordered、count 和 Fast Path 保持一条最终语句一份稠密值载体，普通值只在捕获边界快照。
- keyset 结构与值载体的同长度错配不再依赖调用者隐含配对，内部交接改为失败关闭。
- 载体多保存一份很小的值无关 shape 引用；NONE/LIMIT/OFFSET 使用共享常量，KEYSET marker 已是计划身份所需结构。
- 测试不再反向塑造生产载体的构造或读取 API。

## 兼容性

公共查询 API、`ParameterBinder` 方法签名和生成 ABI 不变。`ParameterBinder` 只补充所有权与线程生命周期合同。
`QueryArguments`、`QueryPaginationShape` 和编译器交接均为包内实现；删除的 `empty()`/`values()` 没有生产调用者，也不属于
公共兼容面。

## 性能影响

该设计删除可静态证明无必要的中间 List、编译期第二份值表和分页时普通参数整表复制，同时为 shape 比较增加一次小型值对象
相等性检查。`0.2.x` 开发阶段不执行 benchmark，本 ADR 不据此声明吞吐、延迟或分配量改善；结构变化只由正确性、并发和
调用路径证据验收。

## 安全影响

所有用户值继续只通过 `PreparedStatement` 绑定。分页 shape 只暴露模式和 null-marker，不包含普通参数值；错配异常不得
打印 anchor、limit、offset 或条件值。调用期所有权还防止共享计划、缓存和资源对象意外延长敏感参数的生命周期。

## 回滚方案

若分段载体出现正确性问题，可以退回每条最终语句构造一份不可变平面值容器，但必须保留捕获时快照、完整分页 shape 校验、
普通值不入计划以及 Binder 不留存调用参数的合同。不得只恢复长度校验，也不得把值重新放入 AST、缓存键或共享计划。
