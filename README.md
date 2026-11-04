# NsfPlayer

一个 Android NSF 音乐播放器（8-bit 游戏音乐格式），针对 arm64-v8a 设备优化。

## 功能

- 分曲播放 / 拖动进度 / 切曲
- SAF 文件夹选择（Android 14 兼容）
- 记忆播放进度（退出后恢复到上次的位置）
- 双击返回键退出，防误触
- 全中文界面
- 仅支持 arm64-v8a

## 使用说明

把音乐文件放到对应的目录，刷新没有用；点击文件夹按钮，选择音乐所在的文件夹即可。

## 编译

```bash
./gradlew assembleRelease
```

需要 Android SDK（含 NDK）与 JDK 17。

## 技术说明

Native 层基于 game-music-emu（LGPL 许可），支持 NSF 格式的 VRC6 / Namco 163 / FME-7 扩展音源。输出固定 44100Hz / 16bit / 立体声。

## 许可

本项目的 native 层遵循 game-music-emu 的 LGPL 许可，其余代码为个人优化开发。
