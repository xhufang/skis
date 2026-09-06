# ADR-0003：生成式结果形状与显式投影绑定

- 状态：已接受
- 日期：2026-09-03
- 影响版本：0.2.4-SNAPSHOT，最终随 0.3.0 公开
- 取代范围：ADR-0001 中实体绑定用户投影的映射身份与注册方式；ADR-0001 的缓存治理要求继续有效

## 背景

0.2.0 的用户投影以 `@SkisProjection(entity = E.class)` 绑定单一实体。APT 按构造器参数名匹配
`PropertyMeta<E, ?>`，生成内部 `ProjectionProvider`，再把 Provider 写入
`META-INF/skis/projections.idx`。runtime 在装配阶段建立按结果 `Class<R>` 索引的
`ProjectionRegistry`，用户通过 `selectProjection(table, ResultType.class)` 发起查询。

该模型能为单表属性子集提供无反射映射，但 0.2.4 引入显式 Join、同表多别名和跨表选择后暴露出结构限制：

1. 用户结果类型被固定到一个实体，无法自然表达跨表列、计算表达式、后续聚合、派生表或标量子查询。
2. DTO 参数名承担隐式实体属性查找职责，但别名和表 occurrence 属于查询局部状态，不能安全写入投影声明。
3. `Class<R> + QueryColumn<?, ?>...` 虽可扩展跨表绑定，却不能让 javac 校验任意参数数量和每个位置的 Java 类型。
4. `Projection<E, R>` 从类型参数、字段、Codec 到 Decoder 都绑定单个 `EntityRuntimeModel<E>`；以
   `Projection<Void, R>`、nullable entity 字段或模式分支扩展会形成两套持续漂移的实现。
5. 按结果 Class 建立全局注册表要求一个结果类型只有一个注册投影，并引入 Provider、索引、装配期加载、unchecked
   泛型收窄和 ClassLoader 生命周期成本。
6. 外连接后的有效 nullability 只能在完整 Join AST 建立后确定，不能仅凭 DTO 注解或物理列元数据判断。

SKIS 的长期约束是“一套 AST、一套执行内核、热路径无反射”。用户投影应当表达结果行构造，而不是把查询来源隐藏在 DTO
声明中。

## 问题

需要同时确定：

1. 用户投影是否继续区分单实体投影与跨表行投影。
2. 选择列由注解、运行时 Class 查找还是生成式强类型入口绑定。
3. 哪些错误由 APT/javac 检查，哪些必须留到查询编译阶段。
4. 如何在不反射、不按列名读取的前提下支持任意构造器参数数量。
5. 如何为计划缓存和 continuation 提供稳定、值无关且不依赖对象地址的映射身份。
6. 是否仍需要投影 Provider、索引和全局注册表。

## 候选方案

### 方案一：保留实体投影，新增 `entity = void.class` 行投影模式

指定 `entity` 时沿用属性名映射；省略时由
`selectProjection(ResultType.class, QueryColumn<?, ?>...)` 显式传入列。

优点：现有单表入口改动较小；用户只认识一个注解。

缺点：运行时仍有两种投影模型；`void.class` 会渗入泛型或分支；Class + varargs 无法获得足够的 javac 检查；注册表仍限制
一个结果 Class 只能有一个映射；后续表达式投影继续扩展同一分支矩阵。

### 方案二：新增独立 `@SkisRowProjection`

实体投影和行投影使用不同注解、Provider 和查询入口。

优点：声明意图明确；不会误把遗漏 entity 当作行投影。

缺点：永久维护两套公共术语、APT 分支、运行时描述符和测试矩阵；单表与跨表只因历史来源不同而不能共享同一结果形状模型。

### 方案三：构造器 lambda、Tuple 或固定 arity 函数族

查询选择若干表达式后，通过 `PetOwnerView::new` 或 `Function2`—`FunctionN` 映射。

优点：调用点直接；部分 Java 类型由方法引用检查；可以减少 APT 工作。

缺点：Java 标准库只提供有限函数 arity；自建 FunctionN 会膨胀公共 API；lambda 可能捕获状态且缺少稳定结构身份；
nullability、SQL 类型、构造器可访问性和生成 ABI 难以集中验证；不符合默认生成式映射路径。

### 方案四：APT 生成强类型投影伴生类并显式绑定选择表达式

`@SkisProjection` 只选择用户构造器。APT 为每个结果类型生成公开的固定参数 `*Projection.of(...)`，返回
`ProjectionSelection<R>`；查询使用 `select(ProjectionSelection<R>)`。

优点：只有一套用户投影模型；任意 arity 不需要 FunctionN；javac 检查数量和 Java 类型；查询编译器继续负责作用域、
SQL 类型和有效 nullability；直接引用生成伴生类后无需 Class 查找、Provider、索引或注册表；未来聚合、CASE、CAST、
派生表和子查询可复用同一 `Selectable`。

