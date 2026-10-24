# 架构决策登记与实现检查

`.github/architecture/decisions.json` 是可提交的最小决策清单，首次登记 ADR-0001—0020。
本地 `docs/adr` 正文和索引继续由 Git 忽略；公开清单只包含必要状态、版本、未完成范围、任务、实现锚点、合同归属及处置理由。
当前验证提交 `9dbb9324411ff2f5697aa85ed29d76b2bb75e692` 的
[Compliance 作业](https://github.com/xhufang/skis/actions/runs/39957930562/job/120428327502)
已通过 33 项失败夹具、可信历史比较和实现锚点检查，上传 CI JSON/夹具日志。
ci.json 的 localAdrChecked=false、semanticContractsExecuted=false；功能/资源等语义证据另由
[CI](https://github.com/xhufang/skis/actions/runs/39957930561) 提供，不能由锚点存在推断。
本地 ADR 正文/索引/清单已另作逐字段静态核对，项目 local 检查器未执行，不冒充其 local.json。

## 两类检查的职责

| 入口 | 检查范围 | 结果 |
| --- | --- | --- |
| `local` | 本地 ADR 正文头部、索引、机器清单的完整 ID 集合和状态、决策版本、目标版本、落地版本、未完成范围、任务；同时检查代码锚点和历史变更 | 本地独立 JSON，不公开 ADR 正文 |
| `ci` | 干净检出的已提交清单、登记记录、代码锚点、合同归属与可信历史变更；拒绝带有本地 ADR 目录的检出 | Compliance 的独立 CI JSON 和失败夹具日志 |

锚点通过只证明声明或配置存在。合同测试归属存在不等于测试执行通过；CI JSON 固定记录
`semanticContractsExecuted=false`，功能、数据库、资源和性能结构合同的运行证据仍由相应测试作业提供。
本检查不编译或运行 Java，也不引入 Maven 或框架运行时依赖。

## 本地入口

使用 Python 3.12 和 Git，在仓库根目录准备检查依赖后执行：

```powershell
python -m pip install -r .github/architecture/requirements.txt
python .github/scripts/architecture_decisions.py local --baseline <完整比较提交SHA> --report target/architecture/local.json
python .github/scripts/run_architecture_contracts.py
```

比较提交取目标分支的可信 SHA，不能选择当前变更中自行降低状态后的提交。首次登记记录绑定
`55a01f31aaee40171dc7c1907d9dad6c33d49858`；首次登记合入前若目标基线移动，必须重新静态核对并更新登记记录。
仓库开发约束要求本地不编译运行时，只写入代码和静态核对事实，命令结果保持待执行。

本地正文直接放在 `docs/adr` 下，禁止用未登记的嵌套目录存放 ADR。正文在首个二级标题前包含唯一的
`状态`、`决策版本`、`目标版本`、`代码落地版本`、`未完成范围`、`实施任务` 字段；
状态字段允许在规定枚举后追加说明，其余字段与清单逐项相等。不适用版本写 `—`，对应 JSON `null`；
无未完成范围或任务写 `—`，对应空字符串。索引使用相同字段的八列表格，每条记录链接唯一正文。
新增正文未登记、索引漏项、重复 ID、正文文件与 ID 不一致、任一状态或字段不一致均失败。

## 登记字段与锚点

`schemaVersion` 固定为整数 `1`。每条决策必须显式填写以下字段：

| 字段 | 约束 |
| --- | --- |
| `id`、`title`、`status` | ID 唯一，格式为 `ADR-` 加四位数字；状态只允许 Proposed、Accepted、Implemented、Superseded、Abandoned |
| `decisionVersion` | 决策所属版本；不能用时间戳替代 |
| `targetVersion`、`implementedVersion` | 不适用时显式为 null；Accepted/Proposed 必须有目标版本，Implemented 必须有落地版本 |
| `remaining`、`task` | 未完成决策必须填写；Implemented 必须为空，不能把待验证状态改成已实现 |
| `anchors`、`contracts` | 实现位置与合同测试/CI 作业归属；Implemented 两者均不可为空，其他状态已登记的锚点也检查 |
| `disposition` | 提供时必须是对象；Superseded/Abandoned 必须保留理由；Superseded 必须指向存在的替代 ID，禁止替代环 |
| `change` | 提供时必须是对象；相比可信基线改变状态或移除锚点/合同归属时，填写原状态、理由、基线 SHA 和逐项移除清单 |

所有状态均在清单校验阶段检查 `change`、`disposition` 的对象类型；不适用时省略，不能填 null、数组或标量。
`disposition.replacedBy` 如有提供，必须是无重复的字符串数组，进入替代关系遍历前即核对。
类型错误通过 `GateError` 报告，失败 JSON 的 `error` 包含 ADR 编号和字段名，避免留下缺少错误原因的报告。

Java 锚点用 `kind=java`、`path`、完整 `type`，可加 `member={kind:method或field,name:成员名}`。
解析实际包、类型与所属类型中的成员声明；注释、字符串、调用表达式、同名嵌套类型不能冒充。
方法锚点表示该名字的声明存在，不承诺特定重载签名。Java 合同还要求测试源路径和 JUnit 测试注解。
Java 合同登记实际声明测试方法的类；只继承测试、负责数据库装配的入口类登记为实现锚点，
其公共抽象基类登记为合同归属。具体数据库套件继续由普通/PostgreSQL 必需报告清单检查执行及零跳过，
不能用基类源码存在代替执行证据，也不为满足锚点检查增加重复测试或放宽声明检查。

2026-10-25：PR #87 的 [Compliance 40131623909](https://github.com/xhufang/skis/actions/runs/40131623909/job/120984347065)
在检查聚合 H2 入口类时报告没有直接测试声明；当前已按上述分工更正 ADR-0006/0022 的归属及入口锚点。
相对 PR 可信基线 `2fe80d0d9403738dc68fb8a3634d36cea307e283` 没有移除原有锚点或合同，首次登记记录及状态保持。
仅静态核对，未执行本地检查器；修复待后续 CI，历史通过记录不作为本次通过证据。

POM 使用 `kind=xml` 和带 `m` 命名空间的 XPath；YAML/JSON 使用 `pointer` 段数组，可用单键对象按列表元素属性选择。
可提供 `expected` 核对值；重复配置键失败。JSON/YAML 的数组、对象默认不得为空；可通过 `expectedType=array`
与 `minItems` 声明数组最小元素数，或通过 `expectedType=object` 与 `minProperties` 声明对象最小属性数。
最小数量必须为非负整数，不接受布尔值；数量约束必须同时声明对应类型。只有显式声明数量为 `0` 才允许空容器，
标量 `false` 和 `0` 仍有效。ADR-0012 的 `modules` 锚点要求数组且 `minItems=1`，空数组、对象或标量均失败。
Python 脚本使用 `kind=python` 和顶层入口 `entry`，同时核对 `if __name__ == "__main__"` 主分支的直属调用。
支持 `entry()`、`sys.exit(entry())` 和 `raise SystemExit(entry())` 三种入口形式；
`else`、内层条件、函数/类方法、lambda 或生成器中的调用不能作为入口注册。脚本的工作流调用另用 YAML 锚点登记。
CI 要求所有锚点文件均受 Git 跟踪，路径不得越出仓库。

`contracts` 只接受 Java 测试、Python unittest 套件或工作流中的可执行作业/步骤，不能登记普通配置或脚本入口。
Python 合同使用 `kind=python-test`、`path`、`suite`：文件位于 `tests` 目录下且命名为 `test_*.py`，
`suite` 指定直接继承 `unittest.TestCase` 的顶层类，并至少声明一个 `test_` 方法；支持 unittest 导入别名。
YAML 合同必须位于 `.github/workflows`，`pointer` 只选择 `jobs/<作业>` 或
`jobs/<作业>/steps/<选择器>/run或uses`；作业须有运行器和可执行步骤，或调用可复用工作流。
JSON、XML、普通 YAML 配置和 Python `entry` 仍可作为实现锚点，不能作为合同归属。

## 首次登记与可信历史

`.github/architecture/registration.json` 保存首次 ID 集合、清单的 SHA-256 摘要、基线 SHA 及本地静态核对声明。
摘要计算方式为 UTF-8 编码的排序键紧凑 JSON：
`json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))`。
登记声明明确区分人工静态核对与未执行的检查器；bootstrap 只能证明首次登记声明，CI 不能据此推断本地正文完整性。

| CI 事件 | 比较来源 |
| --- | --- |
| pull_request | 事件中的 base SHA，另核对检出为该 base/head 的合并提交 |
| push | 事件中的 before SHA，包括首次登记所在 push |
| workflow_dispatch | 显式输入 `architecture_baseline_commit`，须为默认分支当前 tip 且已经包含登记清单 |

所有比较 SHA 必须为可获取的非零完整提交；事件、检出、仓库与传入 SHA 不一致直接失败。
仅在可信基线和历史都没有清单/登记记录时允许 bootstrap，且 ID 集合、摘要和绑定 SHA 必须全部匹配。
历史已有清单后，首次登记记录保持不变；整份清单删除、历史记录删除后重启 bootstrap、单条删除均失败。

状态修正或移除锚点时使用下列形式；只填写理由而不登记被移除的项也不能通过：

```json
{
  "change": {
    "baselineRevision": "<本次可信比较提交SHA>",
    "fromStatus": "Implemented",
    "reason": "发现缺失合同，重新打开验证任务",
    "removedAnchors": [],
    "removedContracts": []
  }
}
```

Superseded/Abandoned 保留原 ID 和处置记录；模块移动、删除或合并时更新相应实现锚点，并登记被移除的旧锚点。
ADR-0006 保持 Accepted，聚合专用规则归 0.2.9；ADR-0018—0020 按当前 CI 的实际架构、消费者、日志/资源及模块/发布语义证据升级 Implemented，
同时记录 Accepted 原状态、9dbb932 比较基线及理由，保留全部锚点/合同和首次登记；并非由本检查单独推断。

## CI 与结果

Compliance 新增 `ADR registration and implementation anchors` 作业，与既有开发期冻结、许可证检查共同执行；
发布白名单继续由 CI/Release 的 `verify-release-boundary.ps1` 验证，不改变发布物或冻结范围。
失败夹具使用临时 Git 仓库，包括干净克隆、可信基线、bootstrap、删除/降级、锚点与合同删除、
状态/重复、替代环、本地未登记/不一致、注释冒充和配置结构检查。
专用入口 `run_architecture_contracts.py` 强制要求失败夹具文件存在且发现的测试数大于零，
发现为空、加载失败、用例失败、任意测试/子测试跳过或预期失败（`expectedFailures`）均返回非零；夹具文件和套件也登记为合同锚点。
新增夹具覆盖文件缺失/空套件、发现钩子返回空套件、导入/执行失败，以及配置冒充合同、空工作流作业和 Python 套件缺失。
跳过负例覆盖整套跳过、部分方法跳过、运行时跳过和子测试跳过；Python 入口夹具覆盖三种有效形式及 `else`、延迟/条件调用负例。
预期失败负例覆盖整类、单方法、通过与预期失败混合及子测试；意外成功仍由 unittest 判为失败。
处置字段夹具覆盖所有状态下的非法对象类型、非法替代 ID 数组，以及本地/CI 入口提前失败时的结构化 JSON 错误原因。
配置锚点夹具覆盖 JSON/YAML 空数组/对象、显式允许空容器、数量不足、类型/约束声明错误及实际登记的 `modules` 非空规则。
上述修复已在 9dbb932 的 Compliance 作业通过，未在本地执行。
源码检查工具安装成功且作业未取消时，一致性检查独立于失败夹具步骤执行；夹具步骤失败后仍生成 `ci.json`，
与失败夹具日志分别留证。两个检查步骤均保留失败状态，不使用 `continue-on-error`；任一步失败都会使作业失败。
工件 `architecture-decisions-<run_id>-<run_attempt>` 保存 CI JSON 和夹具日志；本地 `local.json` 单独登记，不混作 CI 证据。

解析工具固定在 `.github/architecture/requirements.txt`，仅用于开发/CI。
上游接口说明见 [Python Tree-sitter](https://tree-sitter.github.io/py-tree-sitter/)、
[Java grammar](https://github.com/tree-sitter/tree-sitter-java)、[PyYAML](https://pyyaml.org/wiki/PyYAMLDocumentation)。

## 0.2.8 收口记录

2026-10-23 的本地核对范围为 ADR-0001—0020 的 ID、状态、决策/目标/落地版本、未完成范围与任务，
正文、八列索引和机器清单一致；独立记录见本地 `target/t10-evidence/local-adr-static.json`。
T07 的本地验收是同步核对有独立记录，禁止本地运行时采用此静态核对；不把它标为检查器执行通过。
33 项 CI 失败夹具覆盖本地同步与干净检出规则的拒绝行为。首次登记中 checkerExecution=not-run 的历史声明保持不变。
本次三条状态升级及处置字段仅经静态核对，提交后仍由 Compliance 验证本次清单声明；既有语义验收以 9dbb932 为准。
