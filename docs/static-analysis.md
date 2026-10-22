# JSpecify 静态检查

`static-analysis` 是仓库维护者使用的显式 Maven profile。它对生产源码及 APT 生成源码运行
Error Prone 2.50.0 和 NullAway 0.14.2；普通构建继续使用 javac。检查器不属于发布运行时依赖，
独立消费者不继承 SKIS parent，也不需要安装检查器。

已核实 [CI 39566474356](https://github.com/xhufang/skis/actions/runs/39566474356)：Java 21/25 均已执行
processor 专用检查，`NullnessCompilationTest` 的 8 个测试全部通过；`SkisEntityProcessorTest` 的 70 个测试中
有 1 个嵌套数组负例测试失败。工件确认生成注解完整，但两个数组读取路径被检查器漏检，具体边界与修正见下文。
当前夹具修正仅静态核对，未在本地编译运行，完整门禁仍待新 CI，T02 尚未关闭。

## 配置与环境

CI 使用 Temurin 21 和 25，产物统一为 `--release 21`。Java 21 至少为 21.0.8，
编译显式传入 `-XDaddTypeAnnotationsToSymbol=true`，保证跨模块字节码中的类型注解可读。
检查器版本由根 POM 固定，不使用浮动版本。

CI 执行命令如下；本地是否执行服从工作区约束。

```bash
./mvnw -B -ntp clean verify -Pstatic-analysis
```

NullAway 设为 ERROR，并显式启用 `OnlyNullMarked`、`JSpecifyMode`、`HandleWildcardGenerics`、
`JSpecifyJDKModels` 和 `WarnOnGenericInferenceFailure`。`RequireExplicitNullMarking`、
`JSpecifyUnrecognizedAnnotationLocation` 同样设为 ERROR，保留 Error Prone 默认错误规则。
没有生成类、核心包或模块级的检查排除。

Maven 的生产编译使用 fork javac，所需 exports/opens 只授予编译子进程。内嵌 javac 的权限只授予
该 profile 的测试 JVM；Surefire/Failsafe 的 argLine 保留 `@{argLine}`，供 JaCoCo 合并 agent。
没有修改全局 `.mvn/jvm.config` 或消费者运行参数。APT 模块的 processor path 追加检查工具，
保留 SKIS 实体、索引及投影处理器；processor 自身仍禁用自处理。

## 编译入口与夹具映射

| 原入口 | 保留行为 | 实际检查器通道 |
| --- | --- | --- |
| Maven `default-compile` | 生产源码和适用 APT 一起编译 | profile 加载 Error Prone/NullAway |
| Maven `default-testCompile` | 故意传 null 等普通测试由 javac 编译 | 不加载插件；专用夹具在测试执行时检查 |
| `SkisEntityProcessorTest.process` 的 `getTask` | `-proc:only` 生成与处理诊断 | 新用例先调用原入口，再检查同一批原始、生成及调用方源码 |
| `SkisEntityProcessorTest.compileGenerated` 的 `getTask` | `-proc:none -Xlint:all -Werror` 验证普通可编译性 | `checksGeneratedSourcesAndTheirCallersWithTheRealNullnessChecker` 使用完整检查器编译 |
| `QueryTypeSafetyCompilationTest.compile` | 保留阶段、单列和非空选择器的名义类型正反例 | `NullnessCompilationTest` 的查询终止方法、泛型及通配符矩阵 |
| `MutationTypeSafetyCompilationTest.compile` | 保留实体表/派生关系的名义类型正反例 | `NullnessCompilationTest` 的 mutation、Binder 及回调输入输出矩阵 |
| `VerifyQueryApiBudget.publicTypes` | 仅 `JavacTask.parse` 统计公共类型 | 不加载检查器，不计为空值检查证据 |

测试侧 `NullnessCompiler` 使用完整 `getTask().call()`，显式传入 release、classpath、processor path
和与 Maven 相同的检查参数，禁用 APT 以隔离生成失败。它核对实际加载的检查器 Jar 版本，
逐项记录完整 javac 参数（包括 classpath、processor path 和检查器选项）、附加字节码目录及诊断。
编译前按源文件 URI 保存原始、生成及调用方源码正文，生成文件后续修改和临时目录清理不影响已保存的快照；
javac 异常退出时也追加已收集的诊断与异常栈。所有断言失败都带具体报告路径及诊断，意外编译成功时同样保留这些信息。
工具缺失、版本不匹配或未加载时测试失败。

`NullnessCompilationTest` 覆盖 nullable/non-null 查询、SingleRow、List/Page/Slice、cursor/stream、
Codec、Binder、Decoder、投影读取器、mutation 和事务回调；泛型/通配符正反例与单独编译的字节码 API
另列。生成用例覆盖表、可复用/专用 Pro、Binder/Decoder、nullable 数组和调用方。
生成代码明确声明 `@NullMarked`，同时准确保留可空读取器类型及嵌套泛型/数组注解。
生成表的 nullable 列继续通过 `Selectable<V>` 选择为 `SelectQuery<@Nullable V>`，不改成非空选择器。
实体元数据及类字面量使用无类型注解的类型名；投影源码按外层到内层保留各数组维度的可空性。
普通 javac 的 JDBC 类型夹具覆盖可空 `byte[]`，专用投影夹具覆盖二维数组内层可空、外层可空、两层均可空及泛型内嵌数组。
查询回调正例显式使用计算结果，继续接受 Error Prone 默认返回值检查。

NullAway 0.14.2 的数组读取存在以下已确认漏检，不能把这些用法编译成功当作安全证明：

| 用法 | CI 实测 | 当前合同验证 |
| --- | --- | --- |
| `var matrix = view.nullableMatrixAndRows()` 后判空并解引用 `matrix[0]` | Java 21 的 `nested-generated-caller-matrix-row.txt` 为 `success=true` | 直接访问已判空的 record accessor，再检查内部行解引用，保留 `assertRejected` |
| `view.nestedNullableRows().getFirst()[0]` 解引用内部行 | Java 25 的 `nested-generated-caller-nested-array-row.txt` 为 `success=true` | 正例保留 `List<String[] @Nullable []>`，负例拒绝收窄为 `List<String[][]>`，保留 `assertRejected` |

两份报告的生成源码均保留了各层 `@Nullable`。结合该版本的[数组读取实现](https://github.com/uber/NullAway/blob/v0.14.2/nullaway/src/main/java/com/uber/nullaway/dataflow/AccessPathNullnessPropagation.java)，
这里属于按符号读取数组元素注解时的推断覆盖限制；未修改生成器、关闭检查或把错误用法改为应成功。
嵌套负例按名称排序，并通过 `assertAll` 顺序执行全部用例，避免随机顺序下第一个失败隐藏其他漏检。
新负例及外层数组判空正例仍待 CI 验证；上述漏检保留为工具升级后的复核项。

APT 对 `RuntimeException`、`Error`、`Object` 和 `Column` 这些已知顶层类使用非空的 `Class.getName()`；
这些类的二进制名称与规范名称一致，不改变类型查找或注解识别。
Lombok 注解检测、投影类型注解可见性检查及实体/投影轮次收集改用 `instanceof TypeElement` 模式匹配，
在同一控制流内确认类型并使用非空变量，移除 `isInstance` 过滤后再 `Class.cast` 的流管线。
待处理集合、已完成集合、延迟轮次及生成源码合同保持原有语义，未新增 NullAway 抑制或生产辅助入口。

负例要求 ERROR 诊断包含指定检查器名、源码文件和有效行列位置，不能以普通语法错误或 APT 失败代替。
另对实际生成表注入一个 null 字段缺陷，证明 `@Generated` 源码没有被跳过。

## 生产契约与例外

`ProjectionMapping.ValueReader<V>` 由 `V` 表达结果可空性。生成代码根据构造参数选择 nullable 或
non-null `V`，编译好的非空读取器复用已有 JDBC 边界校验；没有新增逐行反射、参数快照或冗余非空检查。
`TransactionCallback<R>` 和 `inTransaction` 允许 nullable `R`，非空调用方仍不能接收可空结果。
`Skis.resolveClassLoader` 按显式配置、线程上下文、SKIS 定义加载器的顺序选择；定义加载器也可能为 null，
因此在装配期显式检查，全部不可用时抛出 `ASSEMBLY_CONFIGURATION_INVALID`，提示配置 `classLoader` 或 `runtimeModels`。
检查只作用于生成索引发现的装配路径，不新增检查抑制。
JDBC 分页直接保留 `JdbcPageResult<R>`，由 `R` 的实际类型参数表达结果可空性。
游标当前值同样按 `R` 存储，只有未定位状态使用私有非空哨兵；`current()` 的状态检查阻止哨兵外泄，
已定位值直接来自 `RowDecoder<R>`，包括合法的 null 行。哨兵工厂显式返回 `@NonNull R`；类型擦除转换仅用于该哨兵，保留原有 Java unchecked 抑制，
没有新增 NullAway 抑制、逐行分配或额外非空检查。JDBC 分页/游标的编译正反例和 null 行状态转换夹具已补齐，执行证据见下文。

keyset 内部 `OrderedRow<R>` 的用户值直接声明为 `R`，与 `RowDecoder<R>` 一致，不额外扩大为 `@Nullable R`；
单行结果在确认行存在后调用包内 `DefaultSingleRow.<R>decoded(value, nullableResult)`；该工厂接收 JDBC 的 `@Nullable R`，
按已解析选择的 nullability 执行原有非空结果校验，再直接构造 `SingleRow<R>`。无行仍返回共享 no-row 实例，
合法 null 行仍为 present-null，公开 `present(R)` 的入参约束保持。没有新增 NullAway 抑制、强制转换或逐行分配。
查询编译夹具补充 keyset 可空元素负例及 `fetchOne`/`fetchFirst` 的泛型结果传递正例。
单行工厂另有跨字节码的包内调用正例、公开 `present` 拒绝 nullable 值/结果类型误用的负例，以及 null 行与非空选择的运行时夹具。
数组快照用 `getComponentType()` 的非空分支识别数组，并把组件类型传入复制方法，避免重复读取与额外非空断言；
嵌套数组、null 元素、空数组及运行时数组类型保持夹具已补齐。现有反射数组实现的替换仍归 T03，完整矩阵尚未关闭。

测试模型的 Bean 在无参构造后可能尚未赋值，因此字段、getter/setter 显式标为 nullable；
数据库列的非空约束继续由元数据声明。record 模型的 nullable 列、版本初始值及 transient 默认值
同样准确标注。生产包和生成类不使用 `@NullUnmarked` 绕过检查。

仅以下字段使用 `@SuppressWarnings("NullAway.Init")`，理由均为 JSR 269 保证先调用 `init` 再调用
`process`；抑制只作用于初始化诊断，不屏蔽字段使用或生成合同：

| 类型 | 字段 | 理由与处置 |
| --- | --- | --- |
| `SkisEntityProcessor` | `scanner`、`validator`、`messager`、`filer`、`generators` | 由 `init` 赋值；本次精确登记，若生命周期变化必须同步检查 |
| `SkisProjectionProcessor` | `scanner`、`generator` | 同上 |

`PropertyMeta` 的值相等不代表属于同一规范元数据实例，以下成员保留身份比较并精确抑制
`ReferenceEquality`；现有规范属性正反例同时验证构造和查找行为。

| 类型 | 成员 | 理由与处置 |
| --- | --- | --- |
| `EntityRuntimeModel` | 四参数构造器 | 验证生成属性与实体属性为同一实例；保留比较并说明原因 |
| `EntityRuntimeModel` | `property(PropertyMeta)` | 拒绝值相同但实例不同的属性；保留比较并说明原因 |

`EntityRuntimeRegistry` 的构造输入及局部索引显式声明 `IdentityHashMap`，读取视图继续不可修改。
JDBC 引用读取以 `getObject` 返回的 null 表达 SQL NULL；`readInstant` 先判空再转换，
UUID 和时间对象读取省去重复的 `wasNull()` 调用，原始类型读取仍保留必需的 `wasNull()`。

NullAway 的泛型推断和泛型实现检查仍有工具能力限制。边界类型转换继续依赖现有类型/SQL/nullability
校验及生成合同正反例；不声称检查器完整证明所有泛型实现，未知诊断必须保留并定位，不能通过关闭
泛型选项或排除模块消除。

## CI 证据

Java 21 的 `engineering-gates` 与 Java 25 的 `unit-tests` 作业启用此 profile，要求 processor 专用套件、原 APT 套件及 query/mutation
名义类型套件非空、无跳过并成功执行。两种 Java 作业均保存 Maven/JDK 环境、完整构建日志、
Surefire XML，以及 `skis-processor/target/nullness-reports/*.txt`。Java 21 合并至
`engineering-gates-build-<runId>-<attempt>` 工件，Java 25 继续使用 `unit-test-reports-java-25`；
既有 `Unit tests (Java 21)` 名称转发权威作业结果，不再重复构建，依赖失败/跳过/取消时显式失败。
普通构建中专用检查用例按 profile 标记不执行；CI 中跳过不能冒充通过。

2026-10-21 已核实运行 `39559723860`，其 PR head 为 `2b89c55`，合并验证提交为 `92c0a8e`：

- [Java 21](https://github.com/xhufang/skis/actions/runs/39559723860/job/119087784905) 与
  [Java 25](https://github.com/xhufang/skis/actions/runs/39559723860/job/119087784487) 均在
  `DefaultSelectQuery.java:1028` 报告 `passing @Nullable parameter 'value' where @NonNull is required`。
  mapping、jdbc 已通过各自模块验证；query 编译失败使 mutation、runtime、processor 及后续模块跳过，专用 nullness 夹具尚未执行。
- 独立公共消费者、独立 PostgreSQL 消费者、集成模块、Gradle 增量处理及发布边界作业成功；这些结果不替代静态检查矩阵。
- 上述运行早于包内工厂修正；后续运行证据如下。

2026-10-21 已核实运行 `39566474356`（PR head `4223ba3`）及两个 Java 作业的完整工件：

- [Java 21](https://github.com/xhufang/skis/actions/runs/39566474356/job/119109852480) 与
  [Java 25](https://github.com/xhufang/skis/actions/runs/39566474356/job/119109852697) 的 Surefire XML 均显示：
  `NullnessCompilationTest` 为 8 个测试、0 失败/错误/跳过；`SkisEntityProcessorTest` 为 70 个测试、1 失败、0 错误/跳过。
- [Java 21 工件](https://github.com/xhufang/skis/actions/runs/39566474356/artifacts/12329598567)中首个未被拒绝的负例是 `matrix-row`；
  [Java 25 工件](https://github.com/xhufang/skis/actions/runs/39566474356/artifacts/12329519302)中是 `nested-array-row`。
  原测试遇到首个断言失败即停止，因此不能推定其他未留存报告的负例结果。
- 独立公共消费者、独立 PostgreSQL 消费者、集成模块、Gradle 增量和发布边界作业均成功；两个 Java 单元作业仍失败。
  当前对负例及失败报告的修正尚未执行，完整门禁仍待新 CI。

工具接线依据：[Error Prone 安装说明](https://errorprone.info/docs/installation)、
[NullAway JSpecify 支持](https://github.com/uber/NullAway/wiki/JSpecify-Support)、
[NullAway 配置](https://github.com/uber/NullAway/wiki/Configuration)。
