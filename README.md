**重要提示：目前该插件仅提供简易对接方案，不涉及复杂处理功能（如新建对话、多消息并行处理），需要功能可提ISSUE，酌情考虑增加，佛系维护**

# ink.easycode.qqclaw

`qqclaw` 是一个面向 OpenClaw + NapCat 的 QQ 私聊接入方案。当前仓库把原先分散的两个子项目整理到了同一个总目录下，方便统一构建、打包和部署。

## 目录结构

```text
ink.easycode.qqclaw/
├─ README.md
├─ channel-plugin/   # OpenClaw 侧 QQ DM Channel 插件（TypeScript）
└─ connector/        # NapCat/OneBot 到 OpenClaw 的桥接连接器（Kotlin）
```

## 组件说明

### channel-plugin

`channel-plugin/` 是 OpenClaw 的通道插件，负责：

- 在 OpenClaw 内注册 QQ DM channel
- 监听来自 connector 的 websocket 入站消息
- 调用 OpenClaw 的会话/回复运行时
- 把最终文本回复回推给 connector

### connector

`connector/` 是 Kotlin 写的桥接器，负责：

- 连接 NapCat OneBot WebSocket
- 接收 QQ 私聊并转换成 OpenClaw bridge frame
- 接收 OpenClaw 的下行回复并调用 `send_private_msg`
- 维持 `ink.easycode.qqclaw` 命名空间下的桥接代码

## 构建方法

### 1. 构建 OpenClaw 插件

在 `channel-plugin/` 目录执行：

```powershell
npm test -- src/index.test.ts src/runtimeDispatch.test.ts src/wsServer.test.ts
npx tsc --noEmit
```

如果需要安装依赖：

```powershell
npm install
```

### 2. 构建 Kotlin connector

在 `connector/` 目录执行：

```powershell
.\gradlew.bat test installDist --console=plain
```

产物会出现在：

```text
connector/build/install/ink.easycode.qqclaw.connector/
```

## 运行关系

整体链路如下：

```text
QQ -> NapCat(OneBot11 WS) -> connector -> OpenClaw qqdm channel plugin -> LLM
QQ <- NapCat(OneBot11 WS) <- connector <- OpenClaw qqdm channel plugin <- LLM
```

典型配置关系：

- NapCat OneBot WS：`ws://127.0.0.1:3001/`
- OpenClaw bridge WS：`ws://127.0.0.1:19190/ws`
- OpenClaw gateway：`ws://127.0.0.1:18789`

## 部署提示

### OpenClaw 插件

把 `channel-plugin/` 作为插件源码目录挂到 OpenClaw 的插件加载路径，例如：

```text
/home/openclaw/.openclaw/plugin-src/qqdm
```

插件运行时仍使用 `qqdm` 作为 id，因此 OpenClaw 配置里的 `channels.qqdm` 不需要因为这次整理而改名。

### Kotlin connector

把 `connector/` 构建后的安装目录部署到服务器，例如：

```text
/opt/ink.easycode.qqclaw.connector
```

典型 JVM 参数：

```text
-Dqq.provider=napcat
-Dqq.botUin=<QQ号>
-Dqq.wsUrl=ws://127.0.0.1:3001/
-Dopenclaw.wsUrl=ws://127.0.0.1:19190/ws
-Dopenclaw.sharedSecret=<shared-secret>
-Dopenclaw.accountId=qqbot:<QQ号>
```

## 常见排查

### NapCat 有消息，机器人不回复

先看三段日志：

```bash
journalctl -u napcat --since '10 minutes ago' --no-pager | tail -n 120
journalctl -u qqdm-connector --since '10 minutes ago' --no-pager | tail -n 120
journalctl -u openclaw --since '10 minutes ago' --no-pager | tail -n 180
```

关注点：

- NapCat 是否出现 `接收 <- 私聊`
- connector 是否出现 `inbound private message`
- OpenClaw 是否出现 `inbound account=` 和回复/错误日志

### QQ 掉线

如果 NapCat 不断要求扫码，通常不是插件代码问题，而是 QQ 登录环境被风控。优先检查：

- 是否重新登录成功
- `127.0.0.1:3001` 是否在监听
- NapCat 日志里是否还有 `Login Error`

若确认为该问题，可通过Napcat的账号密码登录
