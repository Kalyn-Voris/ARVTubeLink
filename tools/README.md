# tools/ — VTS 探针脚本

探测 VTube Studio (VTS) 输入参数范围与模型绑定关系的辅助工具。
App 本体（`:app`）不依赖这些脚本，只在标定参数映射、调试时使用。

## 前置条件

1. Python 3.10+（脚本用了 `str | None` 等新语法）
2. 安装依赖：
   ```bash
   pip install websocket-client
   ```
3. VTS 已启动，在 **设置 → 常规 → Connect to PC/Server** 里开启 API Server
4. 首次运行或 Token 失效时 VTS 会弹授权窗，需在 **30 秒内**点「允许」

## 脚本说明

### `probe_vts_params.py` — 列出全部可注入参数

连接 VTS WebSocket API，拉 `InputParameterList`，输出所有默认参数与自定义参数的
`min` / `max` / `defaultValue`。

只读探针。不注入任何参数，不改 VTS 状态。

```bash
# 使用默认地址 127.0.0.1:8001
python tools/probe_vts_params.py

# 指定 VTS 所在机器的 IP / 端口
python tools/probe_vts_params.py --ip <VTS机器IP> --port 8001

# 强制重新申请 Token（跳过本地缓存）
python tools/probe_vts_params.py --force-token-refresh
```

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--ip` | `127.0.0.1` | VTS 所在机器 IP |
| `--port` | `8001` | VTS API 端口 |
| `--token` | 无 | 直接指定 Token，覆盖本地缓存 |
| `--force-token-refresh` | 关闭 | 跳过缓存 Token，强制重新申请 |

### `probe_mapping_range.py` — 反推参数映射的 IN 范围

VTS API 不暴露「参数映射图形的 IN 范围」（用户在 VTS 里手配的输入范围），
本脚本用主动探测法反推：

1. 向输入参数（如 `BrowLeftY`）以 30Hz 后台线程持续注入已知探针值 `v`
   （满足 VTS「每秒至少重发一次，否则回退」的要求）
2. 读全量 Live2D 参数，与注入前基线做差分，找出被驱动的 Live2D 参数
3. 画 `v → OUT` 关系曲线，反推 IN 范围候选值

```bash
# 默认探测 BrowLeftY, BrowRightY, Brows
python tools/probe_mapping_range.py

# 指定参数与探针值
python tools/probe_mapping_range.py --params MouthOpenY --probe-values 0,0.5,1
```

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--ip` | `127.0.0.1` | VTS 所在机器 IP |
| `--port` | `8001` | VTS API 端口 |
| `--params` | `BrowLeftY,BrowRightY,Brows` | 逗号分隔的输入参数名 |
| `--probe-values` | `0,0.25,0.5,0.75,1` | 逗号分隔的探针值 |

和只读的 `probe_vts_params.py` 不同，本脚本会主动注入参数驱动模型，
别在正式直播时跑。

## 关于 Token

两个脚本共用同一个本地 Token 缓存文件：

```
~/.arvtubelink_probe_token
```

- 文件在用户主目录，不在本仓库内，不会被提交
- 拿到新 Token 后自动写入，下次运行免弹窗
- Token 无效或被撤销时自动重新申请
- 认证失败时手动删掉该缓存文件即可触发重新授权

Token 等同于 VTS 插件授权凭据，别提交到版本库，也别贴到公开场合。
要撤销的话，去 VTube Studio 的插件授权列表里移除对应插件。

## 与 App 端的关系

脚本的 WebSocket 握手与认证流程和 App 端
[`app/src/main/java/com/exarry/arvtubelink/network/VtsProtocol.kt`](../app/src/main/java/com/exarry/arvtubelink/network/VtsProtocol.kt)
对齐（VTS Public API 1.0），可作为协议实现的参考。
