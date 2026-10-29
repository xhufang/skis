# 写入阶段、条件写入与 RETURNING

> 适用版本：0.2.10-SNAPSHOT。T03/T04 源码及验收测试已补，待本次变更的 Java 21/25、nullness、SQL golden、PG/H2、消费者和工程门禁验证。
> T02 主键基线 1899270 已通过 CI；该证据不证明新的 T03 入口已经通过。

实体插入只有 `insertInto(table)`。构建请求、提供数据和选择返回形状均不获取连接、不发号、不执行 SQL；`write()` 或 `write(options)` 每次执行一次。

```java
int rows = executor.insertInto(table).value(entity).write();
Written<Order> full = executor.insertInto(table).value(entity).returning().write();
Written<OrderView> projected = executor.insertInto(table).value(entity)
    .returning(OrderViewPro.of(table.id(), table.name())).write();
Written<OrderReceipt> special = executor.insertInto(table).value(entity)
    .returning(OrderReceiptPro.all()).write();
```

## 冲突插入与 MERGE

PostgreSQL 冲突插入扩展同一个 insertInto 入口，要求显式物理冲突列。下列新增 API 已有源码，仍待当前 CI 验证。

```java
Written<Order> changed = executor.insertInto(table)
    .values(row -> row
        .value(table.id(), 1L)
        .value(table.name(), "Alice"))
    .onConflict(table.id())
    .doUpdate()
    .setExpression(table.name(), Sql.excluded(table.name()))
    .returning().write();

int ignored = executor.insertInto(table).value(entity)
    .onConflict(table.id()).doNothing().write();
```

DO NOTHING 或更新 WHERE 过滤可返回 0/空 Written；不报告插入/更新分类。EXCLUDED 只在所属目标的冲突更新中可见。
版本实体的更新自动推进数据库版本；expectedVersion(Number) 表达严格比较意图，显式值优先于源实体版本。
插入初始值独立处理，记录不存在仍可插入；无可用预期值时只推进，忽略分支不推进。

严格分类限制为单列主键唯一匹配、插入分支完整且无额外业务过滤的受控路径，零行抛 OptimisticLockException。
映射必须对应真实主键和普通物理表，schema 不得有抑制写入行的触发器/规则；框架不做隐藏内省来判断这些前提。
不满足结构证明的请求在 JDBC 前拒绝。普通手写版本谓词只保留 SQL 的实际零行，不自动报告版本失败。

H2 使用独立的 MERGE 语句。单行来源不保证只匹配一个目标，因此 USING 返回不可修改的列表载体：

```java
var id = Sql.output(table.id(), "id");
var name = Sql.output(table.name(), "name");
var source = Sql.values(row -> row.value(id, 1L).value(name, "Alice")).as("incoming");

Written<List<Order>> changed = executor.mergeInto(table)
    .using(source).on(table.id().eq(source.column(id)))
    .whenMatched().thenUpdate()
        .setExpression(table.name(), source.column(name))
    .whenNotMatched().thenInsert(row -> row
        .value(table.id(), source.column(id))
        .value(table.name(), source.column(name)))
    .returning().write();
```

匹配条件与更新分支条件分别由 on、whenMatched().and(...) 表达；框架版本比较放在 WHEN MATCHED AND，
不会加入 ON。来源版本用 expectedVersionExpression(`NonNullSelectable<? extends Number>`) 明确传入，必须类型一致且非空。
仅声明 whenNotMatched 分支时，匹配行保持不变。更新列和插入列可分别选择；不开放多行来源、DELETE 分支或任意查询来源。

无版本实体可使用 KEY 简写，返回 int 或单行 `Written<R>`：

```java
Written<Order> row = executor.mergeInto(table)
    .key(table.id()).values(row -> row.value(table.id(), 1L).value(table.name(), "Alice")).returning().write();
```

KEY 更新提供的可赋值列，不支持版本实体，也不能用业务键匹配并覆盖不同候选主键。
需要不同插入/更新列集合时使用 USING。PG 与 H2 的语句不自动转换，并发保证分别验收，框架不重试 H2 并发失败。
完整 H2 返回通过 FINAL TABLE 获得，没有额外 SELECT 往返。

显式 INSERT 使用 values(Consumer<ValueRow>)，来源使用 Sql.values(Consumer<ValuesRow>)，MERGE 插入分支使用
thenInsert(Consumer<ExpressionRow>)；每个 row.value 分别保留列/输出句柄与值/表达式的类型和空性检查，不再限制为 8 列。
收集器只在回调期间有效，结束或抛异常后不能追加；空行、重复列/输出和跨目标列均在 JDBC 前拒绝。
回调仅在构建时调用一次，普通值在各次 row.value 调用时快照，结束时冻结列和值；不为每列创建包装对象或复制前缀列表。
实体属性仍在 write 时由生成式 Binder 读取，单语句参数预算保持。显式值源须提供应用侧主键，IDENTITY 须省略主键；
UUID/雪花自动生成与实体回填通过 value(entity) 使用。显式值源没有原实体可供回填。
显式 UUID 字符串仍须为规范的小写 UUID，雪花主键仍须大于 0。MERGE USING 的这两类插入主键只能直接使用
捕获参数或所属 VALUES 来源列；无法在执行前校验的计算主键表达式拒绝。ASSIGNED 主键表达式不受此项限制。
生成策略实体的冲突写入只回填实际返回的主键；无显式 returning 的 Bean 计数请求也只按需读取内部主键列，
不创建 Written 或整行 Decoder。生成键 record 仍需显式 returning。写入、解码或资源关闭失败不提前回填。

