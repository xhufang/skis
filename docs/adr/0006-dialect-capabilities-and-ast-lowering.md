# ADR-0006：方言能力与 AST Lowering 边界

- 状态：已接受
- 日期：2026-09-09
- 影响版本：0.2.5-SNAPSHOT

## 背景

0.2.5 将在现有单表和 Join 查询上增加 EXISTS、IN 子查询、标量子查询、相关引用、派生来源、GROUP BY、HAVING
以及 COUNT/SUM/AVG/MIN/MAX。现有 `DialectFeature` 只覆盖少量分页、标识符和 Join 能力，`Dialect#validate(...)`
也主要检查最外层 Join，无法证明嵌套查询块和聚合类型能够由目标方言正确实现。

D08 已定义跨方言一致的聚合 Java 返回类型、SQL 类型、nullability 和精度合同。PostgreSQL、H2 对整数 AVG、近似数
SUM 等表达式的原生输入提升和 JDBC 返回类型并不完全一致。若把这些差异放进 Renderer 或 Decoder，类型语义会依赖
渲染或读取时猜测；若为每个输入、结果和子句组合增加能力位，能力集合又会退化为难以测试的规则矩阵。

## 问题

需要同时确定：

1. 哪些差异属于公开、可枚举的方言语法能力。
2. 跨方言聚合类型矩阵由哪一层拥有，方言如何兑现该矩阵。
3. 能力校验和方言转换在查询编译流水线中的固定位置。
4. 直接 AST 渲染、嵌套查询块和第三方方言实现如何遵守同一合同。
5. 首批实现是否承担去相关、等价 SQL 替换等优化器职责。

## 候选方案

### 方案一：为每个语法、位置和类型组合增加 DialectFeature

能力检查直接，但会产生例如“整数 AVG”“派生 Join”“HAVING 中相关标量子查询”之类组合位。枚举不能表达完整输入/结果
映射，组合数量随类型和上下文相乘，也很难保证不同方言声明的一致性。

### 方案二：Renderer 按方言识别表达式并临时插入 CAST

表面改动较小，但 Renderer 同时承担语义分析、类型推断、AST 改写和字符串输出。直接渲染与查询编译容易走出不同路径，
字符串阶段的改写也会破坏别名、查询块路径和参数位置合同。

### 方案三：粗粒度能力、集中类型规则和方言 AST lowering 分层

能力枚举只描述能够独立验证的语法，公共类型矩阵由框架集中管理，方言在渲染前对结构 AST 做最小必要转换。递归验证器
统一保护查询编译和直接渲染入口。

## 决策

采用方案三。

### 方言能力

0.2.5 新增以下 `DialectFeature`：

- `EXISTS_SUBQUERY`
- `IN_SUBQUERY`
- `SCALAR_SUBQUERY`
- `CORRELATED_SUBQUERY`
- `DERIVED_TABLE`
- `GROUP_BY`
- `HAVING`
- `COUNT_AGGREGATE`
- `SUM_AGGREGATE`
- `AVG_AGGREGATE`
- `MIN_AGGREGATE`
- `MAX_AGGREGATE`

保留已有 `COUNT_DISTINCT`。`DialectCapabilities` 继续保存显式不可变集合，不加入 Java/SQL 类型组合位。能力表示对应
SKIS 方言实现已经通过 SQL golden 和适用的真实数据库合同，而不只表示数据库文档声称接受某段语法；没有证据时保持
失败关闭。

组合规则固定如下：

- `NOT EXISTS` 与 `EXISTS` 共用 `EXISTS_SUBQUERY`；`NOT IN` 与 `IN` 共用 `IN_SUBQUERY`。
- 相关用法同时要求对应子查询形态能力与 `CORRELATED_SUBQUERY`。
- 派生根和 Join 右来源共用 `DERIVED_TABLE`。它不蕴含 LATERAL/APPLY；普通派生来源依赖外层查询块仍是可移植语义错误。
- 普通 COUNT 要求 `COUNT_AGGREGATE`；COUNT DISTINCT 同时要求 `COUNT_AGGREGATE` 和 `COUNT_DISTINCT`。
- `GROUP_BY`、`HAVING` 与各聚合能力互不推导。

### 聚合类型规则

D08 的公共聚合 Java 返回类型、SQL 类型、nullability、允许输入、精度和溢出边界由 SQL AST/语义层的集中
`AggregateTypeRules` 管理。它是框架拥有的公共语义来源，不是方言能力集合，也不是 Renderer 或 Decoder 的启发式规则。

方言校验器根据已解析表达式类型检查目标方言能否兑现该矩阵。无法通过明确 AST lowering 达成公共合同的输入组合在正式
渲染和 JDBC 前失败，不允许悄悄暴露数据库原生返回类型。

### 方言 lowering

