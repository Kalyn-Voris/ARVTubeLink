#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""探测 VTS 参数映射的 IN 范围（注入输入参数 → 读回 Live2D 参数）.

VTS API 不暴露"参数映射图形的 IN 范围"（用户手动在 VTS 里配的范围），
本脚本用探测法反推：
  1. 向输入参数（如 BrowLeftY）注入已知探针值 v（后台线程 30Hz 持续注入，
     满足 VTS"每秒至少重发一次否则回退"的要求）
  2. 读全量 Live2D 参数，与注入前基线差分，找出被驱动的 Live2D 参数
  3. 画 v → OUT 关系，反推 IN 范围候选

用法:
    python tools/probe_mapping_range.py
    python tools/probe_mapping_range.py --params MouthOpenY --probe-values 0,0.5,1

依赖: pip install websocket-client
"""

import argparse
import json
import os
import sys
import threading
import time
from collections import Counter

try:
    import websocket  # websocket-client
except ImportError:
    print("缺少依赖：请先执行  pip install websocket-client")
    sys.exit(1)

API_NAME = "VTubeStudioPublicAPI"
API_VERSION = "1.0"
PLUGIN_NAME = "ARVTubeLink Probe"
PLUGIN_DEVELOPER = "ARVTubeLink"
TOKEN_PATH = os.path.join(os.path.expanduser("~"), ".arvtubelink_probe_token")

INJECT_HZ = 30.0
SETTLE_SECONDS = 0.2   # 注入后等待（约 12 帧，覆盖平滑拖尾）
RELEASE_SECONDS = 0.2  # 停止注入后的基础等待
REVERT_SECONDS = 1.0   # VTS 停止注入 1 秒后才回退参数值，探下一个参数前必须等满
DIFF_EPSILON = 0.01    # 差分抖动忽略阈值
VISIBLE_DIFF = 0.05    # 差分可见性阈值
CLAMP_EPSILON = 0.01   # 钳制判定（OUT 贴 Live2D min/max）

PROBE_TARGETS = [
    ("BrowLeftY", "Live2D 命名惯例对应 ParamBrowLY（左眉 Y）"),
    ("BrowRightY", "Live2D 命名惯例对应 ParamBrowRY（右眉 Y）"),
    ("Brows", "miku 模型眉毛绑定的实际输入源"),
]


class VTSAPIError(Exception):
    """VTS 返回 APIError（如参数不存在、用户拒绝授权）."""


class VTSDisconnected(Exception):
    """WebSocket 断开或响应超时."""


class VTSClient:
    """单连接 VTS WebSocket 客户端：读线程 + 按 requestID 配对响应."""

    def __init__(self, ip, port):
        self._url = f"ws://{ip}:{port}"
        self._ws = None
        self._send_lock = threading.Lock()
        self._resp_lock = threading.Lock()
        self._responses = {}  # requestID -> data 或 VTSAPIError 实例
        self._events = {}     # requestID -> threading.Event
        self._req_counter = 0
        self._connected = False

    def connect(self, timeout=5.0):
        self._ws = websocket.create_connection(self._url, timeout=timeout)
        self._connected = True
        threading.Thread(target=self._read_loop, daemon=True).start()

    def close(self):
        self._connected = False
        try:
            self._ws.close()
        except Exception:
            pass

    def _read_loop(self):
        try:
            while True:
                try:
                    raw = self._ws.recv()
                except websocket.WebSocketTimeoutException:
                    continue  # 心跳空闲，继续等
                if not isinstance(raw, str):
                    continue
                msg = json.loads(raw)
                if msg.get("messageType") == "Ping":
                    self._send({"messageType": "Pong"})
                    continue
                rid = msg.get("requestID")
                if rid is None:
                    continue
                with self._resp_lock:
                    ev = self._events.get(rid)
                if ev is None:
                    continue  # fire-and-forget 注入的响应，丢弃
                if msg.get("messageType") == "APIError":
                    data = msg.get("data") or {}
                    self._responses[rid] = VTSAPIError(
                        f"errorID={data.get('errorID')} {data.get('message', '')}"
                    )
                else:
                    self._responses[rid] = msg.get("data") or {}
                ev.set()
        except Exception:
            self._connected = False
            with self._resp_lock:
                events = list(self._events.values())
            for ev in events:  # 唤醒所有等待者，让 request() 报断线
                ev.set()

    def _send(self, payload):
        with self._send_lock:
            self._ws.send(json.dumps(payload, ensure_ascii=False))

    def request(self, mtype, data=None, timeout=5.0):
        if not self._connected:
            raise VTSDisconnected("WebSocket 未连接")
        self._req_counter += 1
        rid = f"probe-{self._req_counter}"
        ev = threading.Event()
        with self._resp_lock:
            self._events[rid] = ev
        self._send({
            "apiName": API_NAME,
            "apiVersion": API_VERSION,
            "requestID": rid,
            "messageType": mtype,
            "data": data or {},
        })
        if not ev.wait(timeout):
            raise VTSDisconnected(f"{mtype} 等待响应超时")
        with self._resp_lock:
            self._events.pop(rid, None)
            resp = self._responses.pop(rid, None)
        if resp is None:
            raise VTSDisconnected("连接已断开")
        if isinstance(resp, VTSAPIError):
            raise resp
        return resp

    def fire_inject(self, param, value):
        """注入一条探针值，不等待响应（30Hz 后台线程用；错误靠首次同步注入暴露）."""
        self._send({
            "apiName": API_NAME,
            "apiVersion": API_VERSION,
            "messageType": "InjectParameterDataRequest",
            "data": {
                "faceFound": False,
                "mode": "set",
                "parameterValues": [{"id": param, "value": value}],
            },
        })


class Injector:
    """30Hz 后台注入线程。start() 首次同步注入（让参数不存在的错误直接暴露），stop() 停止."""

    def __init__(self, client, param, value):
        self._client = client
        self._param = param
        self._value = value
        self._stop = threading.Event()

    def start(self):
        self._client.request("InjectParameterDataRequest", {
            "faceFound": False,
            "mode": "set",
            "parameterValues": [{"id": self._param, "value": self._value}],
        })
        self._stop.clear()
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        interval = 1.0 / INJECT_HZ
        while not self._stop.is_set():
            try:
                self._client.fire_inject(self._param, self._value)
            except Exception:
                return  # 连接断开，读线程会让主流程报错退出
            time.sleep(interval)

    def stop(self):
        self._stop.set()
        time.sleep(1.0 / INJECT_HZ)  # 等最后一发注入发出


def _read_token():
    try:
        with open(TOKEN_PATH, "r", encoding="utf-8") as f:
            token = f.read().strip()
            return token or None
    except OSError:
        return None


def _write_token(token):
    with open(TOKEN_PATH, "w", encoding="utf-8") as f:
        f.write(token)


def authenticate(client):
    """复用 ~/.arvtubelink_probe_token；失效则走 TokenRequest 重新授权."""
    token = _read_token()
    if token:
        resp = client.request("AuthenticationRequest", {
            "pluginName": PLUGIN_NAME,
            "pluginDeveloper": PLUGIN_DEVELOPER,
            "authenticationToken": token,
        })
        if resp.get("authenticated"):
            print("认证成功（复用已有 token）")
            return
        print("已有 token 失效，需要重新授权")
    print(f"请在 VTS 弹窗中点击「允许」，授权插件「{PLUGIN_NAME}」…")
    resp = client.request("AuthenticationTokenRequest", {
        "pluginName": PLUGIN_NAME,
        "pluginDeveloper": PLUGIN_DEVELOPER,
    }, timeout=60.0)
    _write_token(resp["authenticationToken"])
    resp = client.request("AuthenticationRequest", {
        "pluginName": PLUGIN_NAME,
        "pluginDeveloper": PLUGIN_DEVELOPER,
        "authenticationToken": resp["authenticationToken"],
    })
    if not resp.get("authenticated"):
        raise VTSAPIError("重新授权后认证仍失败，请检查 VTS 的 API 设置")
    print("认证成功（新 token 已保存）")


def read_live2d(client):
    """读全量 Live2D 参数：{name: {value, min, max}}，附响应供取模型名."""
    resp = client.request("Live2DParameterListRequest", {}, timeout=10.0)
    if not resp.get("modelLoaded"):
        raise VTSAPIError("未检测到已加载模型（modelLoaded=false），请在 VTS 中加载模型后重试")
    params = resp.get("parameters") or resp.get("modelParameters") or []
    snapshot = {
        p["name"]: {
            "value": float(p.get("value", 0)),
            "min": float(p.get("min", 0)),
            "max": float(p.get("max", 0)),
        }
        for p in params
    }
    return snapshot, resp


def probe_param(client, param, probe_values):
    """对单个输入参数跑全部探针值，返回 rows=[(v, top差分列表, B0, B1)] 并打表."""
    print(f"\n### 探测参数: {param}")
    b0, _ = read_live2d(client)
    rows = []
    for v in probe_values:
        inj = Injector(client, param, v)
        try:
            inj.start()
        except VTSAPIError as e:
            print(f"  注入失败（VTS 报错）：{e}")
            print(f"  该参数在 VTS 中不存在，跳过 {param}")
            return []
        time.sleep(SETTLE_SECONDS)
        b1, _ = read_live2d(client)
        inj.stop()
        time.sleep(RELEASE_SECONDS)
        diffs = []
        for name, info in b1.items():
            if name in b0:
                d = info["value"] - b0[name]["value"]
                if abs(d) >= DIFF_EPSILON:
                    diffs.append((name, d, b0[name]["value"], info["value"]))
        diffs.sort(key=lambda x: -abs(x[1]))
        top = diffs[:3]
        if not top or abs(top[0][1]) <= VISIBLE_DIFF:
            peak = f"{top[0][1]:+.3f}" if top else "0.000"
            print(f"  警告：探针值 {v:.2f} 注入未引起可见变化（最大差分 {peak}），"
                  f"可能参数未绑定或映射已被钳制")
        rows.append((v, top))
        for i, (name, d, base, after) in enumerate(top):
            probe_cell = f"{v:.2f}" if i == 0 else ""
            print(f"| {probe_cell} | {name} | {base:.2f} | {after:.2f} | {d:+.2f} |")
    print()
    print("| 探针值 | 变化的 Live2D 参数 | Live2D 基线值 | Live2D 探测值 | 变化量 |")
    print("|--------|---------------------|---------------|---------------|--------|")
    for v, top in rows:
        if not top:
            print(f"| {v:.2f} | （无变化） | - | - | - |")
        for i, (name, d, base, after) in enumerate(top):
            probe_cell = f"{v:.2f}" if i == 0 else ""
            print(f"| {probe_cell} | {name} | {base:.2f} | {after:.2f} | {d:+.2f} |")
    return rows


def _crossing(pts, level, last=False):
    """探针点序列中 OUT 首次/末次穿越 level 的 v（线性插值）。找不到返回 None."""
    found = None
    for (v1, o1), (v2, o2) in zip(pts, pts[1:]):
        if o1 == o2:
            continue
        if (o1 - level) * (o2 - level) <= 0:
            t = (level - o1) / (o2 - o1)
            found = v1 + t * (v2 - v1)
            if not last:
                return found
    return found


def summarize(param, rows, snapshot):
    """对单个参数出方法论评估：关联参数 / 5 点充分性 / IN 范围候选."""
    if not rows:
        return
    top1_names = [top[0][0] for _, top in rows if top]
    if not top1_names:
        print(f"- **{param}**：所有探针值均未驱动任何 Live2D 参数，无法关联目标。")
        return
    primary, hits = Counter(top1_names).most_common(1)[0]
    pts = [(v, top[0][3]) for v, top in rows if top and top[0][0] == primary]
    info = snapshot.get(primary, {})
    lmin, lmax = info.get("min", 0.0), info.get("max", 0.0)
    total_travel = sum(abs(top[0][1]) for _, top in rows if top and top[0][0] == primary)
    print(f"- **{param}**：关联 Live2D 参数 `{primary}`"
          f"（top1 出现 {hits}/{len(top1_names)} 次，量程 [{lmin:.2f}, {lmax:.2f}]，"
          f"总变化 {total_travel:.2f}）")

    # 5 点充分性：相邻探针点是否两点同侧钳在极限（说明区间偏粗、拐点被跳过）
    refine_hint = None
    for (v1, o1), (v2, o2) in zip(pts, pts[1:]):
        low_clamped = (o1 <= lmin + CLAMP_EPSILON and o2 <= lmin + CLAMP_EPSILON)
        high_clamped = (o1 >= lmax - CLAMP_EPSILON and o2 >= lmax - CLAMP_EPSILON)
        if low_clamped or high_clamped:
            refine_hint = (v1, v2)
    if refine_hint:
        print(f"  5 点充分性：不足——探针 {refine_hint[0]:.2f} 与 {refine_hint[1]:.2f}"
              f" 均钳在同一极限，建议在二者之间二分细化")
    else:
        print("  5 点充分性：足够（无相邻双点同侧钳制）")

    # IN 范围候选：假设映射线性，OUT 从 min 走到 max 对应的 v 区间
    lo = hi = None
    if pts[0][1] <= lmin + CLAMP_EPSILON:
        lo = _crossing(pts, lmin)
        lo_text = f"{lo:.3f}" if lo is not None else ">1.00（探针 1 仍未达下限→上限的爬升）"
    else:
        lo_text = "<0.00（探针 0 已越过 Live2D 下限）"
    if pts[-1][1] >= lmax - CLAMP_EPSILON:
        hi = _crossing(pts, lmax, last=True)
        hi_text = f"{hi:.3f}" if hi is not None else "<0.00"
    else:
        hi_text = ">1.00（探针 1 未触及 Live2D 上限）"
    print(f"  IN 范围候选（假设线性）：[{lo_text}, {hi_text}]"
          f"——v 低于下候选时 OUT 钳在 {lmin:.2f}，高于上候选时钳在 {lmax:.2f}")


def main():
    parser = argparse.ArgumentParser(description="探测 VTS 参数映射的 IN 范围")
    parser.add_argument("--ip", default="127.0.0.1", help="VTS 所在机器 IP")
    parser.add_argument("--port", default=8001, type=int, help="VTS API 端口")
    parser.add_argument("--params", default="BrowLeftY,BrowRightY,Brows",
                        help="逗号分隔的输入参数名")
    parser.add_argument("--probe-values", default="0,0.25,0.5,0.75,1",
                        help="逗号分隔的探针值")
    args = parser.parse_args()

    if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

    params = [x.strip() for x in args.params.split(",") if x.strip()]
    probe_values = [float(x) for x in args.probe_values.split(",") if x.strip()]
    probe_values.sort()

    client = VTSClient(args.ip, args.port)
    try:
        client.connect()
    except Exception as e:
        print(f"无法连接 VTS ws://{args.ip}:{args.port}：{e}")
        sys.exit(1)
    print(f"已连接 ws://{args.ip}:{args.port}")

    try:
        authenticate(client)
    except VTSAPIError as e:
        print(f"认证失败：{e}")
        print("请在 VTS 中允许插件访问后重跑；若 token 已撤销，删除"
              f" {TOKEN_PATH} 可触发重新授权")
        sys.exit(1)
    except VTSDisconnected as e:
        print(f"连接断开：{e}")
        sys.exit(1)

    input("\n请先关闭 ARVTubeLink App 的追踪（否则注入会冲突）。关闭后按 Enter 继续…")

    try:
        snapshot, resp = read_live2d(client)
    except (VTSAPIError, VTSDisconnected) as e:
        print(f"读取基线失败：{e}")
        sys.exit(1)
    print(f"模型：{resp.get('modelName')}（Live2D 参数 {len(snapshot)} 项，已存基线快照）")

    results = {}
    for i, param in enumerate(params):
        if i > 0:
            # 上一个参数停止注入后要等满 VTS 的 1 秒回退期，
            # 否则其残留注入值会污染下一个参数的基线 B0
            time.sleep(REVERT_SECONDS)
        rows = probe_param(client, param, probe_values)
        if rows:
            results[param] = rows

    print("\n## 方法论评估")
    for param in params:
        summarize(param, results.get(param, []), snapshot)
    print("\n注：IN 范围候选假设映射线性且 VTS 侧无平滑拖尾；"
          "若 VTS 开了平滑，等待 200ms 可能不够，读回值偏小、候选区间会偏宽。")


if __name__ == "__main__":
    main()