缺点：破坏现有公共入口；用户源码需要引用生成伴生类并显式列出选择表达式；生成伴生类成为应用源码可见 ABI；两个相同
Java 类型的参数互换仍无法由类型系统识别。

## 决策

采用方案四，并在 0.2.4-SNAPSHOT 直接完成破坏性迁移。

### 1. 投影只描述结果构造

- `@SkisProjection` 删除 `entity()`。
- 删除 `@ProjectionProperty`。
- record 使用规范构造器；普通 class 使用唯一公开构造器或一个显式 `@ProjectionConstructor`。
- 参数名只进入生成方法参数名、参数合同和错误消息，不用于查找实体属性。
- SKIS 只生成伴生映射器，不生成或修改用户 DTO。

### 2. 生成固定参数强类型入口

对：

```java
@SkisProjection
public record PetOwnerView(Long petId, @Nullable String ownerName) {}
```

APT 生成语义等价于：

```java
public final class PetOwnerViewProjection {

  private static final ProjectionMapping<PetOwnerView> MAPPING = /* generated */;

  public static ProjectionSelection<PetOwnerView> of(
      NonNullSelectable<Long> petId,
      Selectable<String> ownerName) {
    return MAPPING.bind(petId, ownerName);
  }
}
```

- 方法参数数量与构造器参数数量完全一致，不提供公共 wildcard varargs 捷径。
- primitive 或明确非空构造参数接收 `NonNullSelectable<V>`；允许 null 的参数接收 `Selectable<V>`。
- `Selectable` 由框架控制，不能成为注入原生 SQL 的开放实现点。
- 相同输入必须生成字节级稳定源码，生成类携带生成器名称和 ABI 版本。

投影参数 null 合同按 JSpecify 作用域解析。primitive、显式 `@NonNull` 以及 `@NullMarked` 作用域内未标
`@Nullable` 的引用类型要求非空；只有显式或作用域解析为 `@Nullable` 的引用类型接受 SQL NULL。
`@NullUnmarked`/未知上下文中的未标注引用类型按非空要求保守处理；用户如需接收 SQL NULL 必须显式标注
`@Nullable`，不能让未知 nullness 静默放宽结果合同。

### 3. 查询入口统一为结果形状

```java
executor
    .select(PetOwnerViewProjection.of(pet.id(), owner.name()))
    .from(pet)
    .leftJoin(owner)
    .on(pet.ownerId().eq(owner.id()))
    .fetchList();
```

- 删除 `selectProjection(QueryTable<E>, Class<R>)`。
- 增加 `select(ProjectionSelection<R>)`，返回根实体泛型独立的 `ProjectionSelectFromStep<R>`。
- `select(table)`、`select(column)`、`select(ProjectionSelection)` 在内部归一为统一结果形状编译流程。
- 0.2.4 首批 `Selectable` 只覆盖列；公共语义允许后续标准选择表达式加入，但不预先开放空实现 SPI。

### 4. 映射分为声明、绑定和解析三阶段

1. `ProjectionMapping<R>`：查询无关的构造契约，保存结果类型、映射 ID、参数合同和 Decoder factory。
2. `ProjectionSelection<R>`：映射与本次查询有序选择表达式的不可变绑定。
3. `ResolvedResultShape<R>`：查询编译内部结构，保存表 occurrence、有效 nullability、Codec 和 ResultSet 布局。

`ProjectionMapping` 不持有实体、表、别名、Codec 对象或参数值；`ProjectionSelection` 不捕获普通绑定参数值；
`ResolvedResultShape` 不作为公共扩展 SPI。

### 5. 校验责任严格分层

APT 校验：

- 投影类型、构造器、参数类型和生成包可访问性；
- 参数声明 null 合同；
- 生成源码稳定性。

javac 通过生成方法签名校验：

- 选择项数量；
- 每个位置的 Java 泛型值类型；
- 物理 nullable 表达式不能直接绑定 primitive 或明确非空参数。

查询编译器在全部 Join 完成后校验：

- 选择表达式位于最终作用域；
- boxed Java 类型防御性一致；
- SQL 类型符合集中兼容规则；
- Join 和表达式传播后的有效 nullability 可被构造参数接受；
- 每个选择项可以解析到规范 Codec；
- 一基 ResultSet 布局稳定且与生成 Decoder 参数顺序一致。

Decoder 逐行阶段只进行按下标的 Codec 读取、非空防御检查和直接构造器调用。

### 6. 删除投影发现链路

删除：

- 实体绑定 `Projection<E, R>`；
- `ProjectionRegistry`；
- `ProjectionProvider`；
- `ProjectionModelLoader`；
- `META-INF/skis/projections.idx`；
- 按结果 `Class<R>` 的运行时查找和 unchecked 收窄。

