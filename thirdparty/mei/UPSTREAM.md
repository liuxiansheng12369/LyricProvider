# 上游来源

- 仓库：https://github.com/jwbb903/LyriconProvider-Mei
- 当前基线提交：ce3d5d523df8f54860e6160cafec98031a0e0dd5
- 构建模块：:mei-provider
- 许可：Apache License 2.0（保留仓库内 LICENSE 原文）

## 本仓库对上游的改动

无代码改动。仅构建时自动生成 local.properties（指向 CI 的 Android SDK），该文件不入库。
若上游有新提交，sync-nightly 会自动把源码覆盖到本目录并更新上面的提交号。