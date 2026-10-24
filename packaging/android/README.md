# Folia 安卓构建

这里将当前仓库的 Web 版资源与三平台音乐服务一起打包进 Capacitor 8。网易云、QQ 和酷狗 API 在手机内置 Node 24 运行时中执行，无需用户另行部署服务器。安卓工程在本目录的 `android/`，工具依赖隔离在本目录，不进入桌面端依赖。

## 本地构建

需要 Node.js 24+、npm、pnpm 10+、JDK 21 和 Android Studio / Android SDK。Android 7+，并使用 120+ 的 Android System WebView（应用会检查 WebView 版本）。

在仓库根目录运行：

```sh
npm ci
pnpm --dir packaging/android install --frozen-lockfile --ignore-scripts
npm ci --prefix packaging/android/backend --ignore-scripts
```

独立安卓版优先使用手机内置服务，不需要填写在线音乐后端地址。Web 版仍按环境变量连接外部服务。所有 `VITE_` 值都会进入 APK，不能写 GitHub Token、服务端密钥或其他私密凭据。`localhost` 在手机上指向手机自身，`/api/qq` 也不会访问你的服务器。后端需要允许来自 `https://localhost` 的跨域请求，并支持相应的登录凭据策略。

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
- NetEase / KuGou / QQ 通过手机内置 Node 24 服务工作；服务只监听随机 IPv4 回环端口，每次启动使用随机鉴权密钥，页面经原生桥接访问。QQ 会话加密保存在应用私有目录。
- Spotify、YouTube Music 和波点的桌面专属能力不属于本次手机内置服务。
- Android 的歌词和封面代理由同一个内置服务处理，目标域名受白名单约束；Web 版可继续设置 `VITE_LYRIC_PROXY_ORIGIN`。
- 升级时按打包页面摘要清理旧 Workbox 页面缓存，保留 localStorage、IndexedDB、OPFS 和封面缓存，避免升级后仍运行旧页面。
- 手机的搜索页在短横屏中使用紧凑布局；展开歌曲卡片按可用宽高限制，播放歌名卡片移到顶部，避免挡住底部控制条。
- 保留 PWA worker：现有本地封面资源路径依赖它，不应简单禁用。
- 文件导入、目录持久访问、各平台扫码登录、音频格式和歌词 GPU 性能需要实际 Android 设备逐项验证。WebView 不等同于桌面 Chrome 的文件系统能力。
- Android 原生媒体会话和媒体前台服务提供后台播放、系统通知与锁屏的播放 / 暂停、上一首 / 下一首和进度控制。封面、歌曲信息与应用内同一播放队列同步，拔出耳机会暂停。划掉应用任务、强行停止或系统杀死进程后的自动恢复尚未实现。
- 原生窗口采用沉浸全屏，隐藏状态栏和手势白条；从边缘滑动可临时呼出系统栏。内容铺满窗口，仅为键盘和必要的刘海操作区避让。本地 HTTPS 资源不开放明文或混合内容。
- 安卓系统返回键优先关闭当前弹窗、搜索或集合，随后回到上一级；仅首页根层退到后台。

已在 Windows 上通过 11 项 Node 配置测试、TypeScript 类型检查、安卓模式生产构建、393 × 852 手机尺寸浏览器加载检查，以及 Android SDK 36 / JDK 21 的 assembleDebug 编译；APK 签名验证通过。已在 Android 15 x86_64 模拟器上验证打包后的 Node 运行时、未授权请求隔离，以及生产页面经 Capacitor 原生桥接获取网易云、QQ 和酷狗二维码 key；三项设备测试通过。三平台二维码图片和未扫码轮询也已执行真实接口验证。文件选择、长时间播放与系统回收场景仍需实机验证。独立版无需额外配置音乐 API 地址；二维码初始化、图片生成、轮询和扫码后的账号确认需分别验证。

内置运行时：fogtape/nodejs-mobile `v24.22.0-0`，Android 全量包 SHA-256 `9b068f4ad4c755f26c04b31883c536f037a4831e6575847a699cdf5fa72dde25`。移动运行时基于 Node 24，由维护 fork 发布，构建时校验固定摘要，支持 16 KB 内存页。源码与许可见 https://github.com/fogtape/nodejs-mobile/tree/v24.22.0-0 。音乐 API 依赖版本在 `backend/package-lock.json` 中锁定。

最终修复包构建记录：https://github.com/hejunlin1755/folia-major-android/actions/runs/39199764501 （构建源码 01b1330150348e63b14d8c3d48c05271f13c2e8a）。158 项登录、传输和架构回归测试通过。首次解压服务不计入平台登录请求的时限。

本次 Release 使用本地 debug 构建签名，签名密钥保存在构建环境的 ANDROID_USER_HOME 中。CI 生成的包可能使用不同签名。若提示签名冲突，请先备份本地资料；卸载会清除该应用的数据。



`0.7.21-android.9` 验证：137 项播放 / 传输 / 返回导航 / 架构单元测试、4 项内置服务边界与酷狗授权链测试、TypeScript 检查和 Android APK / instrumentation 编译通过。Android 15 模拟器通过真实界面操作验证了《Canon（钢琴版）》与《Canon（八音盒）》的取流与进度推进、退到桌面和熄屏后继续推进、通知栏与锁屏的暂停 / 恢复、通知栏上一首 / 下一首与进度跳转，以及横屏搜索、沉浸全屏和逐级返回。模拟器运行时关闭了宿主音频输出，因此这些记录验证播放进度和控制链路，不代表已在此电脑上听辨音质。不同厂商设备的后台管理与系统媒体面板行为仍需继续验证。

原生升级回归测试通过：在真实 WebView 中模拟旧安装摘要，退休 Workbox 页面缓存，确认保存的测试账号键与封面缓存不受影响。大缓存清理允许最多两分钟异步完成，避免清理结束后未被原生层确认。

## 全屏与兼容环境

`0.7.21-android.10` 使用 `viewport-fit=cover` 配合原生沉浸窗口，让背景和画面延伸至屏幕边缘。原生层仅将刘海、系统栏和手势距离传给交互控件，键盘出现时继续独立处理输入区域。Android 13+ 首次播放可申请通知权限，媒体通知声明传输分类及本地音乐音量路由。

HarmonyOS 6 经卓易通 / 卓易通 Lite 运行 APK 时，Android 前台服务与 MediaSession 仍在兼容环境内。宿主后台运行和播控卡片依赖兼容工具支持；[卓易通官方 FAQ](https://www.droitong.com/CommonQues.html) 已列出音频通知不能显示播控卡片的情况。请更新工具，并在系统“设置 → 应用和元服务”中检查通知及后台运行权限。本 APK 的 Android 模拟器验证不能代表该鸿蒙兼容环境的实际支持情况。