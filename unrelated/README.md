# unrelated — 与「知时」App 无关的文件

本目录存放**不属于知时 Android 应用（`../Zhishi/`）**的内容：其它项目的资料、仿真工程、临时抓取文件和历史调试产物。

- 构建 / 阅读 / 发布知时 App 时**不需要**本目录中的任何文件。
- 可以整体删除，或整体移出仓库；删除不影响 `../Zhishi/` 的编译与测试。
- 已被根目录 `.gitignore` 忽略，不会进入版本库。

## 内容清单

| 路径 | 内容 | 说明 |
| --- | --- | --- |
| `literature/` | `bms_charging_cnki_20261016/` | BMS 充电相关中文文献，属于另一个课题 |
| `matlab/` | `simulation1_2026.m`、`follower_simulink_*`、`lm324_follower_sweep.m`、`slprj/` 等 | 运放跟随器电路仿真（MATLAB/Simulink） |
| `simulation1_complete/` | `opamp_source_copy.ms14*`、`sample_circuit.bmp`、`inspect_api.ps1` | Multisim 电路工程与导出文件 |
| `simulation-reference-preview/` | 空目录 | 仿真参考预览，已空 |
| `tmp/` | `ew-decoder/`、`multisim-mcp-source/`、`pdfs/`、`hook-review/`、`*.log` 等 | 与 App 无关的临时工作目录 |
| `untitled.asv` | MATLAB 自动保存文件 | 无对应 `.m` 源文件 |
| `java_pid14180.hprof` | 约 807 MB JVM 堆转储 | 一次性内存分析产物，可直接删除 |
| `scratch-references/` | `thu-ref/`、`thu-sso-20261025/`、`thu-homework-ref-20261028/`、`thu-login-20261029.html`、`table-tennis-ref/` | 开发期间抓取的网页/代码参考，其中 `table-tennis-ref` 是另一个项目；仅为历史资料，App 运行时不读取 |

## 仍留在仓库根目录、但不是 App 源码的目录

| 路径 | 说明 |
| --- | --- |
| `_tools/android-sdk/`、`_tools/cmdline-tools/`、`_tools/gradle-8.11.1/`、`*.zip` | 本机构建工具链（Android SDK / Gradle 发行包）。构建知时需要它，但**不应提交到 Git**，已在根目录 `.gitignore` 中忽略。 |
| `Zhishi/app/build/`、`Zhishi/build/`、`Zhishi/.gradle/` | Gradle 构建产物，已在 `Zhishi/.gitignore` 中忽略。 |
