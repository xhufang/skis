# JSpecify 静态检查

`static-analysis` 是仓库维护者使用的显式 Maven profile。它对生产源码及 APT 生成源码运行
Error Prone 2.50.0 和 NullAway 0.14.2；普通构建继续使用 javac。检查器不属于发布运行时依赖，
独立消费者不继承 SKIS parent，也不需要安装检查器。

用户提供的 CI 日志先报告 `skis-mapping/readInstant` 空值错误，后续报告 `skis-jdbc` 分页泛型和游标返回值错误。
这些位置及同批身份语义警告已按下文修正，完整 Java 矩阵与夹具仍待 CI 重新验证，不能写成已通过。

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
javac 异常退出时也追加已收集的诊断与异常栈。工具缺失、版本不匹配或未加载时测试失败。

`NullnessCompilationTest` 覆盖 nullable/non-null 查询、SingleRow、List/Page/Slice、cursor/stream、
Codec、Binder、Decoder、投影读取器、mutation 和事务回调；泛型/通配符正反例与单独编译的字节码 API
另列。生成用例覆盖表、可复用/专用 Pro、Binder/Decoder、nullable 数组和调用方。
生成代码明确声明 `@NullMarked`，同时准确保留可空读取器类型及嵌套泛型/数组注解。
生成表的 nullable 列继续通过 `Selectable<V>` 选择为 `SelectQuery<@Nullable V>`，不改成非空选择器。
实体元数据及类字面量使用无类型注解的类型名；投影源码按外层到内层保留各数组维度的可空性。
普通 javac 的 JDBC 类型夹具覆盖可空 `byte[]`，专用投影夹具覆盖二维数组内层可空、外层可空、两层均可空及泛型内嵌数组。
查询回调正例显式使用计算结果，继续接受 Error Prone 默认返回值检查。

负例要求 ERROR 诊断包含指定检查器名、源码文件和有效行列位置，不能以普通语法错误或 APT 失败代替。
另对实际生成表注入一个 null 字段缺陷，证明 `@Generated` 源码没有被跳过。

## 生产契约与例外

`ProjectionMapping.ValueReader<V>` 由 `V` 表达结果可空性。生成代码根据构造参数选择 nullable 或
non-null `V`，编译好的非空读取器复用已有 JDBC 边界校验；没有新增逐行反射、参数快照或冗余非空检查。
`TransactionCallback<R>` 和 `inTransaction` 允许 nullable `R`，非空调用方仍不能接收可空结果。
JDBC 分页直接保留 `JdbcPageResult<R>`，由 `R` 的实际类型参数表达结果可空性。
游标当前值同样按 `R` 存储，只有未定位状态使用私有非空哨兵；`current()` 的状态检查阻止哨兵外泄，
已定位值直接来自 `RowDecoder<R>`，包括合法的 null 行。哨兵工厂显式返回 `@NonNull R`；类型擦除转换仅用于该哨兵，保留原有 Java unchecked 抑制，
没有新增 NullAway 抑制、逐行分配或额外非空检查。JDBC 分页/游标的编译正反例和 null 行状态转换夹具已补齐，待 CI 执行。

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

Java 21/25 的 `unit-tests` 作业启用此 profile，要求 processor 专用套件、原 APT 套件及 query/mutation
名义类型套件非空、无跳过并成功执行。每个矩阵作业始终保存 Maven/JDK 环境、完整构建日志、
Surefire XML，以及 `skis-processor/target/nullness-reports/*.txt`。
普通构建中专用检查用例按 profile 标记不执行；CI 中跳过不能冒充通过。

工具接线依据：[Error Prone 安装说明](https://errorprone.info/docs/installation)、
[NullAway JSpecify 支持](https://github.com/uber/NullAway/wiki/JSpecify-Support)、
[NullAway 配置](https://github.com/uber/NullAway/wiki/Configuration)。