完整源码/测试与未运行的证据责任见本地任务 0.2.10 §16；这些源码变更不代表真实驱动或性能数字已经通过验证。

示例的 OrderView 和 OrderReceipt 分别由可复用/专用投影声明生成 Pro。返回列只能来自目标表；不接受裸列、DTO Class、任意 Decoder、聚合或子查询。
完整实体明确读取全部持久化列，使用实体 Decoder；非持久字段使用该 Decoder 的缺省值。返回实体不承诺与输入 Bean 同一实例。
计数路径不创建 Written 或整行 Decoder。四类主键和回填规则见[主键生成](id-generation.md)。

```java
Written<List<OrderView>> changed = executor.update(table)
    .set(table.name(), "changed")
    .setExpression(table.note(), table.name())
    .where(table.id().in(ids))
    .and(table.deletedAt().isNull())
    .returning(OrderViewPro.of(table.id(), table.name()))
    .write();

int removed = executor.deleteFrom(table).where(table.id().in(ids)).write();
int all = executor.deleteFrom(table).allowAllRows().write();
```

UPDATE 至少先 SET 一列才能执行。赋值目标必须是目标表的可更新普通列，禁止重复 SET、主键和版本列；值/表达式遵守 Java/SQL 类型和有效 nullability。
可空列可显式 SET NULL；非空列的 NULL 或可空表达式、外部表列和不兼容类型在 JDBC 前拒绝。
条件写入复用标准表达式的必要类型转换，纯参数 COALESCE、CASE、运算及谓词不依赖驱动猜测类型；计数和 RETURNING 使用相同规则。
赋值或谓词在 AST 构造阶段被拒绝时，返回带 SKISM012 的 MutationException，保留原始校验异常为 cause。
`where` 只能设置一次，`and/or` 要求已有条件，按调用顺序左结合；括号由谓词自身的 and/or/not 表达。
没有 WHERE 默认抛 UnsafeMutationException，只有显式 `allowAllRows()` 解除；空 IN 保持恒假，不代表未提供 WHERE。

条件更新统一推进版本，不自动附加每行预期版本检查，零命中返回 0/空 Written。`updateById` 原有预期版本零行冲突规则保持。
UPDATE 返回修改后的行，DELETE 返回删除前的行；两者返回 `Written<List<E>>` 或投影列表，不根据 WHERE 猜测单行。
命中列表不可修改、元素非 null，不承诺与 IN 参数逐项对应。零命中时 Written 为空，`orElse(List.of())` 可取得空列表。

`Written` 仅保存精确计数和已完成的可选结果，不表示事务已提交。get/orElse/回调、Optional、map/flatMap 和 stream 均只访问内存；stream 对列表载体也只产生零或一个元素。
get/orElseThrow 在无行时抛 NoSuchElementException；用户回调异常原样传播。toString 固定脱敏。

PG 使用原生 RETURNING；H2 由受控 lowering 选择 FINAL TABLE（INSERT/UPDATE）或 OLD TABLE（DELETE），同一语句不再请求 getGeneratedKeys。
INSERT RETURNING 直接保存单行解码结果，不创建行列表；必须恰好一行，缺行、探测到第二行、列数/解码不符均失败。为了精确计数，RETURNING 拒绝有效 maxRows 大于 0；可通过单次 maxRows(0) 显式覆盖有界默认值。
执行、解码或关闭失败均不返回成功 Written、不提前回填；资源按 ResultSet → Statement → 本次拥有的连接释放，保留主异常及 suppressed。
不隐式查询、重试或接管外部事务；回滚后已回填键和已签发 Written 保持原内存值。

条件 IN 即使包含大量值也只执行一项操作。最终参数布局超过方言上限时在 JDBC 前失败；unknown 使用 1024 安全上限，不自动拆写。
`insertInto(table).values(collection)` 快照集合结构且拒绝 null 元素；空集合 write 返回 0，空集合 returning 返回空 Written，均无 JDBC。
非空集合执行由 T05 交付，当前在 JDBC 前明确拒绝；不通过逐行循环临时模拟。T03 不执行 benchmark。

动态计划属于 executor 的 catalog，Session 共享；容量 512，提供 `mutationPlanCacheStatistics()` 和 `clearMutationPlanCache()`。
编译完成监听在计划发布并释放编译锁、维护锁后通知；失败通知同样在锁外执行，缓存命中不重复通知。
结果身份包含有序列、规范 Codec 来源、生成 Decoder 合同及 ClassLoader 身份，调用值不入共享缓存；命中复用准备好的结构、固定 INSERT AST 和查找键。
共享键只保留首次准备时生成的扁平结构值描述，不持有调用侧 AST、临时表或完整列对象图；缓存条目存活期间也允许这些调用对象回收。
类型/插件卸载前应停止相关执行并显式清空缓存；仍被应用持有的请求或结果继续由应用负责其生命周期。
