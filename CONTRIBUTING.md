# 参与 SKIS 开发

感谢你帮助改进 SKIS。项目仍处于早期阶段，提交应尽量小而明确，并保持实现、测试和文档同步。

## 开发环境

- JDK 21 或更高版本；提交前至少使用 JDK 21 验证。
- 使用仓库内 Maven Wrapper，避免依赖本机 Maven 版本。
- Git，建议启用对 LF 行尾的支持；仓库的 `.gitattributes` 是最终规则。

## 开发流程

1. 从 `main` 创建功能分支。
2. 在对应模块修改代码，并为成功、失败和边界情况补测试。
3. 涉及公共 API、配置键或行为时，同步 README/Javadoc/CHANGELOG。
4. 涉及 SQL 时补确定性的 SQL golden test；涉及数据库时补真实驱动集成测试。
5. 提交 Pull Request，说明动机、行为变化、风险和验证范围。

常用命令：

```bash
./mvnw verify
./mvnw -pl <module> -am verify
./mvnw -Pcompliance -DskipTests verify
```

## 公共 API 变更

CI 使用 japicmp 比较 Pull Request 目标分支与当前分支中同名 JAR 的 public API。删除类型、降低可见性、修改方法签名
等源码或二进制不兼容会使检查失败。

`0.x` 版本确需破坏性变更时，Pull Request 必须：

1. 明确说明为什么无法保持兼容；
2. 在 `CHANGELOG.md` 的 `Unreleased` 下写迁移步骤；
3. 由维护者确认版本号和兼容策略后再调整门禁。

本地 API 比较需要先把基线源码安装成一个不同版本，然后传入该版本：

```bash
./mvnw -Papi-compatibility -Dapi.previous.version=<baseline-version> verify
```

## 依赖与许可证

- 新依赖必须有明确、可再分发且与 Apache-2.0 项目兼容的许可证。
- 不要提交供应商 JAR、数据库驱动或复制来源不明的源码。
- 复制第三方源码时必须保留原版权、许可证和 NOTICE，并在 Pull Request 中说明来源。
- `compliance` profile 会检查依赖许可证元数据并生成 `THIRD-PARTY.txt` 与 CycloneDX SBOM。

## 提交质量

- 不在默认热路径引入反射、类路径扫描或 SQL 文本重解析。
- 缓存必须有界；数据库和流资源必须可确定关闭。
- 异常和日志不得泄漏密码、密钥或完整绑定参数。
- 不引入 MyBatis、JPA Provider、Hibernate 或 Jimmer 运行时及其兼容层。
- 不提交生成目录、IDE 元数据或本地密钥。

提交贡献即表示你同意按仓库的 Apache License 2.0 对贡献进行许可。
