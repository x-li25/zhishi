# 知时

一个只面向 Android 的本地优先日程助手。用文字描述日程，交给 AI 识别、用户确认后写入系统日历；空闲时间、学习时段和吃饭时间由本地规则计算，不依赖自建服务器。

## 当前已实现

- 今日日程与前后日期切换
- 全周日程视图（按天分组、完成进度、空闲摘要、展开明细）
- 任务完成勾选（本地标记，不修改系统日历数据）
- 读取 Android 系统日历，包括重复日程实例
- 本地计算空闲时间
- 学习时段、午餐、晚餐建议（互不重叠）
- 一键把建议写入系统日历
- 纯文字输入识别
- 可配置 GLM Base URL、API Key、模型、JSON 输出模式
- AI 结构化解析与本地校验
- 识别结果预览、编辑、删除、确认后执行
- AI 可提出新增（CREATE）、调整（UPDATE）、删除（DELETE）操作
- 修改或删除时，利用前后数周的已有日程与真实 ID 进行匹配
- 逐条执行日程操作：成功的立即生效，失败的保留并说明原因，重试不会重复写入
- API Key 使用 Android Keystore + AES-GCM 加密保存
- 排程偏好配置：学习时间、午餐时间、最短学习时长、事件缓冲时间（含合法性校验）

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
  ai/          GLM 调用与 JSON 解析
  data/        系统日历、设置、API Key 加密存储、完成状态存储
  domain/      数据模型、排程引擎
  ui/screens/  Compose 页面与 ViewModel
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
6. 在“识别”页输入文字，点击“交给 AI 识别”。
7. 检查、编辑或删除识别结果后，点击“确认并执行”。

## 模型接口约定

客户端使用 OpenAI-compatible 的 Chat Completions 形式：

```text
POST {baseUrl}/chat/completions
Authorization: Bearer {apiKey}
```

模型被要求只返回 JSON：

```json
{
  "summary": "简短说明",
  "actions": [
    {
      "type": "create_event",
      "title": "复习数学",
      "start": "2026-10-09T19:00:00+08:00",
      "end": "2026-10-09T20:00:00+08:00",
      "reminders": [10],
      "confidence": 0.95
    },
    {
      "type": "update_event",
      "event_id": 123,
      "title": "复习数学",
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
- 任务完成状态只保存在本机（卸载或清除数据后丢失），不写回系统日历。
- 还没有本地数据库，日程的唯一来源是系统日历。
- 还没有账号、云同步和服务器。
- 建议算法是规则版，尚未学习个人习惯。

## 下一步建议

1. 增加 Room，缓存 AI 识别记录和排程偏好版本。
2. 增加通知权限引导和提醒的端到端验证。
3. 增加冲突检测和“调整前后对比”。
4. 完成状态可选同步到系统日历（例如写入事件备注）。
