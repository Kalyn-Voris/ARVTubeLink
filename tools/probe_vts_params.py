#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe_vts_params.py — 连接 VTube Studio WebSocket API，抓取全部可注入参数的 min/max/defaultValue。

协议与 App 端 network/VtsProtocol.kt 对齐（VTS Public API 1.0）：
  1. WebSocket 连接 ws://<ip>:<port>
  2. 两段式认证：
     a. 先用缓存 token（~/.arvtubelink_probe_token）或 --token 发 AuthenticationRequest；
     b. 认证失败（token 无效/被撤销）→ 发 AuthenticationTokenRequest 请求 VTS 颁发新 token，
        用户在 VTS 界面 30 秒内点「允许」→ 拿到新 token 再发 AuthenticationRequest，
        成功后写回 ~/.arvtubelink_probe_token 供下次复用。
  3. InputParameterListRequest → 解析 defaultParameters + customParameters

只读探针：不注入任何参数，不写入任何状态（除本地 token 缓存文件）。

依赖：pip install websocket-client
运行：python tools/probe_vts_params.py [--ip 127.0.0.1] [--port 8001] [--token <token>]
      [--force-token-refresh]

前置条件：VTS 已启动且 API Server 已开启（设置 → 常规 → Connect to PC/Server ON）、IP/端口正确。
"""

import argparse
import json
import sys
import time
import uuid
from pathlib import Path

try:
    import websocket  # websocket-client
except ImportError:
    print("缺少依赖，请先执行：pip install websocket-client")
    sys.exit(1)
DEFAULT_IP = "127.0.0.1"
DEFAULT_PORT = 8001
TOKEN_CACHE = Path.home() / ".arvtubelink_probe_token"

API_NAME = "VTubeStudioPublicAPI"
API_VERSION = "1.0"
PLUGIN_NAME = "ARVTubeLink-Probe"
PLUGIN_DEVELOPER = "exarry"

# 插件名带时间戳：token 申请与认证两处必须字面一致，统一在此生成后引用
PROBE_PLUGIN_NAME = f"{PLUGIN_NAME}-{time.strftime('%Y%m%d')}"

CONNECT_COUNT = 0  # 连接建立次数（排查连接复用问题用）

# App 侧重点对齐的参数（任务清单）
INTERESTED = {
    "FacePositionX", "FacePositionY", "FacePositionZ",
    "FaceAngleX", "FaceAngleY", "FaceAngleZ",
    "EyeOpenLeft", "EyeOpenRight",
    "EyeLeftX", "EyeLeftY", "EyeRightX", "EyeRightY",
    "MouthOpen", "MouthSmile", "MouthX",
    "BrowLeftY", "BrowRightY", "Brows",
    "CheekPuff", "TongueOut", "FaceAngry",
}

RECV_TIMEOUT = 15      # 普通收包超时（秒）
TOKEN_WINDOW = 30      # VTS 授权弹窗等待窗口（秒），与 VTS 官方一致


def make_request(message_type: str, request_id: str, data: dict | None = None) -> str:
    payload = {
        "apiName": API_NAME,
        "apiVersion": API_VERSION,
        "requestID": request_id,
        "messageType": message_type,
    }
    if data is not None:
        payload["data"] = data
    return json.dumps(payload, ensure_ascii=False)


def recv_message(ws: websocket.WebSocket, want_type: str, timeout: float = RECV_TIMEOUT) -> dict:
    """阻塞收包直到拿到 messageType == want_type 的消息；遇到 APIError 直接终止。"""
    ws.settimeout(timeout)
    last_seen: list[str] = []
    while True:
        try:
            raw = ws.recv()
        except websocket.WebSocketTimeoutException:
            hint = f"（等待期间实际收到的 messageType：{last_seen}）" if last_seen else "（期间未收到任何消息）"
            raise TimeoutError(f"等待 {want_type} 超时（{timeout} 秒）{hint}")
        if isinstance(raw, bytes):
            raw = raw.decode("utf-8", errors="replace")
        msg = json.loads(raw)
        mtype = msg.get("messageType", "")
        if mtype == "APIError":
            data = msg.get("data", {})
            raise RuntimeError(
                f"VTS 返回 APIError: errorID={data.get('errorID')} message={data.get('message')}"
            )
        if mtype == want_type:
            return msg
        last_seen.append(mtype or "(空)")


def load_cached_token() -> str | None:
    try:
        token = TOKEN_CACHE.read_text(encoding="utf-8").strip()
        return token or None
    except OSError:
        return None


def save_token(token: str) -> None:
    try:
        TOKEN_CACHE.write_text(token + "\n", encoding="utf-8")
        print(f"新 token 已缓存到 {TOKEN_CACHE}（下次自动复用）")
    except OSError as e:
        print(f"token 缓存写入失败（不影响本次运行）：{e}")


def authenticate(ws: websocket.WebSocket, token: str) -> None:
    """用给定 token 发认证请求；失败抛 RuntimeError。"""
    print(f"[{time.strftime('%H:%M:%S')}] AuthenticationRequest: pluginName={PROBE_PLUGIN_NAME!r} "
          f"pluginDeveloper={PLUGIN_DEVELOPER!r} token前8位={token[:8]} conn_id={id(ws)}")
    req_id = uuid.uuid4().hex[:8]
    ws.send(make_request("AuthenticationRequest", req_id, {
        "pluginName": PROBE_PLUGIN_NAME,
        "pluginDeveloper": PLUGIN_DEVELOPER,
        "authenticationToken": token,
    }))
    auth = recv_message(ws, "AuthenticationResponse")
    auth_data = auth.get("data", {})
    if not auth_data.get("authenticated"):
        req = make_request("AuthenticationRequest", req_id, {
            "pluginName": PROBE_PLUGIN_NAME,
            "pluginDeveloper": PLUGIN_DEVELOPER,
            "authenticationToken": token[:8] + "...(已脱敏)",
        })
        print(f"认证失败。实际发出的 AuthenticationRequest JSON：{truncate(req)}")
        raise RuntimeError(auth_data.get("reason") or "认证失败（reason 为空）")


def truncate(text: str, limit: int = 500) -> str:
    return text if len(text) <= limit else text[:limit] + f"...(共 {len(text)} 字符，已截断)"


def request_new_token(ws: websocket.WebSocket) -> str:
    """向 VTS 申请新 token：用户需在 VTS 界面 30 秒内点「允许」。"""
    req_id = f"arvtubelink-probe-{uuid.uuid4().hex[:8]}"
    print(f"[{time.strftime('%H:%M:%S')}] AuthenticationTokenRequest: pluginName={PROBE_PLUGIN_NAME!r} "
          f"pluginDeveloper={PLUGIN_DEVELOPER!r} conn_id={id(ws)}")
    ws.send(make_request("AuthenticationTokenRequest", req_id, {
        "pluginName": PROBE_PLUGIN_NAME,
        "pluginDeveloper": PLUGIN_DEVELOPER,
    }))
    print(f"已发送 token 申请（插件名 {PROBE_PLUGIN_NAME}），请在 VTS 界面上点击「允许」，有 {TOKEN_WINDOW} 秒窗口 ...")
    print("注意：请到 VTS 窗口查看是否有授权弹窗，可能被其他窗口遮挡或最小化。")
    try:
        resp = recv_message(ws, "AuthenticationTokenResponse", timeout=TOKEN_WINDOW)
    except TimeoutError:
        print("VTS 未响应，检查 VTS 是否在前台（授权弹窗只在 VTS 可见时出现）。")
        print("另请检查 VTS 设置中插件/API 访问类开关（如 Allow plugin API access）是否开启，"
              "关着时申请会不弹窗直接静默失败。")
        sys.exit(4)

    raw = json.dumps(resp, ensure_ascii=False)
    print(f"收到响应（原始，截断 500 字符）：{truncate(raw)}")

    data = resp.get("data")
    if data is None or not isinstance(data, dict) or not data:
        # VTS 拒绝授权时 data 里可能带 errorMessage
        err = data.get("errorMessage") if isinstance(data, dict) else None
        print(f"data 为空或缺失{f'，errorMessage={err}' if err else ''}——可能用户在 VTS 端拒绝了授权。")
        sys.exit(5)

    err = data.get("errorMessage")
    if err:
        print(f"VTS 返回 errorMessage：{err}")
        sys.exit(5)

    # 官方文档字段为 data.token；部分版本/资料写作 data.authenticationToken——两者都接受
    token = data.get("token") or data.get("authenticationToken")
    if not token:
        print("data 中既无 token 也无 authenticationToken 字段。")
        print(f"data 实际内容：{truncate(json.dumps(data, ensure_ascii=False), 500)}")
        print("请把上面的原始响应贴回对话，按真实字段名修正解析。")
        sys.exit(5)
    return token


def connect_and_authenticate(args) -> websocket.WebSocket:
    """建立连接并完成认证（两段式），返回已认证的 WebSocket。"""
    global CONNECT_COUNT
    url = f"ws://{args.ip}:{args.port}"
    print(f"连接 {url} ...")
    try:
        ws = websocket.create_connection(url, timeout=RECV_TIMEOUT)
    except (ConnectionRefusedError, OSError) as e:
        print(f"WebSocket 连接失败：{e}")
        print("请检查 IP/端口是否正确、VTS 服务端是否开启（设置 → 常规 → Connect to PC/Server ON）。")
        sys.exit(1)
    CONNECT_COUNT += 1
    print(f"[{time.strftime('%H:%M:%S')}] 连接建立成功（第 {CONNECT_COUNT} 次，conn_id={id(ws)}）。"
          f"全程复用此连接，不再重连。")

    try:
        token = args.token or load_cached_token()

        # --force-token-refresh：跳过旧 token，直接申请
        if args.force_token_refresh or not token:
            if args.force_token_refresh:
                print("--force-token-refresh：跳过已有 token，直接申请新 token。")
            new_token = request_new_token(ws)
            # VTS 颁发 token 后可能需要短暂时间完成内部注册，稍等再认证
            time.sleep(0.3)
            authenticate(ws, new_token)
            save_token(new_token)
            print("认证成功（新 token）。")
            return ws

        print("使用缓存/命令行 token 认证 ...")
        try:
            authenticate(ws, token)
            print("认证成功（已有 token）。")
            return ws
        except RuntimeError as e:
            reason = str(e)
            # token 无效/被撤销 → 自动申请新 token（仍在同一连接上）
            if "invalid" in reason.lower() or "revoked" in reason.lower():
                print(f"token 无效或已被撤销（{reason}），自动申请新 token（同一连接）...")
                new_token = request_new_token(ws)
                time.sleep(0.3)
                authenticate(ws, new_token)
                save_token(new_token)
                print("认证成功（新 token）。")
                return ws
            raise
    except Exception:
        ws.close()
        raise


def fetch_params(ws: websocket.WebSocket) -> dict:
    req_id = uuid.uuid4().hex[:8]
    ws.send(make_request("InputParameterListRequest", req_id))
    return recv_message(ws, "InputParameterListResponse")


def print_report(resp: dict) -> None:
    data = resp.get("data", {})
    model_loaded = data.get("modelLoaded")
    model_name = data.get("modelName", "(未知)")
    params = list(data.get("defaultParameters") or []) + list(data.get("customParameters") or [])

    if not params:
        print("响应中没有任何参数（模型可能未加载，或 VTS 版本异常）。原始响应：")
        print(json.dumps(resp, ensure_ascii=False, indent=2))
        sys.exit(3)

    out = []
    out.append(f"模型：{model_name}（modelLoaded={model_loaded}），共 {len(params)} 个可注入参数")
    out.append("")
    out.append("## 重点参数（App 对齐清单）")
    out.append("")
    out.append("| name | min | max | defaultValue |")
    out.append("|---|---|---|---|")
    interested_rows = [
        f"| {p.get('name')} | {p.get('min')} | {p.get('max')} | {p.get('defaultValue')} |"
        for p in params if p.get("name") in INTERESTED
    ]
    if interested_rows:
        out.extend(interested_rows)
    else:
        out.append("(重点清单中没有任何参数出现在本次响应中)")

    missing = sorted(INTERESTED - {p.get("name") for p in params})
    out.append("")
    if missing:
        out.append(f"重点清单中未出现的参数：{', '.join(missing)}")
    else:
        out.append("重点清单参数全部存在。")

    out.append("")
    out.append("## 全部参数")
    out.append("")
    out.append("| name | min | max | defaultValue | 重点 |")
    out.append("|---|---|---|---|---|")
    for p in params:
        name = p.get("name", "")
        mark = " ★" if name in INTERESTED else ""
        out.append(f"| {name} | {p.get('min')} | {p.get('max')} | {p.get('defaultValue')} |{mark} |")

    report = "\n".join(out)
    # Windows 控制台默认 GBK，强制按 UTF-8 输出避免中文/特殊字符乱码
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print()
    print(report)


def main() -> None:
    parser = argparse.ArgumentParser(description="抓取 VTS 可注入参数范围（只读）")
    parser.add_argument("--ip", default=DEFAULT_IP, help=f"VTS 主机 IP（默认 {DEFAULT_IP}）")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"VTS API 端口（默认 {DEFAULT_PORT}）")
    parser.add_argument("--token", default=None, help="认证 token（覆盖缓存文件）")
    parser.add_argument("--force-token-refresh", action="store_true",
                        help="跳过已有 token，强制向 VTS 重新申请（需在弹窗点「允许」）")
    args = parser.parse_args()

    ws = connect_and_authenticate(args)
    try:
        resp = fetch_params(ws)
    finally:
        ws.close()

    print_report(resp)
    print()
    print("请在终端运行：python tools/probe_vts_params.py")
    print("提醒：确保 VTS 已启动、API Server 已开启（设置 → 常规 → Connect to PC/Server ON）；"
          "首次运行或 token 失效时，VTS 会弹授权窗，请在 30 秒内点「允许」。")


if __name__ == "__main__":
    main()
