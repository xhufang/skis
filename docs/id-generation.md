# 主键生成与插入结果

> 适用版本：`0.2.10-SNAPSHOT`，生成模型 ABI 9。T02 源码与测试已实现，当前提交待 CI、H2/PostgreSQL 真实驱动及消费者验证。
> 本地未编译运行；此前里程碑的成功记录不证明本次新增能力通过。

## 1. 显式选择策略

使用 `@Id(strategy = IdStrategy...)`；默认 `ASSIGNED`。本版写入只支持单主键，不提供 AUTO、
`@GeneratedValue`、运行期注解扫描或自定义主键生成器 SPI。

| 策略 | Java 类型 | 输入为空时 | 输入已有值时 |
| --- | --- | --- | --- |
| `ASSIGNED` | 已有 APT/Codec 支持的单主键类型，允许 primitive | JDBC 前拒绝 | 原样使用，数值 0 也是有效输入 |
| `IDENTITY` | 包装类型 `Long`、`Integer` | INSERT 省略该列，指定物理列读取 JDBC 生成键 | JDBC 前拒绝 |
| `UUID` | `java.util.UUID`、`String` | 绑定前生成一次 UUID v4 | 保留原值；字符串必须为 36 字符小写带连字符规范格式 |
| `SNOWFLAKE` | 包装类型 `Long` | 绑定前通过显式配置的共享生成器分配一次 | 必须大于 0，原样使用 |

UUID 对象的已有值不限制版本。UUID 字符串映射 VARCHAR，不隐式转换为数据库原生 UUID 列；
其列长度须为 0（未指定）或至少 36。IDENTITY/SNOWFLAKE 不接受 primitive，以免把 0 当作缺失哨兵。
生成策略只接受匹配的内置 Codec；元数据和手工装配也执行校验。

`PropertyMeta.javaType()` 描述运行时值类型。手工传入 `long.class`、`int.class` 等 primitive 值类型时，
构造器统一保存对应包装类型，与 APT 产物一致；插入、删除和属性绑定不在每次执行时重复转换。
这一元数据约定不改变 Java 实体声明限制：IDENTITY/SNOWFLAKE 属性仍须能用 null 表达待生成状态。

生成前的 Java 属性可以声明 JSpecify `@Nullable`。数据库主键列仍非空；
`@Column(nullable = true)` 会触发 SKIS008，Java `@Nullable` 不触发该诊断。
ASSIGNED/UUID/SNOWFLAKE 主键必须 insertable；IDENTITY 无论 insertable 声明如何都从 INSERT 列表省略。
只读实体只允许 ASSIGNED。主键不会进入 UPDATE SET。

## 2. 类型安全结果

```java
import io.skis.annotations.Id;
import io.skis.annotations.IdStrategy;
import io.skis.annotations.SkisEntity;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@SkisEntity
public record Ticket(@Id(strategy = IdStrategy.UUID) @Nullable UUID id, String title) {}
```

实体须单独放在 `Ticket.java` 中。使用生成的伴生表：

```java
var executor = Skis.builder(dataSource).dialect(dialect).build();
InsertResult<Ticket> inserted = executor.insertResult(TicketTable.ticketTable, new Ticket(null, "new"));
java.util.UUID id = inserted.entity().id();
```

`InsertResult<E>` 包含 `affectedRows()` 和 `entity()`，要求恰好影响一行。通过实体 getter/accessor 取得真实主键类型。
插入前声明为 `@Nullable` 的 accessor 在 Java 类型系统中仍可空；运行成功不会改变其签名。

Bean 在成功后原地回填并返回同一对象；record 通过生成的 canonical constructor 仅替换主键，
其余 component（包括 transient）保持输入值。已有 UUID/雪花主键不再写 setter，也不复制 record。

`insert(...)` 继续返回 int。ASSIGNED 的计数路径不创建 InsertArguments、InsertResult 或 record 副本。
Bean 的四策略均支持计数入口；使用生成策略的 record 一律要求 `insertResult(...)`，即使调用方已经提供 UUID/雪花值。
两个入口及 executor/Session 都支持 `ExecutionOptions` 重载。

结果只确认本次主键，不刷新数据库 default、trigger 或初始化版本字段。任意列返回属于后续 RETURNING 任务。

## 3. 生成键与资源时序

IDENTITY 依赖方言声明 `JDBC_GENERATED_KEYS`；PG/H2 已声明，第三方缺少能力时在装配失败。
框架使用 `prepareStatement(sql, new String[]{physicalIdColumn})` 请求受校验的物理主键列；
驱动得到独立数组，列名不是带 SQL 引号的片段。

