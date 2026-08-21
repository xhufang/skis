# SKIS ORM

[![CI](https://github.com/xhufang/skis/actions/workflows/ci.yml/badge.svg)](https://github.com/xhufang/skis/actions/workflows/ci.yml)
[![API compatibility](https://github.com/xhufang/skis/actions/workflows/api-compatibility.yml/badge.svg)](https://github.com/xhufang/skis/actions/workflows/api-compatibility.yml)
[![Compliance](https://github.com/xhufang/skis/actions/workflows/compliance.yml/badge.svg)](https://github.com/xhufang/skis/actions/workflows/compliance.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

SKIS 是一个以类型安全、可预测 SQL 和低运行时开销为目标的 Java JDBC ORM。它使用 Java
注解处理器生成实体元数据、参数绑定器和结果解码器，默认热路径不依赖运行时反射。

> 当前状态：`0.0.4-SNAPSHOT` 工程预览。仓库正在建立发布、兼容性与供应链基线，尚未形成可用于生产的完整
> ORM，也尚未发布到 Maven Central。

## 设计边界

- 直接基于 JDBC，不依赖 JPA Provider、Hibernate、MyBatis 或 Jimmer 运行时。
- SQL DSL、租户、权限和逻辑删除最终统一作用于 SQL AST。
- 不提供隐式懒加载、运行时实体代理或隐藏的数据库访问。
- 用户自行定义 Java `record` 或普通类作为投影结果；SKIS 不生成 DTO 类型。
- Spring Boot 集成保持在独立模块中，核心模块不绑定 Spring。

## 当前实现

当前源码包含多模块 Maven/BOM 骨架，以及首批可工作的基础组件：

- `skis-annotations`：实体、表、列、主键、版本和忽略映射注解。
- `skis-metadata`：不可变实体、属性、表、列、主键和生成代码 ABI 元模型。
- `skis-processor`：生成元模型、表模型、Binder 和按列下标读取的 RowDecoder。
- `skis-mapping`：JDBC Codec、Binder、Decoder 和行布局基础抽象。
- `skis-sql-ast`：标识符、表和列表达式的首批 AST 节点。
- `skis-core`：公共异常和 ID 生成基础能力。

其余模块目前主要是架构占位，不能据此认为对应功能已经完成。

## 环境要求

| 项目 | 基线 |
| --- | --- |
| Java | 21；CI 同时验证 21 和 25 LTS |
| Maven | 3.9+，优先使用仓库内 Maven Wrapper |
| Spring Boot | 4.1.x，仅适用于 `skis-spring-boot*` 模块 |
| 构建系统 | Maven 多模块工程 |

## 从源码开始

Linux/macOS：

```bash
./mvnw verify
```

Windows：

```powershell
.\mvnw.cmd verify
```

只构建一个模块及其依赖：

```bash
./mvnw -pl skis-processor -am verify
```

生成依赖许可证清单和 CycloneDX SBOM：

```bash
./mvnw -Pcompliance verify
```

产物位于 `target/generated-resources/licenses` 和 `target/sbom`。构建命令会访问 Maven Central
下载依赖；首次执行需要可用网络。

## 注解处理示例

```java
import io.skis.annotations.Column;
import io.skis.annotations.Id;
import io.skis.annotations.SkisEntity;
import io.skis.annotations.Table;
import io.skis.annotations.Version;

@SkisEntity
@Table(name = "book")
public record Book(
    @Id long id,
    @Column(name = "book_name", nullable = false, length = 200) String name,
    @Version long version) {}
```

在 `0.0.4-SNAPSHOT` 阶段，这个示例只说明已实现的注解处理模型；ConnectionProvider、完整方言、
`SkisExecutor` 和 CRUD 闭环属于后续版本。

## 版本路线

| 版本 | 核心目标 |
| --- | --- |
| 0.0.4 | CI、许可证、README、API 兼容检查、SBOM 和发布工程基线 |
| 0.0.5 | ConnectionProvider、PostgreSQL/H2 方言、基础 SQL Renderer |
| 0.0.6 | SkisExecutor、查询编译、无反射 `findById` 读取闭环 |
| 0.0.7 | insert/update/delete、事务、版本字段和回滚语义 |
| 0.0.8 | projection、真实数据库合同测试、资源泄漏和安全测试 |
| 0.0.9 | 性能基准、API 收口、示例、文档和发布演练 |
| 0.1.0 | 修复 RC 问题后发布最小可用 JDBC ORM |

## 兼容性和发布

- `0.x` 仍允许快速演进；破坏性 API 变更必须写入 [CHANGELOG.md](CHANGELOG.md) 的迁移说明。
- Pull Request 会以目标分支为基线运行 japicmp，阻止未处理的源码或二进制不兼容。
- 正式版本使用 `v<version>` 标签；标签必须与非 `SNAPSHOT` 的 POM 版本完全一致。
- 正式发布产出源码、Javadoc、GPG 签名、CycloneDX SBOM、Maven Central 部署和 GitHub Release。

## 参与贡献与安全

请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 和 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。安全问题不要提交公开
Issue，请按 [SECURITY.md](SECURITY.md) 中的私密流程报告。

## 许可证

SKIS 使用 [Apache License 2.0](LICENSE)。版权及第三方通知见 [NOTICE](NOTICE)。
