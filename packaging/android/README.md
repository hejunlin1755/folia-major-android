# Folia 安卓构建

这里将当前仓库的 Web 版资源打包进 Capacitor 8，不依赖加载远程首页。安卓工程在本目录的 `android/`，工具依赖隔离在本目录，不进入桌面端依赖。

## 本地构建

需要 Node.js 24+、npm、pnpm 10+、JDK 21 和 Android Studio / Android SDK。Android 7+，并使用 120+ 的 Android System WebView（应用会检查 WebView 版本）。

在仓库根目录运行：

```sh
npm ci
pnpm --dir packaging/android install --frozen-lockfile --ignore-scripts
```

复制 `.env.android.example` 为 `.env.android.local`，按需填写真实可达的 HTTPS 后端；空值会禁用对应音乐平台。所有 `VITE_` 值都会进入 APK，不能写 GitHub Token、服务端密钥或其他私密凭据。`localhost` 在手机上指向手机自身，`/api/qq` 也不会访问你的服务器。后端需要允许来自 `https://localhost` 的跨域请求，并支持相应的登录凭据策略。

```sh
npm run android:check
npm run android:prepare
cd packaging/android/android
# Windows
.\gradlew.bat assembleDebug
# macOS / Linux
bash gradlew assembleDebug
```

产物：`packaging/android/android/app/build/outputs/apk/debug/app-debug.apk`。

也可在 Android Studio 打开生成的 `packaging/android/android` 工程运行。修改前端或环境配置后重新运行 `npm run android:prepare`。`.env.android.local` 不应提交。

## GitHub Actions 构建

把本次修改提交到你有权限的仓库后，在 Actions 中选择 **Android debug APK → Run workflow**，按需填写公开 HTTPS API 地址。完成后从 Artifacts 下载 `Folia-Android-debug`。此流程不发布 Release，生成的是测试用 debug 签名；正式发行应另配自己的签名密钥。

## 功能边界和验证

- 保留 Web 版已有的触控布局、歌词动画和 Navidrome 入口。
- NetEase / KuGou / QQ 通过独立后端工作；Electron 内嵌 Node 服务及桌面专属功能不能放进 Android WebView。
- `VITE_LYRIC_PROXY_ORIGIN` 指向已部署 Folia Web 的域名，以访问其 `/api/lyric-proxy`，否则需代理的歌词或封面可能失败。
- 保留 PWA worker：现有本地封面资源路径依赖它，不应简单禁用。
- 文件导入、目录持久访问、各平台扫码登录、音频格式和歌词 GPU 性能需要实际 Android 设备逐项验证。WebView 不等同于桌面 Chrome 的文件系统能力。
- 尚未实现原生前台媒体服务，不能承诺熄屏持续播放、锁屏控制或被系统回收后的恢复。
- 原生窗口按系统栏、刘海和键盘的 Insets 留白；本地 HTTPS 资源不开放明文或混合内容。

已在 Windows 上通过 11 项 Node 配置测试、TypeScript 类型检查、安卓模式生产构建、393 × 852 手机尺寸浏览器加载检查，以及 Android SDK 36 / JDK 21 的 assembleDebug 编译；APK 签名验证通过。尚未进行安卓真机或模拟器功能验证，尤其是各音乐平台登录、文件选择、长时间播放与系统回收场景。默认构建未配置在线音乐 API，相关功能需填写自己的后端地址后重建。
