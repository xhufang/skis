# PostgreSQL 合同测试环境

0.2.8 T05 已接线 Testcontainers、外部 URL、显式测试选择及 CI；PG 合同与独立消费者双路径已通过 CI，
Java 21/25 静态分析及权威覆盖率仍待修复后复验。
按项目要求，本地未编译、运行测试或启动容器；以下命令供 CI 或后续授权验证使用。

## 测试选择

普通 `./mvnw verify` 由 Surefire 执行单元、APT 和 H2 测试，排除 `postgres-contract` tag。
即使配置了 PG 环境变量，普通单元入口也不会初始化 PG 环境、探测 Docker 或拉取镜像。

显式选择真实 PostgreSQL 合同：

```bash
./mvnw -B -ntp clean verify -Ppostgres-contracts
```

Failsafe 明确包含 `**/*Test.java`，只选择 `postgres-contract`，默认单 fork、复用 JVM、禁止 PG 测试并行。
测试类使用 `@PostgresContract`，在 `.github/quality/postgresql-suites.json` 登记必需报告；
新增已标记套件自动参与执行。普通测试报告位于各模块 `target/surefire-reports`，PG 报告位于
`target/failsafe-reports`，同一套件不会执行两遍。

现有五个 PG 套件覆盖普通投影与 mutation/事务、Join/子查询、专用投影、JDBC 类型、员工/部门标量查询。
原混合数据库的员工/部门测试已拆为共享合同及 `SkisEmployeeDepartmentScalarH2ContractTest`、
`SkisEmployeeDepartmentScalarPostgreSqlContractTest`，两边均保留。
test-support 另有真实数据库隔离及成功/异常清理合同。

## 数据库配置

仅在 PG 套件被选中后读取配置：

| 配置 | 规则 |
| --- | --- |
| `SKIS_POSTGRES_URL` 未设置 | 使用 Testcontainers，要求可用 Docker；不会改用 H2 或跳过 |
| `SKIS_POSTGRES_URL` 已设置 | 必须是非空白、有效的 `jdbc:postgresql:` URL；始终优先于容器 |
| `SKIS_POSTGRES_USER` | 显式 URL 时必填且非空白 |
| `SKIS_POSTGRES_PASSWORD` | 显式 URL 时必填且非空白；禁止放入 URL 或 Maven 系统属性 |

外部 URL 不接受 URL 内嵌的 user/password 参数。格式错误、缺凭据、连接失败或服务端主版本不是 16 时直接失败，
不尝试容器或其他数据库。连接、socket 超时分别受测试数据源约束；默认日志只显示数据库来源、实际版本和镜像身份，
环境错误仅保留阶段及 SQLState，不展开可能含连接信息的原始异常链。

外部环境必须是专用测试 PostgreSQL 16，账号需要连接、`CREATEDB` 和清理自身数据库的权限。
现有生成实体使用固定的 `shelter`、`skis_types` 等 schema；为保持真实生成 SQL，采用每套件唯一临时数据库
`skis_contract_<UUID>` 隔离，而不在 JDBC 测试层重写 SQL 或改动生产 API。
JUnit 类级资源在套件结束删除其数据库，根级资源在整个测试 JVM 结束关闭容器；失败初始化也执行清理。
外部 PostgreSQL 进程及配置 URL 指向的数据库由调用方管理，测试只删除自己创建的临时数据库。

## 镜像与生命周期

唯一镜像登记为 [.github/quality/postgresql-image.json](../.github/quality/postgresql-image.json)。
2026-10-22 已只读访问 Docker 官方 registry，取得 `16.15-bookworm` manifest 的真实响应 digest：

```text
postgres:16.15-bookworm@sha256:d81f7b51697014367236d07ec58b54153fc28f828b72bf247fe5c7487520bcb0
```