新增小型公开 `DialectLowering` SPI，并由 `Dialect#lowering()` 默认返回 `DialectLowering.identity()`。已有第三方方言不必
立即实现新方法；但它们未声明的新能力仍不可用。lowering 只接受已经通过可移植语义和方言能力/类型校验的结构 AST，
返回可由该方言直接渲染的结构 AST。

`Dialect` 的兼容入口固定为：

```java
default DialectLowering lowering() {
    return DialectLowering.identity();
}
```

0.2.5 允许的转换限于兑现既定类型合同所需的显式 CAST，例如：

- PostgreSQL 将 `Float`/`REAL` SUM 的聚合输入提升为 `DOUBLE PRECISION`。
- H2 将 TINYINT/SMALLINT/INTEGER AVG 的聚合输入提升为合适的 `NUMERIC`。
- H2 对 DOUBLE PRECISION 的 SUM/AVG 在原生聚合语义已经满足合同、但结果类型为 DECFLOAT 时，将最终结果转换为
  `DOUBLE`。

原则上优先转换聚合输入，使数据库以足够宽的状态累加或求平均。只有原生聚合语义已经正确、仅 JDBC 结果类型不符合
公共合同，才允许使用结果侧 CAST。

Renderer 只序列化 lowering 后 AST，不推断聚合类型、不修改原始 SQL，也不先把子查询渲染成字符串再替换别名、参数位置
或子句。

### 编译和校验顺序

在既有可移植语义校验、策略注入、规范化和规划阶段形成最终抽象 AST 后，D12 约束的后半段固定顺序为：

1. 递归方言能力和类型支持校验。
2. 方言 AST lowering。
3. lowering 后 AST 结构校验。
4. 按 D11 为最终内容、count 等实际语句分别生成逻辑槽和 JDBC 位置布局。
5. Renderer 序列化 SQL。

能力校验递归覆盖每个查询块的可见/隐藏 SELECT、FROM/派生来源、Join 来源与 ON、WHERE、GROUP BY、HAVING、ORDER BY
和分页结构。错误至少包含方言 ID、缺失能力、稳定查询块路径、子句以及表达式或聚合种类。查询编译与直接 Renderer 入口
必须复用同一验证器，直接 AST 渲染不能绕过能力、类型或 lowering 后结构校验。

### 非目标

0.2.5 不实现子查询去相关、IN/EXISTS/Join 互换、HAVING 下推、派生表合并或其他优化器式改写。H2 兼容模式不能替代
PostgreSQL 的真实合同；H2 未通过的组合保持拒绝。MySQL 正式能力矩阵留到 0.2.8。

## 后果

- 新能力有稳定、可诊断且递归的失败边界，不会等到数据库返回模糊语法或类型错误。
- 公共聚合返回类型不因 Renderer、Decoder 或 JDBC 驱动差异而漂移。
- 方言差异集中在小型 lowering 中，Renderer 保持确定性序列化职责。
- 新增语法或聚合时必须同时提供能力声明、类型规则、必要 lowering、SQL golden 和真实数据库证据。
- 本决策不引入通用规则引擎或查询优化器；某些数据库本可执行但尚未验证的组合会被保守拒绝。

## 兼容性

新增 `DialectFeature` 枚举项不改变已有常量语义。`Dialect#lowering()` 使用默认方法，现有第三方 `Dialect` 实现保持源代码和
二进制兼容；由于新能力默认不存在，旧方言面对 0.2.5 新 DSL 时会在 JDBC 前明确失败。若第三方直接调用 Renderer，
也必须经过统一验证入口，不能依赖过去可绕过 `Dialect#validate(...)` 的行为。

`AggregateTypeRules` 是框架集中语义，不向第三方开放任意覆写公共返回类型的入口。需要新增公共类型组合时按版本兼容规则
评审，而不是由单个方言静默扩展。

## 性能影响

递归能力校验和 lowering 在计划编译阶段各遍历结构 AST，一般为线性成本；结果进入查询对象局部分析或计划复用，不进入
逐行绑定或解码热路径。能力集合仍为枚举集合查询，类型规则使用确定映射，不进行数据库探测或反射扫描。

lowering 产生的新 AST 只与结构和方言有关，参数值不进入转换、结构指纹或共享计划键。最终参数布局在 lowering 后生成，
避免转换引入表达式时发生槽位漂移。

## 安全影响

该设计保持参数化 SQL；lowering 不读取、拼接或记录参数明文。失败诊断只包含方言、能力、查询块路径、子句和表达式种类，
不包含实际参数值。禁止字符串级 SQL 改写可减少别名逃逸、参数错位和原始片段注入的风险。

## 回滚方案

若某个内置方言的特定 lowering 尚不可靠，可移除该方言对应能力声明，使组合在 JDBC 前失败，同时保留公共类型合同。若
新的 lowering 扩展点暂时没有非 identity 实现，可让所有方言返回 identity；不得把类型推断和改写退回 Renderer、Decoder
或字符串处理。公开 SPI 一经发布不在兼容版本中删除，后续调整通过新增默认方法或新的小型接口演进。
