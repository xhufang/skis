# 写入阶段、条件写入与 RETURNING

> 适用版本：0.2.10-SNAPSHOT。T03 源码及验收测试已补，待本次变更的 Java 21/25、nullness、SQL golden、PG/H2、消费者和工程门禁验证。
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