test-support 打包该 JSON 为资源，Testcontainers 与 CI PostgreSQL service 使用同一锁。
Testcontainers 2.0.5 的镜像名解析不能直接接收 tag 与 digest 的组合，启动时使用等价的 `postgres@sha256:...`
引用，标签与 digest 的关联仍由 JSON 及 CI 检查；普通单元合同校验该引用满足实际 Testcontainers 解析规则。
镜像身份核实不等于数据库运行验证；实际服务版本由启动后的 JDBC 探针记录。
每个 PG 测试 JVM 的 JUnit 根 Store 持有一个环境；没有全局自动注册扩展或启动容器的静态初始化。
保留 Testcontainers 资源回收机制，不启用 `withReuse(true)` 或跨构建复用。
临时数据库清理使用 `DROP DATABASE ... WITH (FORCE)`，避免失败用例留下连接阻止回收。

## 独立消费者

四个消费者继续不继承 SKIS parent、不加入 Reactor，也不依赖 test-support、仓库测试模型或夹具。
三个非 PG 消费者沿用独立执行；PG 消费者由 `PostgresConsumerLauncher` 父进程选择环境并创建临时数据库，
只向 Maven 子进程环境传递 URL、账号和密码，正常退出、子进程失败和超时后均回收资源。
子进程上限为 15 分钟；凭据不写入命令行、classpath、消费者 POM 或工件。

启动器运行准备示例（在已有构建产物后执行）：

```bash
./mvnw -B -ntp -pl skis-test-support \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
  -DincludeScope=runtime -DoutputDirectory=target/consumer-launcher/lib
java -cp 'skis-test-support/target/classes:skis-test-support/target/consumer-launcher/lib/*' \
  io.skis.testsupport.PostgresConsumerLauncher -- \
  bash ./mvnw -B -ntp -f /absolute/copied-consumer/pom.xml verify
```

示例 classpath 分隔符适用于 Linux/macOS；Windows 使用 `;`，并将子命令改为适用的 Maven Wrapper。
运行时依赖供启动器使用，不加入消费者 classpath。

## CI 与验收

| 作业 | 数据库路径 | 必需证据 |
| --- | --- | --- |
| engineering-gates，Java 21 | Testcontainers | 同一次构建的单元/PG 覆盖率、必需 PG 报告、真实来源/版本及清理 |
| unit-tests，Java 25 | 不选择 PG，故意配置不可用 URL/Docker 地址 | 普通单元/H2 成功，Surefire 无 PG 报告 |
| postgresql-contracts，Java 25 | Testcontainers | 同一组必需 PG 套件全部执行 |
| postgresql-contracts，Java 21 | 显式 URL，固定镜像 service | 同一组 PG 合同及外部来源记录 |
| postgresql-consumer，Java 21 双路径矩阵 | 容器 / 显式 URL | 独立消费者报告、子进程失败及资源清理 |

`verify-postgres-selection.ps1` 检查镜像锁、套件登记、禁止静默跳过及单元/PG 分离；
`verify-postgres-reports.ps1` 要求登记报告和所有实际 PG 报告非空、零失败/错误/跳过，并核对预期数据库来源。
`verify-postgres-failures.ps1` 验证空白/错误 URL、缺凭据、不可达连接、无 Docker 与消费者失败。
无 Docker 负例在网络隔离且没有挂载 Docker socket 的 Java 容器内执行，避免仅修改 `DOCKER_HOST` 后仍回退本机 socket。
CI 还检查标记为 `io.skis.postgres-contract=true` 的数据库容器没有残留。

权威覆盖率命令增加 `postgres-contracts`，继续合并同一次构建的 Surefire/Failsafe fork 数据，不修改生产模块分母或历史门槛。
采集工作流与脚本已变化；如当前待认证基线的来源版本不匹配，须从新 CI 工件重新登记真实候选，不能伪造新来源或降低门槛。
[CI 39735096562](https://github.com/xhufang/skis/actions/runs/39735096562) 已通过 Java 25 容器及 Java 21 外部 URL 的
PG 合同、独立消费者双路径、配置/无 Docker/消费者失败检查与容器清理检查。
Java 21 权威作业和 Java 25 单元作业均被消费者启动器的 NullAway 编译错误阻断；已将命令参数的数组复制改为列表切片，
修复尚未本地编译运行，静态分析、架构门禁及权威覆盖率须在后续 CI 复验，T05 尚未关闭。
