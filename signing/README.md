# 签名

私钥**不入库**。CI 从仓库 Secrets 读取并还原：

| Secret | 值 |
|---|---|
| `KEYSTORE_B64` | `base64 -w0 dev.keystore` 的输出 |
| `KEYSTORE_PASS` | keystore 密码 |
| `KEY_ALIAS` | key 别名（默认 `azcap`） |
| `KEY_PASS` | key 密码 |

`scripts/build.sh` 有 `KEYSTORE_B64` 时解码到 `.build-cache/signing/dev.keystore`；本地没有该变量时回退到 `signing/dev.keystore`（自行放置）。
