# 变更日志

本文件记录 SKIS 的重要变化。格式参考 Keep a Changelog；正式版本遵循语义化版本。

## [Unreleased]

### Added

- 建立 Java 21/25 的 CI 与独立集成测试工作流。
- 建立基于 Pull Request 目标分支的 japicmp public API 兼容门禁。
- 添加 Apache License 2.0、NOTICE、项目与贡献者文档。
- 添加依赖许可证检查和 CycloneDX 1.6 SBOM 产物。
- 添加带版本/标签校验、源码、Javadoc、GPG 签名、Maven Central 和 GitHub Release 的发布流水线。

### Changed

- 开发版本统一更新为 `0.0.4-SNAPSHOT`。
- 根 POM 补齐 Maven Central 所需的项目、许可证、开发者和 SCM 元数据。

### Migration

- `0.0.4-SNAPSHOT` 未改变现有 Java public API，无需源码迁移。

[Unreleased]: https://github.com/xhufang/skis/compare/v0.0.3...HEAD
