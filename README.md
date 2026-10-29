# 轻小说文库

面向 [Wenku8（轻小说文库）](https://www.wenku8.net/) 的 Android 阅读器，支持在线阅读、书架同步和本地 EPUB/TXT 书库。

[下载最新版](https://github.com/XiaHusuper/light-novel-library/releases/latest) · [更新日志](CHANGELOG.md) · [分类与推荐](ALGORITHMS.md)

## 主要功能

- **找书**：书名、作者与标签检索，多标签筛选，点击与推荐榜单、收藏榜、字数榜及完结小说列表。
- **在线阅读**：正文和插图按需加载，支持左右翻页、滚动阅读、字体与主题设置、阅读进度恢复。
- **Wenku8 书架**：登录账号后同步收藏，可在 App 内添加或移出书架；注册入口通过浏览器打开官网。
- **本地书库**：批量导入 EPUB/TXT，自动合并同一作品、整理分卷与目录，也可手动调整顺序。
- **个性化推荐**：每批最多 20 本，结合标签偏好和阅读画像推荐作品，排除已在书架中的书籍，支持清空阅读学习记录。
- **书签与插图**：保存摘录、分类管理书签、返回原文，集中查看当前分卷的插图。
- **离线阅读**：按章节、分卷或整本手动缓存，支持空间上限与清理。

## 下载与使用

前往 [Releases](https://github.com/XiaHusuper/light-novel-library/releases/latest)，下载并安装 `LightNovelLibrary-版本号.apk`。

- Android 8.0 及以上，支持 ARM64 手机和 x86_64 设备。
- 无需电脑或本地服务，在线功能需能访问 Wenku8 相关服务。
- 本地阅读、历史记录、阅读位置、书签和画像无需登录。
- 同一签名的新版 APK 可直接覆盖安装。

## 数据与同步

普通在线阅读按需获取正文和插图，不自动保存为离线文件。书目、目录和阅读记录会保留在本机；手动缓存保存在应用内部存储，可在“我的 → 离线缓存”管理，不生成 EPUB。

Wenku8 账号仅同步书架收藏。浏览历史、阅读位置、书签和阅读画像保留在本机，不上传到 Wenku8。

标签检索首次需要逐步建立书目索引，结果会陆续补全，支持暂停和继续。推荐采用标签匹配、阅读偏好与多样性排序，具体规则见 [ALGORITHMS.md](ALGORITHMS.md)。

## 构建

使用 Android Studio 打开本目录，配置 JDK 17、Android SDK 35 和 Build Tools 34.0.0。项目使用 AGP 8.6.1 / Gradle 8.7，界面采用 Java 与本地 WebView。

也可使用 Python 3.10+ 和 Android SDK 工具构建：

```powershell
$env:JAVA_HOME = '你的 JDK 17 路径'
$env:ANDROID_HOME = '你的 Android SDK 路径'
python tools/build_apk.py
```

脚本默认在 `dist/` 生成 APK，签名密钥保存在 `.signing/`。请妥善保管密钥，后续覆盖升级需使用相同签名；发行密钥不随仓库分发。测试入口见 [tests/README.md](tests/README.md)。

## 来源与致谢

- **[Wenku8](https://www.wenku8.net/)**：在线书目与阅读内容来源。本项目为独立开发的第三方客户端。
- **[MewX/light-novel-library_Wenku8_Android](https://github.com/MewX/light-novel-library_Wenku8_Android)**：接口相关上游项目及原贡献者。
- **[mikufoxxx/Wenku8_Flutter](https://github.com/mikufoxxx/Wenku8_Flutter)**：原生接口运行库的直接分发来源（v0.4.8）。

本工程原创代码采用 [MIT](LICENSE)。第三方组件保留各自许可证，复用范围及完整声明见 [THIRD_PARTY.md](THIRD_PARTY.md)、[NOTICE](NOTICE) 和 [licenses/](licenses/)。

## 联系

如本项目涉及侵权，请联系 [1807701563a@gmail.com](mailto:1807701563a@gmail.com)，我们将及时核实并处理。
