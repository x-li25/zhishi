# 知时

一个只面向 Android 的本地优先日程助手。内置有记忆的 AI 聊天小助手：用对话的方式告诉它你的安排，它提出日程操作，你一键确认后写入系统日历。

## 当前已实现

- 今日日程卡片：紫色日期标题 + 竖条日程条目（标题、地点、起止时间）
- 全周日程视图（按天分组、清单完成进度、空闲摘要、展开明细）
- 任务清单：AI 检测到复习/预习任务时主动询问清单；带清单的日程可点进二级页逐条勾选
- 自我打分：清单全部完成后可在详情页打 1~5 星，评分显示在日程条目上（本地保存）
- 今日平均自评分显示在“今天”页日期下方（只统计已打分的日程）
- 底部导航选中项紫色高亮
- 其他日程也可由 AI 附带清单（不主动询问）；无清单的日程不可点进二级页
- 手动删除：长按日程条目（有涟漪反馈）弹出确认框，删除时一并清理清单和自评分
- AI 聊天助手：多轮对话、记住历史（本地持久化，重启不丢）
- 助手可提出新增（CREATE）、调整（UPDATE）、删除（DELETE）操作，确认后逐条执行
- 地点字段全链路支持：AI 识别、编辑对话框、写入系统日历、卡片展示
- 读取 Android 系统日历，包括重复日程实例
- 本地计算空闲时间
- 可配置 GLM Base URL、API Key、模型、JSON 输出模式
- 逐条执行日程操作：成功的立即生效，失败的保留并说明原因，重试不会重复写入
- API Key 使用 Android Keystore + AES-GCM 加密保存

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- Android `CalendarContract`
- OkHttp + kotlinx.serialization
- Android Keystore
- Gradle 8.11.1 / AGP 8.7.3 / Kotlin 2.0.21

项目最低支持 Android 8.0（API 26），编译目标 API 35。

## 目录结构

```text
app/src/main/java/com/zhishi/schedule/
  ai/          GLM 多轮对话调用与 JSON 解析
  data/        系统日历、设置、API Key 加密存储、任务清单、聊天记录存储
  domain/      数据模型、排程引擎
  ui/screens/  Compose 页面（今天 / 周 / 助手 / 设置）与 ViewModel
  ui/theme/    主题
  util/        时间格式化
```

## 开始使用

1. 用 Android Studio 打开 `D:\tmp\work\Zhishi`。
2. 等待 Gradle Sync 完成。
3. 连接 Android 8.0 以上手机，运行 `app`。
4. 在“今天”页授予日历读取/写入权限。
5. 在“设置”页填写：
   - Base URL：例如 `https://open.bigmodel.cn/api/paas/v4`
   - API Key
   - 模型名称：例如 `glm-5.3-flash`
6. 在“助手”页像聊天一样说出安排，例如“明天下午三点到五点在六教6A414上大学物理”。
7. 核对助手提出的操作卡片，可编辑或删除，点击“确认并执行”写入日历。

## 模型接口约定

客户端使用 OpenAI-compatible 的 Chat Completions 形式，携带最近 20 条对话历史：

```text
POST {baseUrl}/chat/completions
Authorization: Bearer {apiKey}
```

模型被要求只返回 JSON：

```json
{
  "reply": "给用户看的简短回复",
  "actions": [
    {
      "type": "create_event",
      "title": "复习数学",
      "start": "2026-10-09T19:00:00+08:00",
      "end": "2026-10-09T20:00:00+08:00",
      "location": "图书馆三层",
      "checklist": ["第三章习题", "错题回顾"],
      "reminders": [10],
      "confidence": 0.95
    },
    {
      "type": "update_event",
      "event_id": 123,
      "start": "2026-10-09T20:00:00+08:00",
      "end": "2026-10-09T21:00:00+08:00",
      "confidence": 0.9
    },
    {
      "type": "delete_event",
      "event_id": 456,
      "confidence": 0.9
    }
  ],
  "questions": []
}
```

## 构建命令

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME='D:\tmp\work\_tools\android-sdk'
.\gradlew.bat assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 当前边界

- 修改或删除重复日程、全天日程时会被本地保护逻辑拒绝，需在系统日历中处理。
- 任务清单及其勾选状态只保存在本机（卸载或清除数据后丢失），不写回系统日历。
- 聊天记录保存在本机 SharedPreferences，最多保留最近 100 条；发送给模型的记忆为最近 20 条。
- 还没有本地数据库，日程的唯一来源是系统日历。
- 还没有账号、云同步和服务器。

## 下一步建议

1. 增加 Room，替代 SharedPreferences 存储聊天记录和任务清单。
2. 增加通知权限引导和提醒的端到端验证。
3. 增加冲突检测（新建日程与已有日程时间重叠时提醒）。
4. 清单支持手动添加/编辑条目（目前只能由 AI 创建）。
