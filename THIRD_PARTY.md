# 来源与第三方组件

## Wenku8

[Wenku8](https://www.wenku8.net/) 是在线书目与阅读内容来源，MewX 中继用于相关接口通信；两者均为外部服务。本项目是独立第三方客户端，安装包不附带小说文件，本地导入内容由使用者自行选择。

## 原生接口运行库

以下文件原样来自 [mikufoxxx/Wenku8_Flutter v0.4.8](https://github.com/mikufoxxx/Wenku8_Flutter/releases/tag/v0.4.8)：

- `app/src/main/jniLibs/arm64-v8a/libcpp.so`
- `app/src/main/jniLibs/x86_64/libcpp.so`

对应分发 APK 的 SHA-256：

| 架构 | SHA-256 |
|---|---|
| ARM64 | `9f1f59ad0623511f74e0b008878cd66e2d5330624bbac9dff5e3325435a07e69` |
| x86_64 | `709cd907a8a20778322efb1dc5515e07eed8bf493473d22d77f77e3f43bd8da6` |

运行库基于 [MewX/light-novel-library_Wenku8_Android](https://github.com/MewX/light-novel-library_Wenku8_Android) 及原贡献者的工作。兼容入口保留 `org.mewx.wenku8.api.TokenManager` 类名。实际复用范围为上述未修改的 JNI 运行库及兼容入口；阅读界面、本地书库和推荐由本工程实现。

## 许可证与署名

- **MewX 上游**：GNU GPL version 2 或后续版本，原文见 [licenses/mewx-GPL-2.0-or-later.txt](licenses/mewx-GPL-2.0-or-later.txt)。
- **Wenku8_Flutter v0.4.8**：原始 [LICENSE](licenses/flutter-v0.4.8-LICENSE.txt)、[NOTICE](licenses/flutter-v0.4.8-NOTICE.txt) 和 [GPL 3](licenses/flutter-v0.4.8-GPL-3.0.txt) 完整保留。该版本 NOTICE 保留上游 GPL 2-or-later 声明，并声明 Flutter 组合版本使用 GPL 3。
- **本工程原创代码**：采用根目录 [MIT](LICENSE)，不更改第三方组件原有权利与许可条件；组合使用时仍适用第三方条款。

保留许可、署名和来源不能替代对应源码等分发要求。本工程未修改上述二进制，也不宣称已提供其对应源码。署名不表示上游参与或认可本项目。

## 平台与构建组件

Android SDK、Java 和 Android System WebView 保持各自许可。阅读界面不依赖第三方 JavaScript/CSS 库。

Gradle Wrapper 8.7 来自官方分发包，使用 Apache License 2.0，许可见 [gradle/wrapper/LICENSE](gradle/wrapper/LICENSE)。

以上声明和原始许可可在 App“我的 → 关于 → 开源许可证与致谢”中离线查看。联系方式见 [README](README.md#联系)。
