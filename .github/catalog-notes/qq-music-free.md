QQ音乐简洁版歌词支持 1.0.2

- 补齐 MediaStyle 通知缺失的 android.mediaSession 令牌：MediaSession 构造与 setPlaybackState 处抓取，notify/notifyAsPackage 与 startForeground 发通知前补进 extras，已有的不覆盖
- 效果：系统开始为简洁版建音乐岛（此前只当普通常驻通知）
- 本补丁在本地镜像完成，已以 Issue #141 提交上游
