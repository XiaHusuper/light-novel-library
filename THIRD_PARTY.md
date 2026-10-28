# 第三方组件来源

## 文库接口原生运行库

`app/src/main/jniLibs/arm64-v8a/libcpp.so` 与 `x86_64/libcpp.so` 原样来自：

- 项目：<https://github.com/mikufoxxx/Wenku8_Flutter>
- 正式版本：<https://github.com/mikufoxxx/Wenku8_Flutter/releases/tag/v0.4.8>
- ARM64 APK SHA-256：`9f1f59ad0623511f74e0b008878cd66e2d5330624bbac9dff5e3325435a07e69`
- x86_64 APK SHA-256：`709cd907a8a20778322efb1dc5515e07eed8bf493473d22d77f77e3f43bd8da6`

运行库基于 MewX 及上游贡献者的工作；公开上游项目为 <https://github.com/MewX/light-novel-library_Wenku8_Android>。本工程不修改、不重新实现该二进制，不将本工程的原创代码许可扩展至它，也不宣称已经提供该二进制的对应源码。其使用与分发条件继续以原权利方的许可为准。

JNI 入口类名保持 `org.mewx.wenku8.api.TokenManager`，用于与原运行库兼容；运行时生成的参数不写入配置、日志或测试报告。

## 来源服务与内容

Wenku8 与 MewX 中继是外部服务，不属于本工程。代码许可不授予小说、封面或插图的权利。安装包不附带来源小说文件或用户书库；本地导入文件由用户自行选择。

## 平台组件

Android SDK、Java 和 Android System WebView 保持各自许可。工程的阅读界面不依赖第三方 JavaScript/CSS 库，不内置其他项目的品牌图标。

Gradle Wrapper 8.7 来自已核验的官方 Gradle 8.7 分发包，遵循 Apache License 2.0；许可保留于 `gradle/wrapper/LICENSE`。


## MewX 与 Wenku8_Flutter 的署名和许可

- 上游项目：[MewX/light-novel-library_Wenku8_Android](https://github.com/MewX/light-novel-library_Wenku8_Android)，保留 MewX 与原贡献者署名。上游 LICENSE 是 GNU GPL version 2 或后续版本；原文保存在 `licenses/mewx-GPL-2.0-or-later.txt`。
- 本工程原生运行库的直接分发来源：[mikufoxxx/Wenku8_Flutter v0.4.8](https://github.com/mikufoxxx/Wenku8_Flutter/releases/tag/v0.4.8)。保留该版本的 `LICENSE`、`NOTICE` 以及 `assets/licenses/GPL-3.0.txt` 原文，位于本工程 `licenses/flutter-v0.4.8-*`。该版本 NOTICE 说明原始 GPL 2-or-later 声明保留，Flutter 组合版本使用 GPL 3；不把仓库根目录的 GPL 2 文本误写成它唯一的分发声明。
- 本工程实际复用范围为上述 JNI 原生接口运行库及兼容入口；阅读界面、本地书库和推荐实现由本工程实现。来源链接与原声明不代表上游作者参与、认可或授权运营本项目。
- 本工程根目录 MIT 仅适用于本工程原创代码，不更改第三方组件原有权利与许可条件；组合使用时第三方条款继续适用。保留许可、署名和来源不替代各组件对应源码等实际分发要求。本工程仍不宣称提供了该二进制的对应源码。

以上内容与原许可也在 App“我的 → 关于 → 开源许可证与致谢”中离线提供。
