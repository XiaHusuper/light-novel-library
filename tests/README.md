# 测试

在项目根目录运行：

```sh
node tests/library.test.cjs
node tests/profile.test.cjs
node tests/recommendations.test.cjs
```

测试覆盖书籍分类、阅读画像、推荐换批与去重规则。

`android/` 保存原生仪器测试，需 Android 测试设备或模拟器及测试构建环境。覆盖范围包括账号会话与书架、手动缓存、标签检索、本地目录排序、书签与插图、系统栏、关于、浏览器注册入口与应用内更新。更新测试使用本地 APK 样本校验下载、签名和安装文件授权。

账号测试使用隔离的合成数据，不需要真实 Wenku8 凭据。测试代码不包含在发行 APK 中。
