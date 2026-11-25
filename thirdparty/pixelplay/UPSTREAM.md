# 上游来源

- 仓库：https://github.com/ininan/pixelplay-lyricon-provider
- 当前基线提交：1091d03e9643df6858a6f2628c113b6f95c2ff1d
- 构建模块：:app
- 许可：Apache License 2.0（保留仓库内 LICENSE 原文）

## 本仓库对上游的改动

无代码改动。仅构建时自动生成 local.properties（指向 CI 的 Android SDK），该文件不入库。
若上游有新提交，sync-nightly 会自动把源码覆盖到本目录并更新上面的提交号。