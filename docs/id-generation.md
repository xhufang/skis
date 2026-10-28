# 主键生成与插入结果

> 适用版本：`0.2.10-SNAPSHOT`，生成模型 ABI 9。T02 基线 1899270 已通过 CI/PG/H2/消费者验收；T03 唯一插入入口和 RETURNING 已实现，当前变更待验证。
> 本地未编译运行；历史基线不证明新入口通过。完整用法见[写入阶段与 RETURNING](mutation-returning.md)。

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
Written<Ticket> inserted = executor.insertInto(TicketTable.ticketTable)
    .value(new Ticket(null, "new")).returning().write();
java.util.UUID id = inserted.get().id();
```

`Written<E>` 提供 affectedRows 和 Optional 风格结果访问；访问始终只读内存。单条 INSERT 要求恰好一行。
生成 accessor 原有 JSpecify 可空性保持，运行成功不会改变其 Java 签名。

完整 returning 读取数据库实际持久化列，包括 default/trigger/初始化版本；record 的非持久 component 使用 Decoder 缺省值。
返回 Bean 不承诺同一实例；输入 Bean 只在成功后回填需要变更的主键，不自动回写其他返回字段。
已有 UUID/雪花键与返回键相同时不调用 setter，不同时成功后回填数据库实际键，最多一次。
ASSIGNED 输入键不执行生成键回填；请求的返回实体/投影仍反映数据库返回值。

不配置 returning 的 write 返回 int。ASSIGNED 计数不创建 InsertArguments、Written 或完整实体 Decoder；
Bean 支持四策略计数，generated record 计数在 JDBC 前拒绝，即使已经提供 UUID/雪花值。
显式返回 record 投影时不创建实体副本；需要键时应在投影选择中包含键。Bean 投影未选键时追加内部隐藏键，不改变 Pro 字段顺序。
write(options) 保持 executor/Session 默认值、单次覆盖和事务时限；insertInto/value/values/returning 本身不执行。

## 3. 生成键与资源时序

IDENTITY 要求方言至少支持 JDBC_GENERATED_KEYS 或 MUTATION_RETURNING；两者都没有时装配失败。
计数路径独立要求 JDBC_GENERATED_KEYS；显式 returning 独立要求返回能力。PG/H2 均已有接线，T03 返回路径待当前变更验证。
框架使用 `prepareStatement(sql, new String[]{physicalIdColumn})` 请求受校验的物理主键列；
驱动得到独立数组，列名不是带 SQL 引号的片段。

生成键必须是单行、单列、非空结果；返回 JDBC 列类型仅接受 TINYINT/SMALLINT/INTEGER/BIGINT，
不把字符、浮点或 decimal 转成整数主键。使用声明 Java 类型的内置 Codec 按下标 1 解码；整数越界不截断。
驱动异常返回 null 的键元数据时，以 SKISM016 拒绝结果并按原有顺序关闭资源。
没有可插入列的自增实体使用统一 AST 的 `INSERT INTO ... DEFAULT VALUES`，需要 `INSERT_DEFAULT_VALUES` 能力。
计数模式不执行额外 SELECT 或自动重试。显式 RETURNING 通过统一 AST/受控方言管线生成，单条语句不再请求 getGeneratedKeys。

UUID/雪花值先保存在本次参数载体中，原实体保持未生成状态。只有执行、键映射、ResultSet/Statement 关闭
和本次拥有的连接释放都成功，才按需调用输入 Bean 的 setter。record 从显式返回 Decoder 取得数据库结果。Session 保留所属事务的连接所有权。

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
`Written`、`InsertArguments` 的默认文本固定脱敏；框架诊断不输出原始主键或实体内容。

APT 的 SKIS042—044 和运行时 SKISM014—018 分别见 [APT 码表](apt-error-codes.md)与[运行时码表](runtime-error-codes.md)。
雪花回拨、序列耗尽和纪元越界的固定原因保留在插入异常及操作完成事件的顶层诊断中，同时附带实体、属性和策略上下文；原始异常仍保留为 cause。
SQLState、vendor code、原始 cause 及 cleanup suppressed 异常沿用 JDBC 分类，不能把所有 JDBC 失败变成“无生成键”。

当前测试源码包含策略/APT/nullness、雪花确定性状态与并发、UUID 并发、生成 Binder 槽位、资源失败、
PG/H2、事务回滚及四个公共消费者职责检查；本次没有本地运行结果。CI 清单已登记普通回归、两条 PG 路径和独立消费者。
`0.2.x` 期间不执行 benchmark，不给出吞吐或分配量结论。
