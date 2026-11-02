# 写入、组合与 RETURNING

> 适用版本：`0.2.10-SNAPSHOT`，生成模型 ABI 9；公开版本仍为 `0.2.0`。
> 当前写入、批量和组合合同已按 PR #101 合入提交 `9a7db594` 的
> [CI](https://github.com/xhufang/skis/actions/runs/41395524566) 与
> [Compliance](https://github.com/xhufang/skis/actions/runs/41395524562) 验收。本地未编译运行。

## 插入与行结果

普通 INSERT 只有 `insertInto(table)`。提供数据、选择结果及构建查询均不获取连接、不发号、不执行 SQL；
`write()` 或 `write(options)` 执行一次。以下片段假设已有生成表 `table`、实体 `entity` 和对应 Pro。

```java
int count = executor.insertInto(table).value(entity).write();
Written<Order> full = executor.insertInto(table).value(entity).returning().write();
Written<OrderView> projected = executor.insertInto(table).value(entity)
    .returning(OrderViewPro.of(table.id(), table.name())).write();
Order inserted = full.one().get();
List<OrderView> rows = projected.rows();
```

`Written<R>` 的 R 是一行的类型。`affectedRows()` 是精确影响行数，`rows()` 返回不可修改的行列表；
`one()` 返回 `SingleRow<R>`，超过一行时抛非唯一结果异常。零行与一行 SQL NULL 不同。
这些访问只读内存，不再执行 SQL；`Written` 不表示事务已提交，也不保证返回 Bean 与输入对象同一实例。

SELECT 与 RETURNING 共用实体、标量、合法表达式、`Row2`/`Row3` 和生成式 Pro 选择。
完整实体读取全部持久化列；非持久字段使用 Decoder 缺省值。两项/三项选择直接返回固定行，宽结果使用 Pro。
返回表达式仍受目标/来源作用域、类型、有效 nullability 和方言能力校验，不能传裸 DTO Class 或任意 Decoder。
计数路径不分配 Written 或整行 Decoder。四类主键、生成 record 的返回要求及成功后回填见[主键生成](id-generation.md)。

PG 使用原生 RETURNING；H2 INSERT/UPDATE 使用 FINAL TABLE，DELETE 使用 OLD TABLE。
单条 SQL 不混用 RETURNING 与 getGeneratedKeys，不另查主键。返回读取保持精确计数，拒绝有效 maxRows 大于 0；
可用本次 `maxRows(0)` 覆盖有界默认值。单实体 INSERT 要求恰好一行，冲突忽略可返回零行。
执行、解码或资源关闭失败均不返回成功结果，也不提前回填；关闭顺序为 ResultSet、Statement、本次拥有的连接。
外部事务稍后回滚不撤回已返回 Written 或恢复已回填键。

## 条件更新与删除

```java
Written<OrderView> changed = executor.update(table)
    .set(table.name(), "changed")
    .set(table.note(), table.name())
    .where(table.id().in(ids))
    .returning(OrderViewPro.of(table.id(), table.name()))
    .write();

int removed = executor.deleteFrom(table).where(table.id().in(ids)).write();
int all = executor.deleteFrom(table).allowAllRows().write();
```

UPDATE 至少先 SET 一列才能执行。`set` 接普通值、同类型表达式或单列查询；
`setNull(column)` 明确写 NULL，`setDefault(column)` 交由数据库求 DEFAULT。
`set(Sql.row(a, b, ...), query)` 提供联合赋值，按位置校验输出；非空目标的查询必须能证明单行非空，
当前采用无分组全局聚合的保守证明，并要求方言具有 ROW_SUBQUERY_ASSIGNMENT 能力。
标量子查询零行保留 SQL NULL，多行由驱动失败，不静默取首行或重试。

目标必须属于可写目标表，拒绝主键、不可更新列、重复 SET、不兼容类型及违反有效空性的赋值。
显式 `update(table)` 只执行写出的 SET/WHERE，不自动追加版本比较、版本推进或审计字段；合法版本列可显式赋值。
实体 `updateById(table).value(entity).write()` 保留元数据版本初始化/推进及适用的预期版本检查。
普通业务 WHERE 的零行结果不自动解释为乐观锁冲突。

`where` 只能设置一次；已有条件后才能 `and/or`，按调用顺序左结合，括号由谓词组合表达。
没有 WHERE 默认抛 UnsafeMutationException，仅显式 `allowAllRows()` 解除；空 IN 仍是合法恒假条件。
UPDATE 返回修改后数据，DELETE 返回删除前数据；零命中时 `rows()` 为空，不猜测与 IN 参数的对应关系。
条件 IN 始终是一条操作，超参数预算时在 JDBC 前拒绝，不自动拆写。

## PostgreSQL 冲突插入

冲突插入扩展同一个 INSERT 入口，冲突目标显式使用物理唯一列。

```java
Written<Order> changed = executor.insertInto(table)
    .values(row -> row.value(table.id(), 1L).value(table.name(), "Alice"))
    .onConflict(table.id()).doUpdate()
    .setExpression(table.name(), Sql.excluded(table.name()))
    .returning().write();

int ignored = executor.insertInto(table).value(entity)
    .onConflict(table.id()).doNothing().write();
```

DO NOTHING 或更新 WHERE 过滤可返回 0/空行；不报告插入/更新分类。
EXCLUDED 只在所属目标的冲突更新中可见。适用的实体/受控 VALUES 输入保留版本规则；
`expectedVersion(Number)` 的显式值优先于源实体版本，插入初始值与更新预期值分开，忽略分支不推进版本。
严格零行分类只接受能够归因的单列主键匹配、完整插入分支且无额外业务过滤的受控形状。
映射必须对应普通物理表及真实主键，schema 不得有抑制写入行的触发器/规则；框架不通过隐藏内省确认这些前提。
不满足结构证明的严格请求在 JDBC 前拒绝。

## H2 MERGE、来源与返回状态

H2 使用独立 `mergeInto` 语句，支持表、查询及单行 VALUES 来源与有序 UPDATE/DELETE/INSERT 动作。
查询用 `as(alias)` 形成来源，`column(selectedExpression)` 引用唯一已选输出；计算列先声明别名。
以下 `sourceTable` 是已生成的来源表，目标和来源列须满足实际 schema。

```java
var source = Sql.select(sourceTable.id(), sourceTable.name()).from(sourceTable).as("incoming");
Written<Order> changed = executor.mergeInto(table)
    .using(source).on(table.id().eq(source.column(sourceTable.id())))
    .whenMatched().thenUpdate()
        .set(table.name(), source.column(sourceTable.name()))
    .whenNotMatched().thenInsert(row -> row
        .value(table.id(), source.column(sourceTable.id()))
        .value(table.name(), source.column(sourceTable.name())))
    .returning().write();
```

匹配条件和分支条件分别用 `on`、`whenMatched().and(...)` 表达；只有 NOT MATCHED 分支时匹配行不改变。
表/查询来源不自动添加版本行为；单行 VALUES 的适用受控路径保留元数据版本规则，
显式版本 SET 可覆盖默认推进，但不能同时要求 expectedVersion。
严格来源版本需同类型且非空，版本比较进入 WHEN MATCHED AND，不能放进 ON 后误触发插入。
重复来源匹配的驱动错误原样分类，不逐行重试。

| MERGE 动作 | 返回 |
| --- | --- |
| 仅 INSERT/UPDATE | FINAL TABLE，插入/更新后数据 |
| 仅 DELETE | OLD TABLE，删除前数据 |
| DELETE 与 INSERT/UPDATE 混合 | 支持计数；returning 在 JDBC 前拒绝 |

无版本实体另可用 `mergeInto(table).key(table.id()).values(row -> ...).write()`。
KEY 不支持版本实体，也不能用业务键匹配后覆盖不同候选主键；需要不同更新/插入列时用 USING。
PG 与 H2 的语句、并发、计数及失败语义分别成立；PG 不开放上述 MERGE，H2 不自动改写 PG 冲突插入。

## 查询来源写入

PG UPDATE FROM / DELETE USING 支持表、派生表及合法 JOIN；H2 按能力拒绝这些形态，可由调用方显式选择 MERGE。
UPDATE 的 JOIN 阶段位于首个 SET 之后：`update(target).from(source).set(...).join(...).on(...).where(...).write()`。
WHERE 的 IN/NOT IN、EXISTS/NOT EXISTS 直接嵌入查询，保留关联与 SQL 三值逻辑；FROM 无匹配时不更新。

```java
int copied = executor.insertInto(archive).columns(archive.id(), archive.name())
    .select(Sql.select(table.id(), table.name()).from(table))
    .write();
```

INSERT SELECT 按位置检查目标列及查询输出，保持一条 SQL；不物化来源、不自动拆批或逐行调用 Java 主键生成器。
IDENTITY 省略主键；其他策略需要合法主键来源。显式输入按用户语句处理版本与默认值，不偷偷填充。
普通值在构建表达式时快照，实体属性在 write 时由生成 Binder 读取。
逐列 row.value 回调只在构建时调用一次，结束后收集器失效，空行、重复列和跨目标列在 JDBC 前拒绝。

## 实体批量

```java
int inserted = executor.insertInto(table).values(entities).write();
int updated = executor.updateById(table).values(entities).write();
int deleted = executor.deleteById(table).values(entities).write();

var returned = executor.insertInto(table).values(entities)
    .batchMode(BatchMode.VALUES).returning(table.id(), table.name()).write();
```

| 传输 | 支持范围 | RETURNING |
| --- | --- | --- |
| 默认 ADD_BATCH | 实体 INSERT、PG 冲突插入、实体 UPDATE/DELETE byId | 非空输入拒绝，不自动切换 |
| 显式 VALUES | 实体 INSERT、PG 冲突插入 | 实际结果行集合，不承诺输入顺序对应 |
| byId VALUES、H2 MERGE/KEY 集合 | 当前拒绝 | 不开放 |

默认每块最多 256 行、1024 参数、估算框架缓冲 8 MiB，可用 BatchOptions 调整；方言最终参数上限同时生效。
内存预算不包含调用方实体、变长值和驱动缓冲。集合结构捕获一次，保持输入顺序，执行期间不得并发修改实体。
空输入返回 0 或零计数/空行 Written，不获取连接。一次终点跨块共用一个连接；语句不跨调用或线程共享。

正常返回要求精确 int 计数。SUCCESS_NO_INFO、缺失计数、失败或溢出由 `BatchWriteException.result()`
提供 BatchResult，分别保留成功、失败、未执行、未知及计数已知性；未知不能猜为成功或未执行。
失败停止后续块，不逐行重试、不自适应切换方式、不隐式开启事务，也不提交/回滚外部事务。
需要整批原子性时由调用方显式使用事务。返回行不按顺序猜测生成键对应，不承诺 IDENTITY 批量键回填。

## 计划与当前边界

动态写入计划由 executor 的 catalog 持有，Session 共享；缓存容量 512，提供
`mutationPlanCacheStatistics()` 与 `clearMutationPlanCache()`。普通值、连接、本次 AST/上下文不留在共享计划中。
结构身份包含来源、返回布局、规范 Codec/Decoder 和 ClassLoader；命中复用阶段，监听在缓存锁外通知。
卸载类型/插件前应停止相关执行并清空缓存。

原生文本 SQL 及表达式/条件片段延后至 `0.3.x`。本版的 UPDATE FROM、DELETE USING 等均为结构化 DSL。
图写入、审计/填充、MySQL/Oracle 正式方言和复合主键 mutation 仍属后续范围。
本版只验证结构、资源与正确性，`0.2.x` 期间不执行 benchmark，不给出性能数字结论。