生成键必须是单行、单列、非空结果；返回 JDBC 列类型仅接受 TINYINT/SMALLINT/INTEGER/BIGINT，
不把字符、浮点或 decimal 转成整数主键。使用声明 Java 类型的内置 Codec 按下标 1 解码；整数越界不截断。
驱动异常返回 null 的键元数据时，以 SKISM016 拒绝结果并按原有顺序关闭资源。
没有可插入列的自增实体使用统一 AST 的 `INSERT INTO ... DEFAULT VALUES`，需要 `INSERT_DEFAULT_VALUES` 能力。
框架不执行额外 SELECT、不拼接 RETURNING 获取主键，也不自动重试写入。

UUID/雪花值先保存在本次参数载体中，原实体保持未生成状态。只有执行、键映射、ResultSet/Statement 关闭
和本次拥有的连接释放都成功，才调用 setter 或复制 record。Session 保留所属事务的连接所有权。

| 情况 | 内存与结果语义 |
| --- | --- |
| 输入不合法、生成失败 | 获取连接前失败，不回填 |
| 执行、键读取、解码或资源处理失败 | 不回填，不返回成功；SQL 可能已提交 |
| setter/record 构造失败 | 保留 cause，不补偿 setter，不重试 INSERT；SQL 可能已提交 |
| 外部事务稍后回滚或提交失败 | 不清除已回填主键，也不回收 UUID/雪花编号 |
| 调用方再次插入 | ASSIGNED 用现值；IDENTITY 非空拒绝；UUID/雪花保留现值，由数据库唯一约束处理重复 |

获取键属于同一 SQL 生命周期；回填失败可以使操作失败，但不会产生第二条 SQL 或重复的 SQL 结束事件。
UPSERT 冲突时的候选键与数据库实际主键由 T04 单独处理；当前入口不提供 UPSERT。

## 4. 雪花节点、并发与部署

```java
var ids = SnowflakeIdGenerator.ofNode(17);
var executor = Skis.builder(dataSource).dialect(dialect).snowflakeIdGenerator(ids).build();
```

注册可写 SNOWFLAKE 模型必须配置生成器，即使业务准备总是传入已有 ID。没有该策略时不创建生成器。
executor 与其 Session 共享注入的同一实例；同进程多个 executor 使用同一节点时也必须共享实例。
不使用全局节点注册表，不根据 IP/MAC/pid 自动分配节点。

布局为正 long：41 位毫秒差、10 位节点号、12 位序列；固定 UTC 纪元为 `2026-01-01T00:00:00Z`。
节点范围 0—1023，同毫秒序列范围 0—4095，通过每实例 CAS 状态分配；首个候选为 0 时跳过。

时钟回拨、同毫秒序列耗尽、时间早于纪元或超过 41 位范围立即失败，不睡眠、不等待下一毫秒，不生成逻辑未来时间。
CAS 争用会重新计算原子分配；它不代表重试数据库写入。

部署方须保证同一 ID 域的节点唯一，覆盖进程和 ClassLoader。重启重用节点前，必须确保旧实例已停止，
新实例物理时间严格大于旧实例最后发号时间。本版没有持久高水位或分布式租约，不能检测跨重启回拨；
同节点双实例、恢复旧快照等错误配置不具备唯一性保证。数据库唯一约束仍须保留。

## 5. 生成合同、诊断与验证

APT 生成 `EntityIdAccess` 和 `ParameterBinder<InsertArguments<E>>`，直接调用属性访问器、setter/字段或构造器。
生成策略在装配阶段解析，绑定阶段不重新读主键或生成 ID。普通绑定和 observed 绑定使用相同最终槽位和值。
参数载体、实体和生成器状态不进入 AST 或共享计划；Session 复用预编译计划。

ABI 9 要求实体、投影、生成索引和 runtime 使用一致处理器重新生成；ABI 8 被明确拒绝。
`InsertResult`、`InsertArguments` 的默认文本固定脱敏；框架诊断不输出原始主键或实体内容。

APT 的 SKIS042—044 和运行时 SKISM014—018 分别见 [APT 码表](apt-error-codes.md)与[运行时码表](runtime-error-codes.md)。
雪花回拨、序列耗尽和纪元越界的固定原因保留在插入异常及操作完成事件的顶层诊断中，同时附带实体、属性和策略上下文；原始异常仍保留为 cause。
SQLState、vendor code、原始 cause 及 cleanup suppressed 异常沿用 JDBC 分类，不能把所有 JDBC 失败变成“无生成键”。

当前测试源码包含策略/APT/nullness、雪花确定性状态与并发、UUID 并发、生成 Binder 槽位、资源失败、
PG/H2、事务回滚及四个公共消费者职责检查；本次没有本地运行结果。CI 清单已登记普通回归、两条 PG 路径和独立消费者。
`0.2.x` 期间不执行 benchmark，不给出吞吐或分配量结论。
