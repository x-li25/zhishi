# 架构说明

## 分层

```text
Compose UI
  -> AppViewModel
     -> GlmClient
     -> CalendarRepository
     -> ScheduleEngine
     -> ConfigStore
```

## 核心原则

1. AI 只输出结构化候选操作（新增、调整、删除）。
2. 本地代码校验日期、时区、时间顺序与置信度。
3. 用户确认后才写入系统日历。
4. 空闲时间由本地确定性算法计算，不交给大模型。
5. API Key 只保存在本机，使用 Android Keystore 保护。

## 数据流

```text
文字/图片/录音
  -> CaptureInput
  -> GlmClient
  -> AiParseResult
  -> 预览/编辑确认
  -> CalendarRepository.applyEvent()
     -> CREATE: insertEvent()
     -> UPDATE: updateEvent()
     -> DELETE: deleteEvent()
  -> 系统日历
```

排程流：

```text
系统日历 Instances
  -> CalendarEvent
  -> ScheduleEngine
  -> 空闲区间
  -> 学习/午餐/晚餐建议
```

## 关键文件

- `ai/GlmClient.kt`：模型/ASR 请求、图片压缩与结构化 JSON 解析
- `data/CalendarRepository.kt`：系统日历读写、修改/删除保护与提醒处理
- `data/ConfigStore.kt`：设置持久化
- `data/SecretStore.kt`：API Key 加密
- `domain/ScheduleEngine.kt`：空闲时间和建议算法
- `ui/screens/AppViewModel.kt`：应用状态与业务流程
