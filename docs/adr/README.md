# 架构决策记录索引

本目录记录 SKIS 已接受、已实现、已取代或已放弃的架构决策。状态使用开发指南规定的
`Proposed`、`Accepted`、`Implemented`、`Superseded` 和 `Abandoned`；`Accepted` 只表示设计获准，
`Implemented` 才表示代码与验证已落地。

| 编号                                                                      | 决策                               | 状态          | 代码落地版本                             | 当前说明                                 |
|-------------------------------------------------------------------------|----------------------------------|-------------|------------------------------------|--------------------------------------|
| [ADR-0001](0001-projection-plan-cache-key.md)                           | 投影计划缓存键与治理接口                     | Superseded  | 0.0.8；0.2.4 部分取代                   | 投影身份方案由 ADR-0003 取代；缓存治理要求继续有效       |
| [ADR-0002](0002-jdbc-execution-options-and-exception-classification.md) | JDBC 执行选项与方言异常分类边界               | Implemented | 0.2.1-SNAPSHOT                     | 执行选项、异常分类和兼容默认已落地                    |
| [ADR-0003](0003-generated-result-shape-projection.md)                   | 生成式结果形状与显式投影绑定                   | Implemented | 0.2.4-SNAPSHOT                     | 生成式投影与稳定映射身份已落地                      |
| [ADR-0004](0004-spring-transaction-statement-timeout.md)                | Spring 事务时限与 JDBC Statement 配置边界 | Implemented | 0.2.4-SNAPSHOT                     | Statement 配置扩展点和 Spring 适配已落地        |
| [ADR-0005](0005-query-value-snapshots-and-local-analysis.md)            | 查询值快照与查询对象局部分析复用                 | Implemented | 0.2.4-SNAPSHOT                     | 捕获时快照与查询对象局部分析复用已落地                  |
| [ADR-0006](0006-dialect-capabilities-and-ast-lowering.md)               | 方言能力与 AST Lowering 边界            | Accepted    | 0.2.5-SNAPSHOT；0.2.6-SNAPSHOT 部分落地 | 能力身份与参数上限已实现；完整 AST lowering 归 0.2.7 |
| [ADR-0007](0007-shared-query-plan-key-and-cache-governance.md)          | 共享查询计划结构键与缓存治理                   | Implemented | 0.2.6-SNAPSHOT                     | T03—T09 的身份、缓存、并发、生命周期和验证合同已落地       |
| [ADR-0008](0008-final-query-arguments-and-binder-ownership.md)          | 最终查询参数载体与 Binder 调用期所有权          | Implemented | 0.2.6-SNAPSHOT                     | 最终槽位载体、分页 provenance 和 Binder 所有权已落地 |

新增或更新 ADR 时，必须同时更新本索引中的状态、代码落地版本和当前说明。
