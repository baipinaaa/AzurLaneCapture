# AzurLane Capture

一个 **LSPosed 模块**（现代 libxposed API 102），只作用于 **碧蓝航线 B 站国服**
（`com.bilibili.azurlane`），把游戏的**运行日志**与**网络数据**实时转存到手机存储：

```
/storage/emulated/0/logs/azurlane/<yyyy-MM-dd_HHmmss>/
├── meta.json          本次抓取的元信息（设备/进程/hook 状态）
├── app.log            Java + native 日志（统一时间轴）
├── crash/             未捕获异常堆栈
├── net/
│   ├── index.jsonl    每条 TCP 事件一行（fd/序号/方向/地址/长度/hex 预览）
│   ├── cNNN_<ip>_<port>.tx.bin   该连接全部上行原始字节
│   ├── cNNN_<ip>_<port>.rx.bin   该连接全部下行原始字节
│   └── streams.txt    可读 hexdump（每帧前 512 字节）
├── tls/               同上，但内容是 HTTPS 解密后的明文（SSL_write/SSL_read）
└── stats.jsonl        每 60 秒一条运行统计（队列/丢弃/native 计数）
```

## 原理

| 层 | 手段 | 抓什么 |
| --- | --- | --- |
| Java | libxposed hook `android.util.Log.*`、`System.out/err`、`ThreadGroup.uncaughtException` | SDK / 游戏 Java 层日志、崩溃堆栈 |
| native | **PLT/GOT hook**（xHook，非 inline hook）hook `send/sendto/recv/recvfrom/write/read` | 游戏明文 TCP（C# Socket → libc） |
| native | 同上 hook `__android_log_print/_write/_vprint` | Unity / tolua / il2cpp 的 C 侧日志 |
| native | 同上 hook `SSL_write/SSL_read/*_ex` | B 站 SDK / 补丁服务的 HTTPS 明文 |
| 落盘 | 有界队列 → 独立 flush 线程 → JNI → Java 写文件；写入走 `su` 管道 | 不阻塞游戏线程 |

只用 PLT/GOT 替换（不改指令字节），对方 so 的 `.text` 段保持不变，降低被反作弊（`libanogs`）
静态校验发现的风险；模块自身也做了 `xhook_ignore` 防止自我递归。

## 构建（只走 GitHub Actions）

推送到 `main` 或手动 `workflow_dispatch` 后，Actions 用 NDK 27 + build-tools 34 编译，
产物**直接发布为 Release**（`build-<run>-<ts>`，pre-release），不从 Actions 下载 artifact。

本地不参与构建。仓库内 `signing/dev.keystore` 仅用于保持签名稳定（密码 `azcap123`），
可用 `KEYSTORE/KEYSTORE_PASS/KEY_ALIAS/KEY_PASS` 环境变量覆盖。

## 安装

1. 手机需已 root 并装好 **LSPosed**（本模块用 libxposed API 102）。
2. 安装 Release 里的 APK。
3. LSPosed 管理器里启用模块，**作用域只勾 `碧蓝航线`**。
4. 强制停止游戏再启动（作用域变更只对新 fork 的进程生效）。
5. 首次启动时 Magisk 会弹 `su` 授权，**允许**（拒绝也能跑，只是会降级写入
   `Android/data/com.bilibili.azurlane/files/logs/azurlane/`）。

## 已知限制

- 只支持 arm64-v8a（国内机型基本满足）。
- 只针对 CN 服包名；渠道服/日服/国际服不生效。
- TLS 明文依赖对方使用系统或包内 OpenSSL 1.1（`libssl.1.1.so`）；若改用 BoringSSL
  的不可见符号，`tls/` 会是空的，但 `net/` 仍有密文流量。
- 开启抓取会带来额外 IO 与 CPU 开销；长时间挂机建议只用 `net/`。
- 这是抓包/调试工具，**不修改游戏逻辑、不注入协议、不绕过校验**。

## 目录

```
app/        Java 模块（入口 ModuleMain）
native/     libazcap.so（xHook + 队列 + JNI）
resources/  META-INF/xposed/{module.prop,java_init.list,scope.list}
scripts/    build.sh（Gradle-free 构建）
docs/       用法与数据格式
```
