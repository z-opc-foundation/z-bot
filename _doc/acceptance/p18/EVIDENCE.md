# P18 验收 EVIDENCE — 通道注册表 SPI + 飞书/钉钉真出站

- 工作树 `/private/tmp/zbot-wt-p18`，分支 `w4-p18`，起点 `926b8b5`
- 工单 `/Users/zifang/.cache/zbot-p17/dispatch_p18.md`
- 现场/日志根 `~/.cache/zbot-p18/`（`.gitignore:5` = `*.log` ⇒ 决定性读数原样贴本文）

## 0. 第 0 步实测（复算工单假设）

| # | 假设 | 复算命令 | 实测 | 判定 |
|---|---|---|---|---|
| 0-1 | channel 面 16 个 .java | `ls z-bot-core/src/main/java/com/zifang/z/bot/channel/*.java \| wc -l` | |
| 0-2 | 合计 5032 行 | `cat z-bot-core/src/main/java/com/zifang/z/bot/channel/*.java \| wc -l` | |
| 0-3 | 飞书/钉钉 0 生产构造点 | `git grep -n "new FeishuChannel\|new DingTalkChannel" -- 'z-bot-core/src/main/java'` | |
| 0-4 | 出站是 stub | `grep -n "stub" .../channel/FeishuChannel.java`、`.../DingTalkChannel.java` | |
| 0-5 | okhttp 已在依赖 | `grep -n "okhttp" z-bot-core/pom.xml pom.xml` | |
| 0-6 | kernel-app 是空模块 | `unzip -l ~/.m2/.../z-agent-kernel-app/0.2.1/*.jar \| grep '\.class$'` | |
| 0-7 | z-bot 从无 ServiceLoader | `git grep -n "ServiceLoader" -- 'z-bot-core/src/main'` | |
| 0-8 | 无第三方 IM 凭据 | `grep -o '^[a-zA-Z0-9._-]*=' ~/.zbot/config.properties`（只列名，不读值） | |
| 0-9 | @Test 起点 619 | `git grep -c '@Test' 926b8b5 -- 'z-bot-core/src/test'` 求和 | |

## 1. 注册表落地形态

- 复算命令 / 实测：（待填）

## 2. 真出站（Feishu token 换取 + 发送 + 错误分类）

- 复算命令 / 实测：（待填）

## 3. 显式降级（缺键必须报出来）

- 复算命令 / 实测：（待填）

## 杠① 单测 ×3 顺序独立

- 复算命令 / 实测：（待填）

## 杠② 变异注入

- 复算命令 / 实测：（待填）

## 杠③ 真进程 E2E

- 复算命令 / 实测：（待填）

## 杠④ 用户数据根未被触碰

- 复算命令 / 实测：（待填）

## §未做

- （待填）
