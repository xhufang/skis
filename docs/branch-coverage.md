# 分支覆盖率与逐模块棘轮

0.2.8 T04 已接入构建配置、采集脚本、失败夹具和 CI。2026-10-22 已取得权威报告，
当前 `.github/quality/branch-baseline.json` 采用 CI 39738254300 的候选原文，状态为 `active`；没有手填覆盖率。
该候选将 skis-query 门槛从 7314（73.14%）提升至 7337（73.37%），保留首次登记及其他 14 个模块的门槛和证据。
更新后的登记尚待提交后 CI 的来源认证与当前测量比较，不能据本地文件状态宣称门禁已经全部通过。

本次抬线报告来自 [CI 39738254300 的权威作业](https://github.com/xhufang/skis/actions/runs/39738254300/job/119688979310)，
PR head 为 `ae4355c6e40e318298215fef144193b794acbc4f`，实际测试合并提交为
`b14cc2099455e566549b44776a7cd27660097695`，比较基线为 `529d939cf69854d8d981011c25c3de71d5ee1e59`。
[覆盖率工件](https://github.com/xhufang/skis/actions/runs/39738254300/artifacts/12408620651)名为
`branch-coverage-java-21-39738254300-1`，归档 SHA-256 为
`9faa00068675841165f9a8160f85e8f88d9a3497d2d4f17447033fafa4fa9e3f`。
已核对工件哈希、GitHub 合并提交/父提交、七项采集源码 Git blob ID、15 模块 XML 哈希/计数/类数、HTML 和整数门槛。
query 的 covered/missed 从 1291/474 变为 1295/470；生产统计口径、生成排除和各模块类数未变化。
本轮 Java 21/25 构建、45 项 Python 夹具、必需报告、Enforcer、PG 双路径及独立消费者通过，采集及两份工件上传成功。
门禁在应抬未抬时按预期失败，尚未执行新增抬线来源认证；可信 `previous` 已为 active，首次登记和其余模块行按规则保留。

前次抬线报告来自 [CI 39718331626 的权威作业](https://github.com/xhufang/skis/actions/runs/39718331626/job/119625709762)，
PR head 为 `4aeaddeb36d5e7ee8188d767a17fcdf4d4ce5f10`，实际测试合并提交为
`0101358fea836d64cb65aa4492e7254eba8521c1`，比较基线为 `f46c957084be7c7ec3486139f06d812a6ffb805b`。
[覆盖率工件](https://github.com/xhufang/skis/actions/runs/39718331626/artifacts/12400570401)名为
`branch-coverage-java-21-39718331626-1`，归档 SHA-256 为
`aa38b09b530e08cdfc2f4018ce16bc7566b1455d45d03d2d9b78cafffb845a6b`。
已核对归档哈希、GitHub 合并提交/父提交、采集源码版本、会话输入哈希及 15 模块 XML 哈希/计数/类数、HTML 与整数门槛。
类清单及 class ID 与首份报告相同，统计口径和生成排除未变化，没有模块降线。
该轮采集时 main 尚无已激活基线，会话 `previous` 为 null；因此当时的候选按既有规则刷新首次 `registration` 和全部模块证据，
不能把当前 PR 中尚未通过认证的登记当成可信历史来保留来源。

最初登记报告来自 [CI 39715770444 的权威作业](https://github.com/xhufang/skis/actions/runs/39715770444/job/119617391253)，
PR head 为 `6bbac44a9242036133da9028135ac9f48f64cbe5`，实际测试合并提交为
`b83d71a185cd92f74fb2b0627f00502f3d760f00`，比较基线为 `f46c957084be7c7ec3486139f06d812a6ffb805b`。
[覆盖率工件](https://github.com/xhufang/skis/actions/runs/39715770444/artifacts/12398449932)名为
`branch-coverage-java-21-39715770444-1`，归档 SHA-256 为
`3b8427f6482501b79eb9a76add9c4a7f0fa08ee5157637f1b8925eb7f17aeab1`。
本地已核对归档哈希、GitHub 提交/父提交与采集源码版本、15 模块 XML 哈希/计数/类数、HTML 存在性及整数门槛；
annotations 的 10 个类确无 branch，记 N/A，其余 14 个模块均有真实分母，生成代码排除均为空。

首次登记验证的 [CI 39717124424](https://github.com/xhufang/skis/actions/runs/39717124424/job/119621796314)
再次通过构建、必需测试核验、Enforcer 和采集/上传，但在来源认证之前被模块门槛比较拒绝。
该轮 PR head 为 `92d1785115bf300cc35292ff6e17e27c8f2dfe33`，实际测试提交为
`099c13fa3d55db6934009e7962607f1cd87c7499`；与首份报告的 15 模块类清单及 JaCoCo class ID 完全相同。
对比[该轮工件](https://github.com/xhufang/skis/actions/runs/39717124424/artifacts/12399776444)定位到并发时序与 Map 遍历顺序引起的分支覆盖波动：

| 模块 | 首份 covered/missed | 登记验证 covered/missed | 整数门槛变化 | 具体差异 |
| --- | --- | --- | --- | --- |
| skis-core | 285/147 | 284/148 | 6597 → 6574 | rejectReentrantCall 中其他线程回调活跃、当前线程未重入的分支少覆盖一次 |
| skis-query | 1289/476 | 1286/479 | 7303 → 7286 | 准入后加入已有编译任务的四个分支缺失，淘汰比较多覆盖一个分支，净减少三个 |

已补充三个测试：latch 固定其他线程回调活跃窗口；在准入锁排队后注册同 key 编译任务，再释放锁让等待者加入；
读取实际 Map 遍历顺序后只调整访问时间，固定淘汰扫描中更新/保留候选的两种结果。
同步和反射均只在测试侧，不修改生产热路径。上述测试已通过 CI 39718331626 的 Java 21/25 构建，未在本地编译运行。
该轮 core 恢复为 285/147，query 提高为 1291/474；相较首份报告，ProjectionPlanCache.evictOldest
和 QueryPlanKey.equals 各多覆盖一个分支。门禁按预期在 query 应抬未抬时失败，尚未执行来源认证；
已审阅本轮工件并采用真实候选抬线，更新后的登记仍待 CI 验证。

## 权威环境与统计范围

唯一权威作业为 CI 的 `engineering-gates`，使用 Temurin 21、release 21 和 JaCoCo 0.8.15。
Java 25 作业继续验证正确性，不向权威覆盖率汇入执行数据。
运行 JDK 由 `setup-java`、会话登记时的 `java -version` 与 Enforcer 核验；命令行仅用
`-Dmaven.compiler.release=21` 固定产物版本，不传 `-Djava.version=21`，避免覆盖真实 JDK 更新版本并误触最低版本门禁。

作业在同一干净检出中执行一次 `clean verify`，同时启用 `static-analysis,coverage,architecture,postgres-contracts`。
根 Reactor 将 `skis-integration-tests` 放在最后，包含 H2 示例在内的全部生产/夹具产物先完成构建，
架构测试随后在同一次顺序构建中读取完整字节码；不另启动一轮 Reactor 测试或重编译来补齐产物。
T05 已接线 Testcontainers 和显式 PG 选择，权威作业改用容器，并在 Failsafe 中要求五个既有真实 PG 套件及环境清理套件
非空、成功且无跳过，同时核对实际环境来源；外部 URL 路径另有 CI 作业，接线待验证。
命令登记、报告目录和套件清单已随 T05 同步，保持同一次构建的单元/PG 数据合并。工作流及采集脚本版本变化后，现有待认证来源如不匹配，须由新 CI 工件重新生成真实候选，保留历史门槛。

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
  tag 筛选可能启动没有选中测试的 fork；仅当该模块对应阶段没有测试报告时，允许 exec 仅含本次会话。
  有报告的阶段仍要求类记录；所有 fork 仍核对文件和会话，合并数据及适用生产模块仍须有真实执行记录。
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
另验证同一 PR 的抬线候选和首次登记候选重新采集时刷新来源，已登记且未变更的证据继续保留。
43 项 Python 夹具已通过 CI 39718331626。
Java 失败路径沿现有真实入口补充：

| 套件 | 验证内容 |
| --- | --- |
| ExecutionListenerDispatcherTest | 各阶段监听抛异常时后续监听、原始诊断与完成去重；latch 固定其他线程回调活跃窗口，验证独立调用不被误判为重入 |
| JdbcExecutorTest | unchecked 解码失败、SQL 资源关闭失败与 unchecked release 失败的主次及清理顺序 |
| ProjectionPlanCacheTest | 进入前及等待期间中断、空编译结果恢复；锁排队固定等待者加入新注册任务，验证同一编译结果共享；根据 Map 实际遍历顺序固定访问时间并验证淘汰。三项恢复测试及新准入并发测试设 10 秒超时，保留已有 miss/clear/失效合同 |
| CompilationPipelineTest | lowering 引入作用域外列时，后校验在渲染前拒绝 |

本轮 CI 已执行并通过 43 项 Python 门禁夹具、Java 21 主构建、必需 PG/失败路径/nullness/名义类型报告核验和 Enforcer 正反例，
覆盖率采集及两份权威证据上传均成功；[Java 25 作业](https://github.com/xhufang/skis/actions/runs/39718331626/job/119625709474)
也全部成功。权威作业在 query 应抬未抬时按预期失败，两个旧检查名称同步转发失败。
本地仅进行工件数据核对和候选登记，未编译或运行项目代码；T04 仍待登记提交的 CI 认证及历史棘轮验收，
不勾选完整退出标准。本任务不执行 benchmark，也不设置耗时、吞吐或分配量门槛。

工具依据：[JaCoCo agent 与 argLine](https://www.jacoco.org/jacoco/trunk/doc/prepare-agent-mojo.html)、
[CLI 的 merge/report/classinfo/execinfo](https://www.jacoco.org/jacoco/trunk/doc/cli.html)、
[GitHub 工作流运行与尝试](https://docs.github.com/en/rest/actions/workflow-runs#get-a-workflow-run-attempt)、
[GitHub 工件归属](https://docs.github.com/en/rest/actions/artifacts#list-workflow-run-artifacts)。
