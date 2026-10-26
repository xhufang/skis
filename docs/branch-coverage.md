# 分支覆盖率与逐模块棘轮

0.2.8 T04 已接入构建配置、采集脚本、失败夹具和 CI。当前 `.github/quality/branch-baseline.json` 状态为 `active`。
T10 已核实提交 `9dbb9324411ff2f5697aa85ed29d76b2bb75e692` 的
[Java 21 权威作业](https://github.com/xhufang/skis/actions/runs/39957930561/job/120428327026)：
45 项脚本夹具、15 模块覆盖采集、历史棘轮及来源认证全部通过；两条完整 Java 构建的报告守卫各 53 项通过。
工件 `branch-coverage-java-21-39957930561-1`（ID `12503487449`）归档 SHA-256 为
`2876c1755d441238047f8eb8eb47e5c56095cf3c32f3fce39717f885575e6c82`。
已下载并核对摘要，候选的全部模块行及首次登记与当前基线一致，无需抬线、降线或改变统计口径。
本轮只读取 CI 工件与静态核对文档，未在本地编译运行；下述记录保留各历史失败与抬线时点。


## PR #90：聚合缓存覆盖率抬线与回退补证（待新 CI）

[CI 40264559294 的权威作业](https://github.com/xhufang/skis/actions/runs/40264559294/job/121383976048?pr=90)
已通过 Java 21 完整构建、必需回归、覆盖采集和两项证据上传；上轮 scalar/派生表缓存夹具的 `SKISQ026` 已修复。
Java 25、PostgreSQL 外部 URL/容器及独立消费者作业均通过。权威作业首个失败为 skis-sql-ast 应抬未抬，
完整候选另显示 skis-query 由 77.86% 降至 77.79%；不能以抬线登记代替回退补证。

PR head 为 `5497a030f9183a023875863b82002f7dee494501`，实际测试合并提交为
`ae95a60fb71243e3c80d1b493f8cdb8df26cb0bf`，比较 base 为 `9f1931855d098a19d88c27639797c0aafe14e62e`。
已核对工件与运行归属、合并父提交、七项采集输入 blob、policy/baseline 原始摘要、可信 comparison 基线，
以及 15 模块 XML 哈希、逐类/汇总分支计数、类清单和 HTML 报告。

[工件 12625153762](https://github.com/xhufang/skis/actions/runs/40264559294/artifacts/12625153762)
为 `branch-coverage-java-21-40264559294-1`，归档 SHA-256 为
`417a8d101427fc6e288091d4cb1d0c77b71aff8d44d0ec1efdb6a8205ada2283`，与 GitHub 元数据一致。
仅原样采用以下两条真实提升行及来源；其余 13 行、首次登记、统计口径及生成排除保持不变。

| 模块 | 原门槛 | 本轮 covered/missed | 类数 | 登记门槛 |
| --- | ---: | ---: | ---: | ---: |
| skis-sql-ast | 82.67% | 1506/306 | 103 | 83.11% |
| skis-dialect-api | 80.92% | 1110/258 | 39 | 81.14% |

保留 skis-query 的 77.86% 门槛及原来源，未采用本轮 `1895/541`、187 类的降线候选。
SharedQueryPlanRoutingTest 新增复用原阶段时隐藏排序列必须进入独立最终结构、裁剪来源仍可触发 ordered 计划失效，
以及 DISTINCT 未选择排序列在共享查找前拒绝的合同源码，并验证同结构异值的绑定、L1 命中和 lowering/渲染次数。
新增测试的实际分支计数和门槛提升以新 CI 为准，不预测或手填。

本轮仅下载并静态核对工件、登记提升行及补测试源码，未本地编译运行；来源认证、query 回退补证及完整门禁待新 CI。
取证保存在本地 `target/ci-coverage-40264559294/`，T08 保持“已实现/待 CI”。

## PR #89：结果身份与分页覆盖率抬线（登记后待 CI 认证）

[CI 40197870071 的权威作业](https://github.com/xhufang/skis/actions/runs/40197870071/job/121182551488?pr=89)
已通过 Java 21 完整构建、85 个必需回归套件、覆盖采集及两项证据上传。
上轮三个续页参数测试已通过；Java 25、PostgreSQL 外部 URL/容器、独立消费者及 Gradle 作业均通过。
唯一原始失败为 skis-sql-ast 覆盖率应抬未抬，Java 21 单元/集成名称转发该权威失败。
完整候选的 15 个模块均无覆盖率回退。

PR head 为 `33869f4880a3c9182a7f5daa64d4fe0b8e7fa17d`，实际测试合并提交为
`be7f4b40d02e9da7c8fa7d1cd398b2bf9fbb4472`，比较 base 为
`707874d564610e4dcf266321e579fa5c5e6f0dbc`。已核对工件归属、七项采集输入 blob ID、
提交内 policy/baseline 字节摘要、可信 comparison 基线、15 模块 XML 哈希、分支计数与类清单，以及 HTML 报告存在性。

[工件 12600204891](https://github.com/xhufang/skis/actions/runs/40197870071/artifacts/12600204891)
为 `branch-coverage-java-21-40197870071-1`，归档 SHA-256 为
`525f546aae29184627e9129bd8bdd65bd8203a880dd16b63f8b154aace142c93`。
原样采用完整真实候选，提升以下四项门槛；其余 11 行、首次登记、统计口径及口径历史与当前基线一致。

| 模块 | 原门槛 | 本轮 covered/missed | 类数 | 登记门槛 |
| --- | ---: | ---: | ---: | ---: |
| skis-sql-ast | 81.34% | 1498/314 | 103 | 82.67% |
| skis-dialect-api | 80.55% | 1107/261 | 39 | 80.92% |
| skis-dialect-support | 89.07% | 263/30 | 8 | 89.76% |
| skis-query | 76.11% | 1885/536 | 186 | 77.86% |

对应 XML SHA-256：

- skis-sql-ast：`567b56c83c758f485b44eff3d050b2de7016416b628008e689d71754b20499b9`。
- skis-dialect-api：`4fea202e5565cc3d68add8c1a86c3c1d9599ac432e954e93825d0bdafaf31aaf`。
- skis-dialect-support：`ce3c23138a73d140339518b7658e27d066a316b443de7594fa1446bee873fda4`。
- skis-query：`8a9282f8d0c9dc294d339b8829a806fda86bd16e30260f58e3d2fc986d87e344`。

本轮只登记候选并同步已有验证记录，未修改生产代码或测试，未本地编译运行。
新登记来源认证及完整工程门禁待下一次 CI，T07 保持已实现/待 CI。
工件保存在本地 `target/ci-40197870071-review/`。

## PR #88：聚合输入映射覆盖率抬线与补证（登记后待 CI 认证）

### CI 40167945334：回退补证通过，登记完整候选

[CI 40167945334 的权威作业](https://github.com/xhufang/skis/actions/runs/40167945334/job/121092726512?pr=88)
已通过 Java 21 完整构建、架构检查、83 个必需回归套件、覆盖采集及两项证据上传。
AggregateInputMappingTest 的 21 项及 CompilationPipelineTest 的 22 项均通过且零跳过；
上轮新增的 5 个方法已执行通过。Java 25、PG 外部 URL/容器、独立消费者及 Gradle 作业均通过。
本轮唯一原始失败为 skis-dialect-api 覆盖率应抬未抬，Java 21 单元/集成名称转发该权威失败。
15 模块均无覆盖率回退，dialect-api/query 的前次回退已消除。

PR head 为 `40e447095d4f5336420ad569dcc9bf2ba1a26094`，实际测试合并提交为
`1e0d5583008b41f1e8bd1715b0488a4c2ce6f218`，比较 base 为
`204a05f6a971f6515d04bcda090b6ef407aa1c49`。已核对合并父提交、七项采集输入 blob ID、
提交内 policy/baseline 原始字节摘要、可信 comparison 基线、15 模块 XML 哈希、逐类/汇总分支计数、类清单及 HTML 报告存在性。

[工件 12588829880](https://github.com/xhufang/skis/actions/runs/40167945334/artifacts/12588829880)
为 `branch-coverage-java-21-40167945334-1`，归档 SHA-256 为
`360b6526776978d9589914af1c34d1c10d352cd96a13aed186c7375d9fdce546`。
原样采用完整真实候选，提升以下两项门槛，并按同一 PR 重新采集规则刷新 sql-ast/mapping 两条尚待认证的来源。
其余 11 行、首次登记、统计口径、口径历史及生成排除保持不变。

| 模块 | 原门槛 | 本轮 covered/missed | 类数 | 登记门槛 |
| --- | ---: | ---: | ---: | ---: |
| skis-sql-ast | 81.34% | 1256/288 | 100 | 81.34% |
| skis-dialect-api | 80.49% | 1081/261 | 39 | 80.55% |
| skis-mapping | 88.73% | 126/16 | 22 | 88.73% |
| skis-query | 75.91% | 1775/557 | 186 | 76.11% |

对应 XML SHA-256：

- skis-sql-ast：`ffed2abda5b69649c7f100806f5c1c8f50cb6e1782f89d891c80190a2e68ba45`。
- skis-dialect-api：`5e82fd01bae801c7c77963fd81a960e27f5062128e8003f3ece12d505759bf6f`。
- skis-mapping：`129c304014e3fea53e489ed5a9e2865eb11d15be0509b47786f3b19f1fcaf70f`。
- skis-query：`e8ec4fa4a578e1fa4af62036d4e583398fb8d96dabf59e6743b1dfb68563ad5d`。

本轮仅下载并静态核对工件、登记候选和同步证据，未修改生产代码或测试，未本地编译运行。
登记提交的来源认证及完整工程门禁仍待新 CI，T06 保持已实现/待 CI。
取证保存在本地 `target/ci-coverage-40167945334/` 的归档、measurement、候选及 `verified-modules.json`。

### CI 40166923586：首次候选与覆盖缺口

[CI 40166923586 的权威作业](https://github.com/xhufang/skis/actions/runs/40166923586/job/121089778804?pr=88)
已通过 Java 21 完整构建、架构检查、83 个必需回归套件、覆盖采集及两项证据上传。
AggregateInputMappingTest 的 17 项及 ArchitectureContractTest 的 10 项均通过且零跳过；
Java 25、PG 外部 URL/容器、独立消费者及 Gradle 作业均通过。
本轮唯一原始失败为 skis-sql-ast 覆盖率应抬未抬，Java 21 单元/集成名称转发该权威失败。

PR head 为 `203e3c2c26177c3a7cf9a85628d2a8002591aa25`，实际测试合并提交为
`c99cf918b8da33e0dce66cdf5a5c5a314daddc7e`，比较 base 为
`204a05f6a971f6515d04bcda090b6ef407aa1c49`。已核对合并父提交、七项采集输入 blob ID、
提交内 policy/baseline 的原始字节摘要、15 模块 XML 哈希、逐类/汇总分支计数、类清单及 HTML 报告存在性。

[工件 12588384114](https://github.com/xhufang/skis/actions/runs/40166923586/artifacts/12588384114)
为 `branch-coverage-java-21-40166923586-1`，归档 SHA-256 为
`82d0a15cc492da2826ed3280eb5fe6974cb3f261210cd2a9216a04a039f4dcd0`。
仅从真实候选原样采用下列两条提升行及其来源；其余 13 行、首次登记、统计口径和生成排除不变。

| 模块 | 原门槛 | 本轮 covered/missed | 类数 | 登记门槛 |
| --- | ---: | ---: | ---: | ---: |
| skis-sql-ast | 81.02% | 1256/288 | 100 | 81.34% |
| skis-mapping | 88.02% | 126/16 | 22 | 88.73% |

对应 XML SHA-256：

- skis-sql-ast：`63227dd8f7347340b965d5b1fff672de65b71ea71ada1ac29ccbc5fa1bb5cde9`。
- skis-mapping：`e164125be8e0adcacf59d87cc5e86f69cf5bada011eb1ed4c08bf1aaaacd6f8d`。

完整候选还暴露以下回退，未采用其降线行，不能把首个失败修复等同于棘轮通过：

| 模块 | 保留门槛 | 本轮 covered/missed | 本轮覆盖率 |
| --- | ---: | ---: | ---: |
| skis-dialect-api | 80.49% | 1080/262 | 80.47% |
| skis-query | 75.91% | 1759/573 | 75.42% |

CompilationPipelineTest 新增无查询分析的 mutation、普通 SELECT、分组、本块及嵌套聚合标志合同；
AggregateInputMappingTest 新增内置组合表达式、派生/标量来源正例、后续操作数中自定义 Codec 拒绝，
以及 HAVING 的 BETWEEN/NOT/LIKE/IN 子查询遍历正反例。上述新增源码未本地编译运行，
修复后的计数、后续应抬门槛和本次登记的来源认证均待新 CI；不预测或手填新计数。
取证保存在本地 `target/ci-coverage-40166923586/` 的归档、measurement、候选及 `verified-modules.json`。

## PR #87：聚合方言 lowering 覆盖率抬线（登记后待 CI 认证）

[CI 40142830953](https://github.com/xhufang/skis/actions/runs/40142830953/job/121017961102?pr=87)
在测试修复后首次通过全部测试作业，权威棘轮因 skis-sql-ast 应抬未抬失败；核对候选另见 skis-query
由 75.19% 降至 74.60%（1595/543），T05 新增的空成员本块聚合查找分支大多未执行。已补充 query 回归覆盖各条件节点、
左右操作数、组合表达式操作数、重复节点及非表达式值，未降低任何门槛。

[CI 40144002225 的权威作业](https://github.com/xhufang/skis/actions/runs/40144002225/job/121021399961?pr=87)
已通过 Java 21 完整构建（含 static-analysis、nullness、架构、Enforcer 及真实 PG 合同）、必需回归、
覆盖采集与工件上传；Java 25 构建、PG 外部 URL/容器及独立消费者作业均通过。
本轮唯一原始失败为 skis-sql-ast 覆盖率应抬未抬；Java 21 单元/集成名称转发该权威失败。
新增门槛的来源认证仍待登记提交验证。

PR head 为 `3f8ec9375104a8617be718afdb98f0ee7d3165de`，实际测试合并提交为
`c9339d8f6a1a2e75ced7e1ca3c61986d4f28e665`，比较 base 为
`2fe80d0d9403738dc68fb8a3634d36cea307e283`。已核对合并父提交、四个模块 XML 哈希与候选 reportSha256 一致、
各模块 HTML 报告存在，候选的 scope、首次登记、scopeChanges 及其余 11 个模块行与当前基线一致。

[工件 12579543622](https://github.com/xhufang/skis/actions/runs/40144002225/artifacts/12579543622)
为 `branch-coverage-java-21-40144002225-1`，归档 SHA-256 为
`b45b588b3f0da7fa7c26e22f26a8725701eac2ea49eef8d45df54bff7a5dcb1a`。
原样采用真实候选，仅更新以下四行；其余模块行、首次登记、统计口径及生成排除保持不变。

| 模块 | 原门槛 | 本轮 covered/missed | 类数 | 登记门槛 |
| --- | ---: | ---: | ---: | ---: |
| skis-sql-ast | 78.27% | 1251/293 | 100 | 81.02% |
| skis-dialect-api | 76.86% | 1077/261 | 39 | 80.49% |
| skis-dialect-support | 78.68% | 261/32 | 8 | 89.07% |
| skis-query | 75.19% | 1623/515 | 184 | 75.91% |

对应 XML SHA-256：

- skis-sql-ast：`5b10c454aaeeec0c42298af9a16128f9304bd5829454df38ecef931c5e12a159`。
- skis-dialect-api：`f6c7cb168fa990637d1a10c51e58689eccb460f00ea6aaa6c9697bc66346671e`。
- skis-dialect-support：`63007389bc6081fc43ac60b1dcc4f551cf25a10f047cd7f4500d75fdf1ed3e4b`。
- skis-query：`84534a0194b9ae7e79179ff602079f48888ea33f10f00b2028658f9f768dd62f`。

本地以 Java 21 执行 coverage/architecture 完整构建（无 static-analysis 与真实 PG），合并执行数据得到
skis-query 1623/515，与 CI 一致；登记提交的来源认证及完整门禁尚未通过。
取证保存在本地 `target/ci-coverage-40144002225/` 的归档内容、measurement 与候选。

## PR #86：聚合 DSL 覆盖率抬线（登记后待 CI 认证）

[CI 40097770979 的权威作业](https://github.com/xhufang/skis/actions/runs/40097770979/job/120883515859?pr=86)
已通过 Java 21 构建、必需单元/APT/H2/既有真实 PG、nullness、架构、Enforcer 和报告检查，
79 个普通回归套件均已执行。Java 25 完整构建、PG 外部 URL/容器及独立消费者作业均通过。
本轮唯一原始失败为 skis-sql-ast 覆盖率应抬未抬；Java 21 单元/集成名称转发该权威失败。
新增门槛的来源认证仍待登记提交验证。

PR head 为 `f9fc4b98ce7d848614a462a4b17f24c169555bff`，实际测试合并提交为
`5e5d32bc955332d4bebf5c909fd9ad31a87ca207`，比较 base 为
`8fab2087ac36ebc0542e5e3c02aa530641a05f72`。已核对运行/尝试、合并父提交、七项采集输入 blob ID，
以及提交内 policy/baseline 的字节摘要、15 模块 XML 哈希、逐类/汇总 branch 计数与类数，
确认 XML 中的类均存在于对应 classes.json，并确认各模块 HTML 报告存在。

[工件 12561048629](https://github.com/xhufang/skis/actions/runs/40097770979/artifacts/12561048629)
为 `branch-coverage-java-21-40097770979-1`，归档 SHA-256 为
`4afe19966550c350163eb6c34949b57065583b285b0a6ace94cb1ed74edca691`。
原样采用真实候选，仅更新以下四行；其余 11 个模块行、首次登记、统计口径及生成排除保持不变。

| 模块 | 原门槛 | 本轮 covered/missed | 类数 | 登记门槛 |
| --- | ---: | ---: | ---: | ---: |
| skis-sql-ast | 77.36% | 1063/295 | 98 | 78.27% |
| skis-dialect-api | 76.37% | 525/158 | 30 | 76.86% |
| skis-dialect-support | 78.29% | 203/55 | 7 | 78.68% |
| skis-query | 73.43% | 1579/521 | 184 | 75.19% |

对应 XML SHA-256：

- skis-sql-ast：`b8ef650a30a41c6711bcfa3b5c864513f20734541423dff73761d3244d0ea622`。
- skis-dialect-api：`1c62674fd66c5343f7b10e82811a1f5eb0de7840fbcb1fe2fce9ae26f8a06d67`。
- skis-dialect-support：`6b65da5b3046ca739ff63b060a9f2dcf83f9d798d74fcc4cd9371ca01906eb20`。
- skis-query：`013f612c6e7900d7d5fe2c95195a73b5305aeeadaec1ccf62054df28cd1f1683`。

本地仅下载、静态核对工件并登记候选，未编译运行或执行项目检查器、benchmark；
T04 保持已实现/待 CI，登记提交的来源认证及完整门禁尚未通过。
取证保存在本地 `target/ci-coverage-40097770979/` 的归档、measurement、候选与 `verified-modules.json`。

## PR #85：分组语义覆盖率抬线（登记后待 CI 认证）

[CI 40078097207 的权威作业](https://github.com/xhufang/skis/actions/runs/40078097207/job/120825728566?pr=85)
已通过 Java 21 构建、必需单元/APT/H2/既有真实 PG、nullness、架构、Enforcer 和报告检查。
AggregateGroupingTest 的 50 项、SkisDiagnosticsTest 的 16 项、CompilationPipelineTest 的 21 项及
EntityPlanSetTest 的 19 项均无失败、错误或跳过，首轮 BOOLEAN 排序夹具问题已修复。
Java 25 单元、PG 外部 URL/容器和独立消费者作业均通过；Java 21 单元/集成名称转发权威棘轮失败。
本轮权威作业在 core 应抬未抬时失败，后续新增门槛的来源认证仍待登记提交验证。

PR head 为 `6368897352543e330225adf267d2631e8dc84f13`，实际测试合并提交为
`436a1c3a2813e9a9339fe0032727b63e9863f92b`，比较 base 为
`7b45e7a495b86d876365bf6cf189c4164045c245`。已核对运行/尝试、合并父提交、七项采集输入 blob ID，
以及提交内 policy/baseline 的原始字节摘要、15 模块 XML 哈希、逐类/汇总 branch 计数、类清单和 HTML；
classinfo 与执行数据中的类 ID 一致，34 个执行会话均属于该测试合并提交。

[工件 12553907327](https://github.com/xhufang/skis/actions/runs/40078097207/artifacts/12553907327)
为 `branch-coverage-java-21-40078097207-1`，归档 SHA-256 为
`a3841bdc37634406d05b1df5d1a08d76b0a42732b3f0a0ae54f553a1e739eca6`。
按真实候选登记以下三行，其余 12 个模块的门槛及原来源、首次登记和统计口径/生成排除保持不变。

| 模块 | 原门槛 | 本轮 covered/missed | 类数 | 登记门槛 |
| --- | ---: | ---: | ---: | ---: |
| skis-core | 72.25% | 480/155 | 47 | 75.59% |
| skis-sql-ast | 75.14% | 1046/306 | 98 | 77.36% |
| skis-dialect-api | 75.90% | 485/150 | 30 | 76.37% |

对应 XML SHA-256：

- skis-core：`e9a79042d6dd5f187e4f471ef23b6bdf0afc95b089dca22d8afad4098b6d577d`。
- skis-sql-ast：`614dbd76d94e8780cf5df2f5a3451e790365dc8a4762e2cd59c10ea8cc046b4b`。
- skis-dialect-api：`85b3284cf7493a6c124b5cd57f68e864fa46dd272ecdc11859ce528dffebb41d`。

本地仅下载、静态核对工件并登记候选，未编译运行或执行项目检查器、benchmark；
T03 保持已实现/待 CI，登记提交的来源认证及完整门禁尚未通过。
取证保存在本地 `target/t03-evidence/` 的归档、measurement、候选和 `verified-modules.json`。

## PR #84：聚合 AST 覆盖率抬线（登记后待 CI 认证）

[CI 40000183883 的权威作业](https://github.com/xhufang/skis/actions/runs/40000183883/job/120573944802)
已通过 Java 21 构建、单元/APT/H2/真实 PG、nullness、架构、Enforcer 和必需报告检查，
`AggregateSemanticsTest` 的 19 项用例无失败、错误或跳过；77 个普通回归套件均已执行。
覆盖率检查因 skis-sql-ast 的应抬未抬而失败，Java 21 单元/集成名称转发该失败；新增门槛的来源认证尚未完成。
Java 25 构建、PG 外部 URL/容器及独立消费者作业已通过。

PR head 为 `648d348019e9f00e77f0bf2b4b165c768216797f`，实际测试合并提交为
`08b2f7291770d0168f173a2892dba11ce1fe46e0`，比较 base 为
`f437eb27722200d123f0ddaedd49ee31f81aff4c`。已核对运行/尝试、合并父提交、七项采集输入 blob ID、
提交内 policy/baseline 的原始字节摘要，以及 15 模块 XML 哈希、逐类/汇总 branch 计数、类清单和 HTML。

[工件 12521746864](https://github.com/xhufang/skis/actions/runs/40000183883/artifacts/12521746864)
为 `branch-coverage-java-21-40000183883-1`，归档 SHA-256 为
`5cbe480eabdd0820de5971c9d18460d45a0fb6d0a6afe88c8669332d4b484524`。
仅采用真实候选的 skis-sql-ast 行：covered/missed 为 916/303、97 类，门槛 7232 → 7514（72.32% → 75.14%），
XML SHA-256 为 `d4a916892b0d79b68c4878e75b2204cab92cc51b103f855be0dac16ac789ab3f`。
首次登记、统计口径/生成排除和其余 14 个模块行均与可信历史一致，未降低门槛或修改门禁。

本地仅下载工件并静态核对、登记候选，未编译运行或执行项目检查器、benchmark；
T02 保持已实现/待 CI，登记提交仍需通过后续来源认证及完整门禁。
本地取证保存在 `target/t02-evidence/` 的归档、measurement、候选与 `verified-modules.json`。

## PR #82：query 棘轮抬线与并发覆盖稳定化（合入后已通过）

[CI 39962078076 的权威作业](https://github.com/xhufang/skis/actions/runs/39962078076/job/120442308744)
在 branch 历史比较阶段拒绝 skis-query 的应抬未抬；Java 21/25 构建、必需报告、PG 双路径、消费者和覆盖率采集已执行通过。
Java 21 单元/集成名称转发该权威失败，不是另一项测试失败；本轮尚未完成新增来源认证。
PR head 为 `5c2dcd466c812e5853de78f673ab14083a6cbd19`，实际测试合并提交为
`c0613777956eb564491e46af2a3cfa23b90d5126`，比较 base 为
`9dbb9324411ff2f5697aa85ed29d76b2bb75e692`。

已核对 GitHub 的运行/尝试、合并父提交、七项采集输入 blob ID、15 模块 XML 哈希与 branch/类数及 HTML。
[工件 12505546778](https://github.com/xhufang/skis/actions/runs/39962078076/artifacts/12505546778)
为 `branch-coverage-java-21-39962078076-1`，归档 SHA-256 为
`904f4512ffc1a9c6a387c1083ba85cd2a5532700fa97990891ec714ac6dfe375`。
仅采用真实候选的 skis-query 行：covered/missed 为 1299/470、178 类，门槛 7337 → 7343（73.37% → 73.43%），
XML SHA-256 为 `3f8809ebb5c277bf5596d3f1d6db821191df0f02320b89f2d29c3f480a854ce5`；
首次登记、统计口径/排除及其余 14 个模块行保持不变。

与 9dbb932 的权威 XML 比较，唯一 branch 差异是 EntityPlanSet.java:187 的 compareAndExchange 竞争失败分支，
covered/missed 从 1298/471 变为 1299/470。原 publishesOnePlanIdentityUnderConcurrentFirstUse 依赖 parallel 流调度，
现改为两个独立工作线程在渲染阶段通过 CountDownLatch 同步，确保都越过空槽读取再竞争发布；
保留单主键预热，断言竞争双方及后续命中复用同一发布计划，所有等待有上限且 finally 中中止并回收线程池。
测试方言的标准 Renderer 绑定自身目标；没有修改生产代码、增加生产便利方法或放宽棘轮。

本地仅静态核对源码/差异及 CI 工件，未编译运行或执行 benchmark；测试修正、新门槛的来源认证及稳定报告待后续 CI。
取证数据保存在本地 `target/t10-evidence/pr82-coverage.zip`、候选/measurement 与 `pr82-inspection.json`。

## 历史登记与故障处置

2026-10-23 从 CI 39887845595 的真实候选登记 core、processor 和 jdbc 的提升；首次登记、统计口径及其余 12 个模块的
历史门槛与证据保持不变。该候选同时含 mutation 的下降行，该行未采用，保留 73.40% 并补充缺失的合同测试。
当时新增登记的来源认证及测试修复后测量待 CI；2026-10-24 已核实合入提交 3afa2f0 的新 CI 39965355009 通过。9dbb932 仅证明原始历史基线。

本轮报告来自 [CI 39887845595 的权威作业](https://github.com/xhufang/skis/actions/runs/39887845595/job/120200120805)，
PR #75 head 为 `b1d4405e6d1747d7c57416dbdf2582c302a50a3b`，实际测试合并提交为
`7659b45476031bc6926711f9f04284f60c8d77c6`，比较基线为 `0f77a65c5ad5f75b561b6373cfc7ad69f1ebcf4f`。
[覆盖率工件](https://github.com/xhufang/skis/actions/runs/39887845595/artifacts/12473448389)名为
`branch-coverage-java-21-39887845595-1`，归档 SHA-256 为
`36123cfba1800481966e10b630b4e7749dbeebca0c14406f93e59eb21cf42971`。
已核对 GitHub 工作流身份、尝试次数、合并父提交与会话、七项采集输入 Git blob ID，以及 15 模块 XML 哈希、
类数、逐类与汇总 branch 计数、整数门槛及 HTML 存在性；首次登记与可信比较历史一致。

| 模块 | 原门槛 | 本轮 covered/missed | 登记门槛 |
| --- | ---: | ---: | ---: |
| skis-core | 65.97% | 401/154 | 72.25% |
| skis-processor | 72.24% | 836/318 | 72.44% |
| skis-jdbc | 75.00% | 217/59 | 78.62% |

该轮 Java 21/25 构建、单元/APT/nullness/架构检查、45 项覆盖率脚本夹具、真实 PG 双路径及独立消费者通过，
权威采集和两份工件上传成功。棘轮首先因 core 应抬未抬而失败，后续模块尚未走到认证。
完整工件另显示 mutation 为 67/27（71.27%），低于历史 69/25（73.40%）；两条未覆盖分支均为
`MutationPlanCompiler.DeleteParameterBinder.bindObserved` 中 `needsMore()` 的 true/false 路径。
新增 `MutationParameterBindingTest` 通过实际编译的 delete Binder 验证这两条路径：JDBC 始终且仅绑定一次、
观察发生在绑定之后、最终下标/声明类型/原始局部值一致，预算耗尽时停止观察并仍返回正确下标。
仅补充测试，未改生产入口或降低门槛，未在本地编译运行；分支恢复及所有新登记仍待下一次 CI。

此前 CI 39738254300 的候选将 skis-query 门槛从 7314（73.14%）提升至 7337（73.37%），该行及原证据继续保留。

登记验证 [CI 39740005226](https://github.com/xhufang/skis/actions/runs/39740005226/job/119694664049) 已通过构建、
必需报告及覆盖率采集，但 query 为 1291/474，低于登记报告的 1295/470；其余模块计数一致。
两轮 15 模块的生产类清单、JaCoCo class ID 及统计口径完全相同；四个分支差异均位于
`ProjectionPlanCache.compileOrAwait` 的准入后 `putIfAbsent` 竞争及额外许可释放路径。
已补充 `admittedWaiterJoinsAnOwnerRegisteredBeforeItsFlightInsertion`，用维护锁固定竞争窗口，
核对等待者复用唯一编译、miss 统计及两个许可全部归还。测试仅使用现有生产锁与测试侧反射，
不改生产代码或 73.37% 门槛，未本地编译运行；该轮待验证状态后由 3afa2f0 的 CI 39965355009 关闭。

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
已审阅本轮工件并采用真实候选抬线；更新后的登记已通过合入提交 3afa2f0 的 CI 39965355009 工程门禁。

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
