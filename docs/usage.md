# 数据格式与使用

## 目录

```
/storage/emulated/0/logs/azurlane/<yyyy-MM-dd_HHmmss>/
```

一次游戏进程启动 = 一个目录。多进程时，非主进程落在 `<stamp>/proc_<进程名>/`。

拉取到电脑：

```bash
adb shell su -c 'tar -c -C /storage/emulated/0/logs azurlane' > azurlane.tar
tar -xf azurlane.tar
```

## meta.json

```json
{
  "module": "com.azurlane.capture",
  "target": "com.bilibili.azurlane",
  "process": "com.bilibili.azurlane",
  "startedAt": 1763900000000,
  "stamp": "2026-11-23_171203",
  "dir": "/storage/emulated/0/logs/azurlane/2026-11-23_171203",
  "write": "root",
  "root": true,
  "device": "realme RMX3478",
  "android": "17 (sdk 37)",
  "config": {"log": true, "net": true, "tls": true},
  "hooks": {"native": "loaded", "loaded": true, "refresh": "last-failures=0"}
}
```

`write` 取值：`root`（写了 `/storage/emulated/0/logs`）、`root-failed`（有 root 但目录建不出来）、
`no-root`（降级到 app 私有目录）。`hooks.refresh` 的 `last-failures` 是最后一次 PLT 刷新失败的库数，
`0` 表示目标 so 的符号都替换成功。

## app.log

```
2026-11-23 17:12:03.421  [J] Unity                       : OnApplicationPause
2026-11-23 17:12:03.422  [I] native/Unity                : SetMute: 0
2026-11-23 17:12:03.500  [W] native/tolua                : lua_pcall failed
```

- `[J]` Java 层（`android.util.Log` / `System.out`）
- 级别 `V D I W E A`；`native/` 前缀表示来自 `__android_log_print` 系列
- 时间戳在 sink 线程补齐，来自事件发生时刻（native 侧用 `CLOCK_REALTIME` 微秒）

## net/ 与 tls/

`index.jsonl` 每条事件一行：

```json
{"t":1763900000421,"fd":87,"seq":3,"dir":"tx","addr":"1.2.3.4","port":8017,"len":1234,"preview":"0a0b0c..."}
```

- `seq` 是连接序号（同一 fd 复用后会变），文件名 `c003_1.2.3.4_8017`
- `dir`：`tx` 上行 / `rx` 下行
- `len` 是**原始**长度，`preview` 是前 512 字节的 hex
- 单帧超过 64KB 会被截断到 64KB（`len` 仍记原长）
- `*.tx.bin` / `*.rx.bin` 是该连接两个方向的**原始字节流**（按到达顺序拼接，无帧边界）
- `streams.txt` 是人类可读的 hexdump，每帧前 512 字节，带时间与长度头

`tls/` 结构与 `net/` 完全相同，但内容是 `SSL_write` / `SSL_read` 的**明文**。
两者同时存在时：`net/` 里是 TLS 密文记录，`tls/` 里是解密后的 HTTP/私有协议负载。

### 用 Python 读

```python
import json
base = "2026-11-23_171203/net/"
for line in open(base + "index.jsonl", encoding="utf-8"):
    e = json.loads(line)
    if e["dir"] == "tx" and e["preview"].startswith("474554"):  # "GET"
        print(e["t"], e["addr"], e["port"], e["len"])
```

提取某条连接的上行全程：

```python
open("c003_1.2.3.4_8017.tx.bin", "rb").read()
```

## 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| 没有 `logs/azurlane` 目录 | 模块没启用/作用域没勾；看 LSPosed 日志 `/data/adb/lspd/log/` |
| 目录在 `Android/data/com.bilibili.azurlane/files/logs/` | 没给 su 授权，走了降级路径 |
| `net/` 为空 | 目标 so 还没加载就抓了；模块会做 6 次延迟 refresh，重启游戏后再看 |
| `tls/` 为空 | 对方不用 OpenSSL 1.1（或符号被裁剪）；`net/` 仍有密文 |
| 文件被截断 | 单文件 64MB 分代（`app.log.1`、`app.log.2`…），总量 512MB 上限 |
| 游戏变卡 | 关掉 `Config.captureLog` 或 `captureNet` 后重编（见 `Config.java`） |

## 隐私与合规

抓到的内容包含账号相关报文（登录、订单、聊天）。仅用于自建服务端/协议研究，
不要公开分发原始抓包。

## 写入位置与 root

启动时先落到 app 私有外部目录（一定有权限、不丢数据）：

```
/storage/emulated/0/Android/data/com.bilibili.azurlane/files/logs/azurlane/<时间戳>/
```

启动 25 秒后如果 `su` 可用，会把目录切到

```
/storage/emulated/0/logs/azurlane/<时间戳>/
```

并把已抓到的文件复制过去（`meta.json` 里 `"write":"root"` 表示切换成功，`"private"`/`"no-root"` 表示仍在私有目录）。

`su` 可用性按 **进程 uid** 授权：第一次会弹 Magisk/KernelSU 授权框，选"允许"并勾选永久。若不想让游戏进程拿到 root，把 `app/java/com/azurlane/capture/Config.java` 里 `USE_ROOT` 改成 `false` 后重新构建即可，此时只写私有目录，用 `adb pull /storage/emulated/0/Android/data/com.bilibili.azurlane/files/logs/azurlane` 取回。

## 目录内容（build-5 起）

```
<stamp>/
├── meta.json          会话信息（write/root/native 诊断/config 快照）
├── app.log            主日志，白名单 tag；格式 2026-11-23 19:13:36.705  +12345ms  [I] Unity : ...
├── app.1.log          超过 maxSizeMb 后的上一代
├── stacks.jsonl       每 5 秒一条主线程栈采样（state/sameMs/top/frames）
├── stacks_summary.txt 每 60 秒覆盖一次：出现次数最多的 20 段栈
├── stats.jsonl        每 10 秒一条：队列/丢弃/写入/帧统计/native 统计
├── crash/             未捕获异常原文
├── net/
│   ├── index.jsonl    每一次 send/recv 的摘要（fd/seq/端口/长度/preview）
│   ├── frames.jsonl   8017/8018 按帧切分：size/flag/pid/endian/hex
│   ├── streams.txt    可读 hexdump
│   └── c0NN_<ip>_<port>.tx.bin/.rx.bin  原始字节流
└── tls/               同上（仅 captureTls=true 时）
```

日志 tag 白名单：`Unity`、`LuaInterface`、`AndroidRuntime`、`DEBUG`、`libc`、`Il2Cpp`、`tolua`、`Mono`、`CRASH`、`AzCap`、`art`。SDK 的 APM / 广告 / 支付回调默认丢弃。

## 运行时改开关（不用重启游戏）

写 `Android/data/com.bilibili.azurlane/files/logs/azurlane/config.json`（存在则每 15 秒重读）：

```json
{"verbose": false, "log": true, "net": true, "tls": false, "http": false,
 "frame": true, "maxSizeMb": 8, "keepSessions": 10}
```

- `verbose`：记录全部 tag（会回到 APM 噪音里，排障时短开）
- `http`：是否记录 80/443 的 SDK 请求（默认关）
- `frame`：是否对 8017/8018 做 protobuf 帧切分
- `maxSizeMb`：单文件滚动阈值；`keepSessions`：保留最近几个会话目录

取文件：

```
adb pull /storage/emulated/0/Android/data/com.bilibili.azurlane/files/logs/azurlane
```
