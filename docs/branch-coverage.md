# 分支覆盖率与逐模块棘轮

0.2.8 T04 已接入构建配置、采集脚本、失败夹具和 CI。当前尚未取得首份权威报告，
`.github/quality/branch-baseline.json` 明确记录 `awaiting-first-authoritative-run`，没有预填覆盖率。
实现接线不表示执行验证通过；首次基线登记及全部夹具的执行证据仍待 CI。

## 权威环境与统计范围

唯一权威作业为 CI 的 `engineering-gates`，使用 Temurin 21、release 21 和 JaCoCo 0.8.15。
Java 25 作业继续验证正确性，不向权威覆盖率汇入执行数据。

作业在同一干净检出中执行一次 `clean verify`，同时启用 `static-analysis,coverage,architecture`。
根 Reactor 将 `skis-integration-tests` 放在最后，包含 H2 示例在内的全部生产/夹具产物先完成构建，
架构测试随后在同一次顺序构建中读取完整字节码；不另启动一轮 Reactor 测试或重编译来补齐产物。
T05 尚未实现，因此当前复用 PostgreSQL 16 service 与现有 Surefire PG 套件，显式要求五个真实 PG 套件
非空、成功且无跳过；T05 的 tag/profile、Testcontainers 和外部 URL 治理仍归 T05。
T05 切换后须同步命令登记、报告验证目录和执行套件，保持同一次构建的单元/PG 数据合并。

Java 21 单元、APT/nullness、名义类型、架构与 PG 检查统一在 `engineering-gates` 执行，
保留专用套件非空/无跳过核验和 Enforcer 正反例；Java 25 的 `unit-tests` 独立执行
`clean verify -Pstatic-analysis,architecture`。原 Java 21 单元和 PG 作业不再重复构建。

| 检查名称 | 实际执行与证据 |
| --- | --- |
| Engineering gates (Java 21 coverage) | 完整 Java 21 构建、PG、工程门禁及覆盖率；报告在 `branch-coverage-java-21-<runId>-<attempt>` 和 `engineering-gates-build-<runId>-<attempt>` |
| Unit tests (Java 21) | 保留既有 required check 名称，转发 `engineering-gates` 结果，不重复构建 |
| Integration test module | 保留既有 required check 名称，转发 `engineering-gates` 结果，不重复启动 PG |
| Unit tests (Java 25) | 独立 Java 25 构建及静态/架构检查；报告在 `unit-test-reports-java-25` |

两个结果转发作业始终执行，仅当依赖作业成功时通过；依赖失败、跳过或取消时显式失败，
不以跳过作业冒充成功。转发结果包含覆盖率门禁，首次待登记也会失败。远端分支保护无需改名，未自动修改其配置。
Java 21 的 nullness 诊断、Surefire/Failsafe、Enforcer 日志和政策 JSON 随 `engineering-gates-build` 工件保存；
不再单独生成 `unit-test-reports-java-21` 或 `integration-test-reports`。

`.github/quality/coverage-policy.json` 列出 15 个生产 Jar，并与模块政策中的 production/processor 清单核对。
annotations 合法零 branch 时记 N/A，仍须有实际类清单；其他模块零分母失败。
parent、BOM、测试支持、测试模型、集成测试、示例和空 benchmark 不进入生产分母。
测试及集成模块的执行数据仍参与合并，从而计入其对依赖生产模块的覆盖。

## 数据完整性

- 每个复用测试 fork 使用独立的 `unit-N.exec` 或 `postgres-N.exec`；会话记录提交、模块与 fork。
  Surefire/Failsafe 的 agent 参数与现有 argLine、静态检查器 JVM 参数合并。
- 采集所有 Reactor 测试模块的 exec，以同版 CLI 合并，再针对各生产模块本次 `target/classes` 分别生成 XML/HTML。
- 核对干净检出、比较提交、构建参数、文件时间、release 21 class 头、exec 会话及 JaCoCo class ID。
  缺报告、缺执行数据、关键模块无执行记录、类冲突、class ID 不一致和模块遗漏均失败。
