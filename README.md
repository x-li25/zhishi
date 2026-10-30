# VibeCoding/work

工作区根目录。这里只有一个真正的项目：

| 目录 | 状态 | 说明 |
| --- | --- | --- |
| [Zhishi/](Zhishi/) | **有效项目** | 「知时」Android 日程助手，Kotlin + Jetpack Compose。入口见 [Zhishi/README.md](Zhishi/README.md) |
| `_tools/` | 构建工具链 | Android SDK / cmdline-tools / Gradle 发行包，仅本机使用，不进版本库 |
| [unrelated/](unrelated/) | **与 App 无关** | 其它课题的文献、仿真工程、临时文件、历史调试产物。详见 [unrelated/README.md](unrelated/README.md)，可整体删除 |

约定：

- 与知时 App 无关的文件一律放在 `unrelated/`，不要散落在根目录。
- 构建产物、工具链、堆转储等二进制内容由 `.gitignore` 拦截，不要提交。
