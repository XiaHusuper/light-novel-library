# 测试

在项目根目录运行：

```sh
node tests/library.test.cjs
node tests/profile.test.cjs
node tests/recommendations.test.cjs
node tests/book-stream.test.cjs
```

测试覆盖书籍分类、阅读画像、推荐排序、逐页流与去重规则。

安装 Playwright 及浏览器后，可运行 `node tests/list-return.test.cjs`，检查三个栏目的列表原位返回、首帧位置及封面复用。结果写入 `dist/test-results`；可用 `READER_TEST_OUTPUT` 指定目录。

`android/` 保存原生仪器测试，需 Android 测试设备或模拟器及测试构建环境。覆盖范围包括账号会话与书架、手动缓存、标签检索、本地目录排序、书签与插图、系统栏、关于、浏览器注册入口与应用内更新。更新测试使用本地 APK 样本校验下载、签名和安装文件授权。

账号测试使用隔离的合成数据，不需要真实 Wenku8 凭据。测试代码不包含在发行 APK 中。

可运行 `node tests/scroll-lists.test.cjs` 验证每次下滑一页、两个周榜来源、真实总页数、回到书目顶部与栏目切换位置保留。

`official-updates.test.cjs` checks the opt-in official-list parser, existing native-login entry, pagination, preserved source cache and failure handling. `android/OfficialUpdatesTest.java` uses synthetic intercepted HTML in Android WebView to verify native-only session handoff, one normal official-login POST, same-user identity, separate bridge and navigation bounds. It does not prove a real Wenku8 account or challenge is accepted.

The official-page tests additionally cover image-free cards/rows, relative paging links, bounded DOM-only rechecks, keeping an unrecognized page open, safe diagnostic copy and return to background paging. The stream test exhausts433 synthetic pages without live source reads.

Official pagination fixtures reproduce adjacent 1/217 and button1 becoming2171, verify isolated/stat/last-link totals and contradictory-marker refusal. Android fixtures also verify the compact217-page layout and background next-page reads; visual preview uses synthetic content only.

Recent-update pagination gives the official page pair priority over auxiliary arrows/labels. Tests keep1/217 usable despite differing auxiliary markers, retain real wrong-page rejection, and verify display deduplication never recalculates the source total. This adapter is exclusive to official recent updates.

Official browser lifecycle tests verify a retained same-origin XHR document, quiet background failures/recovery, bottom-tab continuity/in-flight cache, rank-only cancellation and cookie-owner reuse/logout cleanup. No real account or bulk source access is needed.

`android/ReleaseUpgradeTest.java` 验证同签名测试版通过本地正式版更新清单发现、校验更新包，以及覆盖安装后保留本机状态。分为 `before` 和 `after` 两个阶段，仅在隔离测试设备运行。