- 手写生产类及内部/合成类进入统计。生成代码只根据实际 `target/generated-sources`、编译器 `createdFiles.lst`
  和 class 的 `SourceFile` 逐类登记排除；不按包或 Pro、Meta 等后缀排除。
  排除清单写入模块 `classes.json` 与统计口径，fixture 模块整体按角色排除。
- 两份工件上传都必须成功。缺失或上传失败的报告不构成可登记证据。

## 首次登记与抬线

1. 将当前改动提交并由 CI 执行。首次正常采集会上传报告及 `branch-baseline.candidate.json`，
   然后因正式基线尚未登记而让棘轮检查失败；这不是已经通过覆盖率验收。
2. 下载本次 `branch-coverage-java-21-<runId>-<attempt>` 工件，审阅每模块 XML/HTML、计数与 N/A。
3. 用工件中的候选替换 `.github/quality/branch-baseline.json`，随变更提交。脚本不自动写回源码或提交。
   首次 `registration` 保存报告提交、仓库、作业编号、尝试次数及工件名称；可信比较基线激活后不可改写或重新 bootstrap。
4. 后续 CI 先比较可信历史，再通过 GitHub Actions API 核对新增/变更门槛的实际仓库、工作流 ID/路径、
   运行编号、尝试次数、允许事件和提交；核对成功采集/上传步骤、工件归属以及 XML 校验和和计数。
   PR 同时核对实际测试的合并提交及其 base/head 父提交，避免将 PR 源分支 SHA 与合并 SHA 混为一谈。
   普通 PR token 只需 `contents:read`、`actions:read`；来源工件过期或不可读取时不能用手填计数代替。
5. 同口径报告提高时，审阅并提交新的候选。可信比较基线中未变化的门槛保留原证据，避免每次运行都重登记。
   若报告下降，须先修复覆盖缺口；直接应用较低候选仍会被历史基线拒绝。

工件中的会话声明还须与 GitHub 保存的源码树核对：`.github/workflows/ci.yml`、
`.github/scripts/branch_coverage.py` 和 `.github/scripts/verify-test-reports.ps1` 的 Git blob ID
必须与当前检出的已提交版本一致，不能仅以同名作业/步骤或工件自报提交作为来源证明。
修改上述采集逻辑后，新增或变更门槛须重新取得候选；已经登记且未变更的模块行继续保留原证据。
候选的登记记录、口径历史和可保留模块行只取自会话固定的 `previous`，不把当前 PR 内尚待登记的候选当作可信历史。
因此同一 PR 重新采集时，待登记的抬线行使用本次工件；首次激活前的 `registration` 也随本次来源刷新。
首次未登记导致的失败作业仍可提供候选，但整个运行必须结束，采集和两次上传步骤均须成功。

门槛使用整数 `floor(covered * 10000 / (covered + missed))`，一个单位为 0.01 个百分点。
policy 的 `precision` 必须与上述规范字符串完全一致；缺失、不同精度及仅改变公式文字均拒绝。
采集、当前比较及历史比较都校验这一合同，不能通过改描述并提升 revision 来重建较低门槛。
展示百分比不参与判断。每个适用模块必须满足当前门槛，报告提高到下一可表示整数时必须同步抬线。
不会通过全仓平均覆盖率抵消某个模块的下降。

| 事件 | 可信比较来源 |
| --- | --- |
| pull_request | 目标分支 base SHA |
| push | 事件的 before SHA |
| workflow_dispatch | 必填的 coverage_baseline_commit，必须等于登记会话时通过 GitHub API 取得的默认分支当前 tip SHA |

缺比较提交直接失败，不回退为只检查 HEAD JSON。抬线后回退即使仍高于初始门槛也失败；
同一变更改低 JSON 门槛、应抬未抬或重置为未登记状态都不能放行。
首次接入时，目标提交尚无基线文件仅允许启动首次报告登记流程，仍不能让未登记门禁绿色通过。
PR/push 的比较提交还须与 GitHub 事件原始载荷的 base/before 一致；手动运行不能选择旧阈值提交或
登记前提交重新 bootstrap。默认分支在填写输入后已前进时，本次运行失败，使用最新 tip 重跑。
比较提交在会话登记时固定，构建期间分支继续前进不会改变本次比较口径。

