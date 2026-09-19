# Flowgram 维护笔记

> 本文件是 fork 维护者的工作笔记：架构决策、踩坑记录、待办事项。
> 给未来的自己（或协作者 / AI 助手）省去重新考古的时间。

## 项目概况

- 谱系：Telegram → NekoX（作者 nekohasekai，即 sing-box 作者）→ Nagram（NextAlone）→ **Flowgram**
- 仓库：<https://github.com/flowdance/Flowgram>（从 Nagram 改名而来，旧 URL 自动重定向）
- 本地路径：`/mnt/gjkj/wsz/code/Nagram`（目录名未改，历史遗留）
- GPL-3.0 合规：源码公开；品牌资产已全部替换（包名 `net.flowdance.tg`、签名、图标、字标）

## 构建 / CI / 签名（重要，都踩过坑）

| 项 | 要点 |
|---|---|
| CI secrets | `KEYSTORE_BASE64`（keystore 文件的 base64）+ `LOCAL_PROPERTIES`（KEYSTORE_PASS/ALIAS_NAME/ALIAS_PASS 三行的 base64）。alias 是 `flowdance`，密码在维护者手里 |
| fail-fast | workflow 的 "Restore keystore" 步骤校验 secret 存在性，缺失 5 分钟内报错（否则要烧 40 分钟编译才知道） |
| **签名校验** | `TMessagesProj/jni/integrity/integrity.cpp` 硬编码签名证书 SHA1。**轮换签名密钥必须同步更新此哈希**，否则 App 启动卡死（NativeLoader 吞掉加载异常 → 无 native 层 → ANR） |
| versionCode | 距 2026-01-01T00:00:00Z 的 UTC 秒数（`build.gradle`，CI 用 APP_BUILD_TIMESTAMP 注入，本地构建回退当前时间）。31 位 int 装不下 yyMMddHHmmss，完整时间戳放 APK 文件名（北京时间） |
| Release | push 到 main 自动构建并发布 GitHub Release，tag 为 `v<run_number>`（失败的 run 也烧号） |
| 本地无 JDK | 编译正确性只能靠 CI 验证；改动后用括号配平 + 引用闭环脚本自查 |
| gh PAT | 无 Administration / Secrets 写权限——改名、配 secret 需要网页操作 |

### Telegram 机制速查（实现功能时摸清的）

- **消息删除**：`processUpdateArray` 收集 `updateDeleteMessages` → UI 段发 `messagesDeleted` 通知 + DB 段 `markMessagesAsDeleted`。自己删消息走 `deleteMessages`（另一条路，不受防撤回影响）
- **view-once 媒体文件永远在 cache 目录**（FileLoader 对 ttl 媒体 forceCache）→ 显示/保存必须用 cacheType 1；PhotoViewer 消息分支默认 cacheType 0（images 目录）会错位 → 转圈 + "停止下载"
- **view-once 的服务端清空**：发 readMessageContents 后服务端推 `updateEditMessage`（空媒体 photoEmpty）——拦截点在 `MessagesStorage.putMessages` 入库前从本地恢复
- **noforwards 是纯客户端执行**（服务端不拦 forward）；但 view-once 的转发是服务端拦（消息已消费）
- NekoX 留的逃生门：`NekoXConfig.disableFlagSecure` 置 false 会让 `needDrawBluredPreview`/`isSecretMedia`/`shouldEncryptPhotoOrVideo` 直接短路
- 隐藏功能开关：设置页底部版本号**长按 5 次**（弹"错误"Toast 是障眼法）→ N-Config → 实验设置（Force Copy 等藏在这）

## 自研功能（v6–v11）

### 1. 防撤回 —「保留被删除的消息」（N-Config → 聊天设置）

- 别人删消息不删本地，时间戳旁显示"已删除"（同 edited 标记样式）
- 实现：`MESSAGE_FLAG_KEPT_DELETED = 0x80000000`（bit 31，TL 层未用，BLOB 序列化原样穿透）存进 messages_v2 + messages_topics；`messagesDeletedKept` 事件 + `forceUpdate` 即时刷新
- 限制：纯本地标记；回复被保留消息会暴露；转发被删消息会失败（服务端已删）

### 2. 防闪照 —「保留单次查看媒体」（N-Config → 聊天设置）

- view-once 照片/视频/语音视为普通媒体：不自毁、可重看、可保存
- 实现：MessageObject 三方法短路 + `putMessages` 恢复服务端清空 + PhotoViewer cacheType 1 + 禁流式 + `sendViewOnceReadReceipt`（发"已查看"回执但不起自毁计时）
- **保存 ≠ 转发**：保存到相册后重发没问题；直接转发原消息走服务端会得到空的（媒体已消费）
- 秘密聊天（TL_message_secret）语义未动

### 3. 解除内容限制 —「Force Copy」（实验设置，需解锁隐藏功能）

- 受限频道/群：复制、转发、保存、引用全部放行
- 实现：`NekoXConfig.bypassNoForwards()`（= Force Copy ∥ Disable Flag Secure）；`ChatActivity.isPeerNoForwards()` 已改用 WithOverride 版本（8 个调用点一次覆盖）；消息级 `messageOwner.noforwards` 检查已补齐

## 遗留 TODO

- [ ] `google-services.json` 还是上游 Firebase 项目 → **FCM 推送不可用**（长连接收消息正常，离线推送缺失）。需自建 Firebase 项目注册 `net.flowdance.tg`
- [ ] 上游链接残留（等有自己的对应物再换）：`NekoXConfig.FAQ_URL`、设置页"官方频道/Tips 频道"（`nagram_channel`/`NagramTips`）、Crowdin 翻译链接、"源码"行（NextAlone/Nagram）、NekoXConfig 里两个频道 ID
- [ ] 若受限群转发被服务端拒绝（v11 验证中）→ 实现"转发为副本"（下载媒体 + 按新消息重发）
- [ ] `drawable/ic_launcher_nagram_background.xml` 是孤儿死文件（`<resources>` 根、无引用），可删
- [ ] 资源名 `ic_launcher_nagram*`、Java 包 `xyz.nextalone.nagram` **有意保留**（内部标识符，改了只增加 merge 冲突）

## 协作约定（对 AI 助手同样适用）

- 默认**只 commit**，push 必须等用户明确说
- 提交信息：Conventional Commits、英文；PR：中文标题正文 + Codex 审阅脚注
- 图标生成脚本在仓库外：`/mnt/gjkj/wsz/code/flowgram-icon-preview/`（`suite.py` 全套资产、`gen.py` 概念稿）
- 测试靠用户真机（小号发消息再撤回/闪照/受限频道转发）
