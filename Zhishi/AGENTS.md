# 版本更新约定

- 用户要求：以后每次更新应用时，同步更新版本号和内部版本码。
- 每次完成一批功能更新或修复并交付 APK 时，更新 `app/build.gradle.kts` 的 `versionName` 和 `versionCode`。
- 用户指定版本号时使用指定值；否则默认将补丁号递增一位（例如 `0.9.2` → `0.9.3`）。`versionCode` 同时递增 1。
- 同一批更新的中间修改、测试和重复构建只使用一个新版本号，不重复递增。
- 交付前重新构建 APK，核对 `app/build/outputs/apk/debug/output-metadata.json` 中的版本号与版本码，并在回复中报告。