## 统计口径修订

新增/拆分/删除统计模块、改变 N/A 分类或生成排除清单时，提升 policy 的 revision，取得新权威报告，
并填写候选新增的 `scopeChanges` 记录：旧 scope、旧模块全部门槛及证据、逐模块映射、处置理由和新模块清单。
既有记录只能追加。新模块数值由对应真实工件验证，修订记录保留重建前事实。
这类变更按重建基线登记，不视为同口径通过；候选中的空理由必须在提交前补齐。
当前权威工具链及精度固定；历史门槛按模块核对，仅实际改变 N/A 分类或生成排除的模块可以重建数值；
其余保留模块继续遵守旧门槛，即使同一修订新增/删除了其他模块也不能降线。
映射中把未变更模块写成“删除后新增”也不能解除其门槛；未变更模块的同阈值候选保留既有证据。
未来调整统计工具或算法须同步实现与工程合同并单独评审，不能只修改 policy 的描述字段。

## 验证入口与剩余证据

`.github/scripts/tests/test_branch_coverage.py` 由权威作业先执行，覆盖下降、改低门槛、提高后回退、
应抬未抬、缺比较基线、缺模块/报告/数据、零分母、会话及 class ID 错配、生成排除、上传失败和口径历史。
另覆盖跨模块口径变更降线、映射伪装、手动旧比较提交、同名伪工作流、实际提交/尝试错配、
采集逻辑版本错配、工件自报信息与 XML 计数篡改，以及 PR 合并提交的父提交错配。
成功夹具使用内存 ZIP 和模拟 Actions API，经过候选生成、文件登记和完整 `check` 认证，
同时验证同一来源只下载一次；不调用真实网络、Maven 或 Java。
新增采集链路夹具直接执行 `collect → candidate → check`：使用临时 Reactor 目录、Surefire/Failsafe 报告及模拟 CLI，
验证单元与 PG 数据合并、模块 XML/HTML 输出、未登记失败和来源认证；缺单元/PG fork、缺测试报告及非法精度均拒绝生成候选。
另验证同一 PR 的抬线候选和首次登记候选重新采集时刷新来源，已登记且未变更的证据继续保留。夹具仍待 CI 执行。
Java 失败路径沿现有真实入口补充：

| 套件 | 验证内容 |
| --- | --- |
| ExecutionListenerDispatcherTest | 各阶段监听抛异常时后续监听、原始诊断与完成去重 |
| JdbcExecutorTest | unchecked 解码失败、SQL 资源关闭失败与 unchecked release 失败的主次及清理顺序 |
| ProjectionPlanCacheTest | 进入前已中断和耗尽许可后等待期间被中断两条路径，核对 interrupt 保留、原编译者及其缓存结果不受影响、后续重试/命中；另验证空编译结果恢复。三项恢复测试设 10 秒超时，保留已有 miss/clear/失效并发合同 |
| CompilationPipelineTest | lowering 引入作用域外列时，后校验在渲染前拒绝 |

本轮仅做静态核对，未编译运行。T04 尚缺首份真实基线登记、指定套件/负例运行结果和完整报告上传证据；
取得证据前保留“已接线、待 CI 验证”，不勾选退出标准。本任务不执行 benchmark，也不设置耗时、吞吐或分配量门槛。

工具依据：[JaCoCo agent 与 argLine](https://www.jacoco.org/jacoco/trunk/doc/prepare-agent-mojo.html)、
[CLI 的 merge/report/classinfo/execinfo](https://www.jacoco.org/jacoco/trunk/doc/cli.html)、
[GitHub 工作流运行与尝试](https://docs.github.com/en/rest/actions/workflow-runs#get-a-workflow-run-attempt)、
[GitHub 工件归属](https://docs.github.com/en/rest/actions/artifacts#list-workflow-run-artifacts)。
