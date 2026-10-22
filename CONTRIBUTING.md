# 参与 SKIS 开发

感谢你帮助改进 SKIS。项目仍处于早期阶段，提交应尽量小而明确，并保持实现、测试和文档同步。

## 开发环境

- JDK 21 或更高版本；提交前至少使用 JDK 21 验证。
- 使用仓库内 Maven Wrapper，避免依赖本机 Maven 版本。
- Git，建议启用对 LF 行尾的支持；仓库的 `.gitattributes` 是最终规则。

## 开发流程

1. 从 `main` 创建功能分支。
2. 在对应模块修改代码，并为成功、失败和边界情况补测试。
3. 涉及公共 API、配置键或行为时，同步 Javadoc/相关文档/CHANGELOG。
4. 涉及 SQL 时补确定性的 SQL golden test；涉及数据库时补真实驱动集成测试。
5. 提交 Pull Request，说明动机、行为变化、风险和验证范围。

当前 `0.2.x` 开发阶段冻结 benchmark：不得添加或执行 benchmark 源码、脚本、结果或工作流。只有全部
`0.2.x` 里程碑完成并进入功能冻结后，才能由单独评审的 `0.3.0` 发布前性能验证任务重新建立和执行；旧结果及
旧门槛不得直接复用。

常用命令：

```bash
./mvnw verify
./mvnw -pl <module> -am verify
./mvnw -B -ntp clean verify -Ppostgres-contracts
./mvnw -Pcompliance -DskipTests verify
```

普通单元入口排除 PG 合同，不探测 Docker。选中 `postgres-contracts` 后优先使用显式
`SKIS_POSTGRES_URL/USER/PASSWORD`，没有 URL 时启动固定镜像 Testcontainers；错误配置和缺 Docker 直接失败。
每套件独立临时数据库，外部专用测试账号需 CREATEDB 权限。配置、镜像锁、清理及消费者入口见
[PostgreSQL 合同测试](docs/postgresql-contracts.md)；本轮接线仍待 CI 验证。

CI 在 Temurin 21/25 上另外启用 `static-analysis` profile，对生产源码与生成代码执行
Error Prone/NullAway 的 JSpecify 检查，并执行专用正反编译夹具。普通测试中的故意 null 输入仍由
javac 编译；独立消费者不需要继承 SKIS parent 或安装检查工具。配置、入口映射、精确抑制和
执行证据说明见 [静态检查](docs/static-analysis.md)。

```bash
./mvnw -B -ntp clean verify -Pstatic-analysis
```

Java 21 的单元、静态检查、架构与 PG 验证统一由 `engineering-gates` 的一次完整构建承担，
以固定 JaCoCo 0.8.15 汇集单元/PG 数据并执行逐模块分支覆盖率棘轮；Java 25 保留独立验证。
集成测试模块排在 Reactor 最后，使架构检查读取包含示例的完整产物。既有 Java 21 单元/集成检查名称
转发权威作业结果，依赖失败、跳过或取消时显式失败，不重复构建。首份真实基线已按 CI 工件写入，登记提交仍待 CI 认证；
检查名称映射、证据位置、提高门槛、可信历史比较及口径修订步骤见
[分支覆盖率](docs/branch-coverage.md)。不要手填通过率或用降低 JSON 门槛处理下降。
手动覆盖率运行必须填写默认分支当前 tip SHA；采集逻辑变化后，新增/变更门槛需要重新生成来源候选。

## 公共 API 变更

`1.0.0` 之前不执行跨版本 API/ABI 比较，公开 API、SPI、生成代码合同、包结构和模块可以按当前设计质量直接调整。
变更必须同步当前版本的生成器、运行时、调用方、测试、Javadoc 和使用文档，并由 CI 验证当前源码、功能、APT
生成装配、独立消费者及发布边界。不要为了兼容旧版本添加桥接类型、旧入口别名或新旧生成代码混用路径，也不要求
迁移说明、兼容报告或累计 API 差异清单。

`1.0.0` 起按语义化版本重新建立公开 API/SPI 的跨版本兼容门禁。

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
