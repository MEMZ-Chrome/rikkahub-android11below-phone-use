<div align="center">
  <img src="docs/icon.png" alt="App 图标" width="100" />
  <h1>RikkaHub Plus</h1>

一个原生 Android LLM 聊天客户端，支持切换不同的供应商进行聊天 🤖💬

[English](README_EN.md) | 简体中文

</div>

<div align="center">
  <img src="docs/img/poster.png" alt="RikkaHub Plus" width="800" />
</div>

> [!NOTE]
> 本项目是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的个人修改版，与上游无关，上游也不接收第三方改动。
> 除下面列出的几处调整外，其余代码与上游 `master` 保持一致。

## 🚀 下载

🔗 [前往 Releases 下载 APK](../../releases/latest)

由 GitHub Actions 在每次推送到 `master` 时构建，可直接覆盖安装升级。包名与官方版不同，两者可以共存，互不覆盖。

## ✨ 功能

**除以下新增内容外，功能与上游 [RikkaHub](https://github.com/rikkahub/rikkahub) 完全一致。**

### 本版新增

- 📱 **Android 8.0 – 10 也能用 Phone Use**
  这些系统没有无障碍截图接口，截图改为从用户手动授权的录屏里取帧。
  入口：设置 → 助手 → 本地工具 → Phone Use → 屏幕捕获（仅 Android 11 以下显示）。
  开启期间状态栏会显示录屏提示，关掉开关或重启应用即停止。

- 🔓 **Phone Use 支持 Shizuku 和 root**
  除了无障碍服务，还可以选 Shizuku（adb 级权限）或 root（su）来操作手机，不用开无障碍服务。
  入口：设置 → 助手 → 本地工具 → Phone Use → 执行方式。
  这两种方式下中文等非 ASCII 文字靠剪贴板粘贴输入，个别应用可能不接受粘贴。

- 🚫 **禁用的应用由自己配置**
  原项目把支付、银行类应用（包含微信）写死在代码里禁止操作，没有开关。这里默认不限制任何应用，
  在 设置 → 助手 → 本地工具 → Phone Use → 禁用的应用 里勾选需要禁用的应用即可，改完立即生效。

- 🔥 **移除 Firebase 统计与崩溃上报**
  不依赖任何私有密钥，克隆下来就能直接编译，也不再向第三方上报使用数据。

## 🛠️ 开发

用 [Android Studio](https://developer.android.com/studio) 直接打开即可，不需要额外放置
`google-services.json`（上游 README 里那条要求在本项目不适用）。

```bash
# 编译 APK
./gradlew assembleGithubRelease
```

技术栈与上游一致：

- [Kotlin](https://kotlinlang.org/)（开发语言）
- [Koin](https://insert-koin.io/)（依赖注入）
- [Jetpack Compose](https://developer.android.com/jetpack/compose)（UI 框架）
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)（偏好数据存储）
- [Room](https://developer.android.com/training/data-storage/room)（数据库）
- [Coil](https://coil-kt.github.io/coil/)（图片加载）
- [Material 3 Expressive](https://m3.material.io/)（UI 设计）
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3)（导航）
- [OkHttp](https://square.github.io/okhttp/)（HTTP 客户端）
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)（JSON 序列化）

### 构建与同步上游

CI 配置在 `.github/workflows/build.yml`：推送到 `master` 产出 APK artifact，打 `v*` tag 会额外发一个
prerelease。

```bash
# 同步上游
git fetch upstream
git merge upstream/master
```

## 📄 许可证

本项目基于 [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) 开源，与上游一致。
