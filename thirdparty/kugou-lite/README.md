# 🎵 KugouLiteProvider - 酷狗概念版 词幕提供器

**基于 [LyricProvider](https://github.com/tomakino/LyricProvider) 开发的酷狗概念版词幕提供器**  

🚀 [下载最新版本](https://github.com/OneDongua/KugouLiteProvider/releases)

## ✨ 功能特性

* 📂 **本地歌词支持**：直接从酷狗概念版的数据目录读取已下载的 KRC 歌词文件。
* 📝 **歌词解密与解析**：集成 KrcKit，支持 KRC 格式的解密和解析，提供逐字级的歌词体验与翻译支持。
* 🔄 **状态同步**：通过 Hook MediaSession，实现播放状态（播放/暂停/进度）和歌曲元数据（标题/艺术家）的实时同步。
* 🎯 **智能匹配**：结合精确匹配与编辑距离算法（Fuzzy Match），提高歌词文件的匹配成功率。

## 🛠️ 使用要求

1. 🔓 **Root 与 Xposed 环境**：已安装 LSPosed 或其他兼容的 Xposed 框架。
2. 📱 **目标应用**：安装并正常使用 [酷狗概念版](http://www.kugouyouth.com/)。
3. 🎼 **词幕应用**：安装并配置 [词幕 (Lyricon)](https://github.com/tomakino/lyricon) 客户端。

## 🚀 安装与配置

1. 📥 下载并安装本模块的 APK。
2. ✅ 在 LSPosed 管理器中启用本模块，并勾选 **酷狗概念版** 作为作用域。
3. ⚠️ **确保其他酷狗音乐提供器模块已关闭。**
4. 🔄 重启酷狗概念版。
5. 🎶 在酷狗概念版中播放歌曲，即可看到状态栏歌词生效。

## 💻 技术实现

本项目主要依赖以下库：

* [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)：高效且现代的 Hook API。
* [Lyricon](https://github.com/tomakino/lyricon)：用于接入词幕提供器框架。
* [KrcKit](https://github.com/tomakino/LyricProvider/tree/master/share/krckit)：酷狗 KRC 歌词解密与解析工具。

## 📄 许可证

本项目采用 [Apache 2.0 License](LICENSE) 许可证。
