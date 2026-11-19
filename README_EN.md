<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub Plus</h1>

A native Android LLM chat client that supports switching between different providers for conversations 🤖💬

简体中文 | English

</div>

<div align="center">
  <img src="docs/img/poster.png" alt="RikkaHub Plus" width="800" />
</div>

> [!NOTE]
> This is a personal fork of [RikkaHub](https://github.com/rikkahub/rikkahub). Upstream does not accept
> third-party changes, so it lives on its own. Everything except the changes listed below is upstream `master`.

## 🚀 Download

🔗 [Download the APK from Releases](../../releases/latest)

Built by GitHub Actions on every push to `master`. Updates install over the previous build, and the package
is separate from the official RikkaHub so both can be installed at once.

## ✨ Features

**Everything else works exactly like upstream [RikkaHub](https://github.com/rikkahub/rikkahub).**
Only the additions are listed here.

### Added in this fork

- 📱 **Phone Use on Android 8.0 – 10**
  These versions have no accessibility screenshot API, so screenshots come from an opt-in screen recording
  (Settings → Assistant → Local tools → Phone Use → Screen capture). A recording notice stays in the status
  bar while it is on; turning the switch off or restarting the app stops it.

- 🚫 **Configurable blocked apps**
  Upstream hard-codes a payment/banking blocklist (including WeChat) with no way to turn it off. Here nothing
  is blocked by default: pick the apps you want to block in Settings → Assistant → Local tools → Phone Use →
  Blocked apps. Changes apply immediately.

- 🔥 **Firebase removed**
  No private key needed; the project builds from a clean clone and does not report usage data anywhere.

## 🛠️ Development

Open the project in [Android Studio](https://developer.android.com/studio). No `google-services.json` is
needed (the requirement from upstream's README does not apply here).

```bash
# Build the APK
./gradlew assembleGithubRelease
```

Tech stack (same as upstream):

- [Kotlin](https://kotlinlang.org/)
- [Koin](https://insert-koin.io/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- [Room](https://developer.android.com/training/data-storage/room)
- [Coil](https://coil-kt.github.io/coil/)
- [Material 3 Expressive](https://m3.material.io/)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3)
- [OkHttp](https://square.github.io/okhttp/)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)

### Build and sync with upstream

CI lives in `.github/workflows/build.yml`: pushes to `master` produce an APK artifact, and a `v*` tag also
publishes a prerelease.

```bash
git fetch upstream
git merge upstream/master
```

## 📄 License

Licensed under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0), same as upstream.
