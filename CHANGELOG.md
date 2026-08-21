# 变更日志

本文件记录 SKIS 的重要变化。格式参考 Keep a Changelog；正式版本遵循语义化版本。

## [Unreleased]

## [0.0.4] - 2026-08-21

### Added

- 建立 Java 21/25 的 CI 与独立集成测试工作流。
- 建立基于 Pull Request 目标分支的 japicmp public API 兼容门禁。
- 添加 Apache License 2.0、NOTICE、项目与贡献者文档。
- 添加依赖许可证检查和 CycloneDX 1.6 SBOM 产物。
- 添加带版本/标签校验、源码、Javadoc、GPG 签名、Maven Central 和 GitHub Release 的发布流水线。

### Changed

- Maven GroupId 从 `io.skis` 调整为 `io.github.xhufang`，以匹配 Maven Central 已验证命名空间。
- 项目版本统一更新为正式版 `0.0.4`。
- 根 POM 补齐 Maven Central 所需的项目、许可证、开发者和 SCM 元数据。
- GitHub Actions 更新到 Node.js 24 运行时对应的当前主版本。

### Fixed

- 修复局部 Reactor 构建未包含 `skis-processor`，导致集成测试无法解析注解处理器的问题。

### Migration

- Maven 依赖坐标需从 `io.skis:*` 更新为 `io.github.xhufang:*`。
- Java 根包仍为 `io.skis`，源码导入路径无需修改。

[Unreleased]: https://github.com/xhufang/skis/compare/v0.0.4...HEAD
[0.0.4]: https://github.com/xhufang/skis/releases/tag/v0.0.4