实体 `EntityRuntimeModelProvider` 与 `META-INF/skis/entities.idx` 保留，因为查询仍需从规范实体模型解析表 occurrence 和
属性 Codec。

### 7. 稳定映射身份

映射 ID 至少包含：

- 结果类型二进制名称；
- 选定构造器描述符；
- 有序参数 Java 类型和 null 合同；
- 生成 ABI 版本。

未来通用计划键还必须包含有序选择 AST、表 occurrence、Join 结构和方言能力版本。对象地址、Codec 对象身份及普通参数值
不得进入 continuation 或可序列化查询指纹。查询对象内部可以使用对象引用做短期发布和复用，但不能把它当作跨查询结构身份。

## 后果

### 正面后果

- 单表、跨表和后续表达式投影只有一套语义及编译路径。
- 编译器而不是运行时 Class 查找承担参数数量和 Java 类型验证。
- 删除投影全局注册和结果类型唯一性限制，未使用的投影不会在启动期加载。
- 同表多别名天然由具体选择表达式和 occurrence 区分。
- 外连接有效 nullability 在正确的查询阶段统一处理。
- 逐行热路径继续无反射、无列名匹配和无 Codec 查找。
- 结果形状可以自然扩展到 0.2.5 的聚合、CASE、CAST、派生表和标量子查询。

### 负面后果

- 所有现有用户投影调用必须迁移，无法保持 0.2.0 源码兼容。
- 用户需要显式引用 APT 生成的伴生类和选择表达式。
- DTO 构造器签名变化会使使用伴生方法的查询源码重新编译失败；这是预期的强类型失败方式。
- 两个同 Java 类型参数的位置互换仍可能通过 javac；参数名、IDE 提示和运行时诊断只能降低风险，不能证明业务语义。
- 生成源码成为用户编译图的一部分，必须对生成 ABI、增量处理和错误信息投入更严格测试。

## 兼容性与迁移

旧代码：

```java
@SkisProjection(entity = Pet.class)
public record PetSummary(Long id, String name) {}

executor.selectProjection(pet, PetSummary.class).fetchList();
```

迁移为：

```java
@SkisProjection
public record PetSummary(Long id, String name) {}

executor
    .select(PetSummaryProjection.of(pet.id(), pet.name()))
    .from(pet)
    .fetchList();
```

- 提升生成 ABI，并在旧生成代码与新 runtime 混用时 fail-fast。
- `SKIS219`—`SKIS224`、`SKIS298` 保留历史语义但停止产生，不得重用于新错误。
- 删除的公共类型和方法登记到 `0.2.0 → 0.3.0` 兼容报告及迁移文档。
- 不保留默认抛 `UnsupportedOperationException` 的旧入口，也不在内部维护旧查询编译支路。
- 历史版本说明和已发布 API 文档保留原行为；面向 0.3.0 的开发指南、SQL DSL 文档和示例使用新入口。

## 性能影响

- 删除启动期投影索引读取、Provider 实例化、结果 Class Map 和查询期注册表查找。
- `ProjectionSelection` 是查询构建期的小型不可变对象；其选择表达式本来就是 AST 构建所需信息，不增加逐行成本。
- Codec、nullability 和列下标只在计划编译时解析一次，生成的 `RowDecoder` 可被查询对象局部计划及未来通用缓存复用。
- 逐行只执行专用 Codec、非空分支和直接构造器调用，目标仍是不低于同等手写 JDBC-by-index 的既定门槛。
- 固定参数生成方法避免通用 Object 数组的调用点类型检查；内部允许在查询构建阶段做一次防御性集合复制。

## 安全影响

- 所有选择表达式仍来自框架控制的 AST，不允许生成伴生类接受字符串 SQL、字符串列名或用户实现的不透明表达式。
- 投影错误不得输出绑定参数值，只输出结果类型、参数名/序号、表达式结构摘要、表 occurrence、Java/SQL 类型和
  nullability。
- 删除反射和运行时生成类查找，减少 Native Image 配置、非法访问和 ClassLoader 注入面。
- 映射身份不包含用户输入或参数值，不改变 PreparedStatement 参数化和日志脱敏规则。

## 回滚方案

如果公开生成伴生类导致无法接受的源码或增量编译问题，可以保留 `ProjectionMapping<R>`、
`ProjectionSelection<R>` 和统一结果形状编译器，仅增加一个显式、非默认的基础设施注册适配层，把结果 Class 解析为同一
`ProjectionMapping<R>`；不得恢复实体绑定 `Projection<E, R>`、参数名运行时属性匹配或逐行反射。

如果固定参数 `of(...)` 生成导致极端大投影源码不可接受，可以让生成伴生类内部使用数组保存选择项，但公开方法仍保持固定
参数签名；不得退化为用户直接调用 wildcard varargs。命名 builder 只有在真实错误数据证明同类型参数误序频繁时再作为
独立设计加入，不与 0.2.4 同时冻结。
