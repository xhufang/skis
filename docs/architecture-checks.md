# 0.2.8 架构检查

T03 已完成源码、POM 和 CI 接线，尚未取得本版执行证据。本地不编译运行；以下检查由 CI 执行，不能据接线状态宣称通过。

## 模块与依赖

[modules.json](../.github/quality/modules.json) 登记实际 Reactor 的路径、artifact、职责和允许的直接生产依赖。
检查递归读取根 POM 的实际 modules，核对全部 46 个条目（根聚合、44 个根模块及 H2 示例）；新增、删除、未分类、
生产源码与职责不符、生产产物缺失或为空都会失败。模块治理 T08 删除占位模块时必须同步修改政策。

生产模块的 Enforcer `enforce-runtime-boundaries` 检查传递依赖闭包。普通核心只准入登记的 SKIS 依赖、JSpecify
和 SLF4J API；annotations 只允许 JSpecify。Spring 集成另准入实际 Spring JDBC/事务依赖及 Spring 7 必需的
Commons Logging 门面。测试依赖按 test scope 放行，不进入运行时准入。现存无实现的远期 Boot 模块保持 placeholder，
其删除由 T08 负责，不能凭空模块宣称 Starter 已支持或其运行时已验收。

架构检查同时核对每个 `production` 模块 POM 中显式声明的 `enforce-runtime-boundaries` 执行：
必须在 validate 阶段执行 enforce、启用传递依赖检查，并按模块政策推导的 SKIS 依赖闭包及上述外部坐标精确准入。
缺少插件或执行、仅放入 pluginManagement、关闭失败或传递检查、降级为警告、筛除规则和扩大准入都会失败；
新增模块即使已经登记进 modules.json，也必须通过此项核对。构建侧负例随 `ArchitectureContractTest` 执行。

CI 使用实际 core POM 的执行配置，分别证明直接 Spring、放行直接坐标后仍禁止的传递 Spring、合法 test scope 三种结果。
负例必须因 BannedDependencies 失败，依赖下载失败或其他构建错误不能替代负例证据。

## 字节码与公开边界

`ArchitectureContractTest` 从每个有生产源码的模块的 `target/classes` 导入 ArchUnit 字节码，包括 APT 生成类；
不以集成测试自身的 classpath 决定扫描范围。检查依赖方向、模块无环、普通 API/SPI 的公开及受保护字段、方法、
构造、泛型边界和继承关系。公开方法体使用框架实现是允许的，公开签名不得出现 internal。

[internal-access.json](../.github/quality/internal-access.json) 逐项登记跨模块内部协作的实际调用类型、目标类型、理由、
责任和退出安排，不支持包通配符。持有计划的 record 与嵌套类型也须按各自二进制类名登记，不继承外层类型的权限。
它不是对应用或生成代码的授权。生成源码和生成字节码另行检查，四个独立消费者
在自己的构建完成后使用 `VerifyExternalBytecode.java` 检查实际 class 文件，包括泛型签名和数组类型。
只有测试源码的 spi-only、listener-only 消费者检查 `target/test-classes`；H2/PG 消费者同时检查
`target/classes` 与 `target/test-classes`，覆盖生成产物和真实调用代码。每个适用目录都必须存在且包含 class 文件，
不以跳过缺失或空目录处理扫描范围错误。

公开 DSL 继续使用根包业务类型。描述、单列描述、派生来源及输出、排序、参数、continuation、切片请求和单行结果的实现位于 internal；
接口不构成任意第三方实现的 SPI。内部在捕获边界核对框架实例，未增加公开 state/AST 访问器、反射访问或全局注册表。
生成表仍继承 `QueryTable`，原有 protected 构造及类型安全列方法保持；其状态归 `QueryTableModel`，
`columns()` 提供按元数据顺序排列的不可变 DSL 列，用于元数据驱动的选择。普通业务调用和生成代码不引用模型实现。

`QueryRuntime` 和 `QueryPlanCatalog` 已移入 `io.skis.query.internal`，根包不保留别名；公共查询预算守卫删除两项豁免，
仍统计普通 29 项查询类型及 core 查询异常，共 30 项。内部重构不复制 Catalog、共享缓存、参数值或逐行结果。

## 热路径与精确例外

除 APT 编译器模块外，所有导入的生产类均检查动态成员发现、反射调用、类路径/注解扫描、MethodHandle/VarHandle
发现或动态调用、动态代理和外部 SQL 字符串解析器。该范围覆盖查询/写入终止、编译与计划查找、绑定、Codec/Decoder、
行读取、cursor/stream 和监听。`Class.getName/getComponentType` 等纯类型元数据读取不作反射发现处理。

| 精确入口 | 允许操作与用途 | 调用方限制 | 责任与退出 |
| --- | --- | --- | --- |
| `GeneratedIndexReader.resources` | `ClassLoader.getResources`，枚举固定实体索引 | 仅 `read`，再由 `EntityRuntimeModelLoader.load` 调用 | T03 登记，T09 验证索引合同，T10 复核 |
| `EntityRuntimeModelLoader.loadProvider` | `Class.forName/getConstructor`、`Constructor.newInstance`，装配索引中的生成提供者 | 仅 `load`，装配入口为 `Skis.Builder.build` | T03 登记，T09 验证提供者失败边界，T10 复核 |
| `ProjectionMapping.hasInstanceState` | `Class.getDeclaredFields`、`Field.getModifiers`，建立有状态 factory 的缓存身份 | 仅 `statefulDecoderToken`；映射构造可多次发生 | T03 登记，T09 验证复用映射在 bind/命中/解码时不重扫，T10 复核 |

装配调用链按包含参数类型的完整成员签名匹配，区分构造期的 `statefulDecoderToken(DecoderFactory<?>)`、
读取缓存身份的 `statefulDecoderToken()` 与同名字段。读取已缓存的身份不属于动态发现；构造期扫描的调用方限制保持生效。

数组快照已改为 primitive 数组直接 clone、运行类型不变的 Object 数组 clone 后递归必要元素；深哈希采用相应数组内容哈希。
保留嵌套数组、null 元素、SQL 日期和深相等/hash 关系，没有为了求哈希复制数组，也没有额外快照层。

## CI 与证据

Java 21/25 的完整 Reactor 构建结束后，CI 再以 `architecture` profile 单独执行 `ArchitectureContractTest`，
避免只构建集成测试依赖闭包时漏掉 Spring 和示例。profile 未选择时普通构建不要求全仓产物；专用作业必须有实际执行、
零跳过的架构测试报告。保留现有查询类型、消费者、发布边界和 benchmark 冻结检查，不恢复跨版本兼容检查或 benchmark。

CI 上传架构日志、Surefire 报告、政策 JSON 及 Enforcer 正反例日志。ArchUnit 只证明静态结构；普通/事务共享 Catalog、
L0/L1/L2、快照隔离、缓存并发与回收仍由原有动态合同和 T09 回归验证，不能将字节码检查当作性能测量。
