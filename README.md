# ARVTubeLink

把华为 AR Engine 的人脸追踪数据实时注入 VTube Studio。Android 端面捕，WebSocket 直连本地 VTS，不上云、无服务端、无账号。

```
AR Engine (BlendShapes + 头姿)
  └─ BlendShapeMapper   校准偏移 / 灵敏度 / 强度整形 / 钳制
      └─ Smoother       1€ filter（可关，退回原始直传）
          └─ VtsWsClient  30Hz 节流 → WebSocket → VTube Studio
```

## 核心特性

- **原生 AR Engine**：直接用 `com.huawei.hms:arenginesdk` 取 BlendShape 系数与头姿，不走 CameraX 或 ML Kit。
- **VTS 参数自适应探测**：向 VTS 注入已知探针值，读回 Live2D 参数做差分，反推每个模型的 IN→OUT 映射，自动生成覆盖参数。换模型不用重调。
- **全局脸部镜像**：前置摄像头成像是镜像的。开关对最终输出做左右通道互换 + 水平分量取反，不动内部公式，已存校准不受影响。
- **自定义校准与映射**：点一次校准，把当时的姿态与表情记为零点。头姿、表情、位置三条链路的灵敏度独立可调（0.10x ~ 3.00x），手动映射范围可覆盖中性点与输出量程。
- **1€ 滤波器**：静止时低截止频率压抖动，快速运动时自适应抬高，不拖影。
- **掉帧保护**：上一帧没收到 VTS 回执前不发新帧，防止延迟累积。
- **连接稳定**：解决 VTS 端协议兼容问题，支持心跳防掉线、断线自动重连、免重复授权（Token 本地保存）。

## 环境要求

| 项 | 要求 |
| --- | --- |
| 系统 | Android 12+（API 31+）或 HarmonyOS 3.x+ |
| 依赖 | 华为 AR Engine，需设备预装或从应用市场安装 |
| 设备 | 支持 AR Engine 人脸追踪的机型（华为 / 荣耀为主） |
| VTube Studio | 已开启 API Server，默认端口 8001 |
| 网络 | 手机与运行 VTS 的机器在同一局域网 |

## 快速开始

**1. 下载安装**

去 [Releases 页面](https://github.com/Kalyn-Voris/ARVTubeLink/releases) 下载最新的 APK 直接安装。

**2. 连 VTS 开端口**

VTube Studio → 设置 → 常规 → 打开 **Connect to PC/Server**，确认端口 8001。

App 首页填入运行 VTS 的机器 IP 与端口，点「连接 VTS」。VTS 会弹授权窗，**30 秒内**点允许。同意后 Token 存在本地，下次不用再点。

**3. 校准开推流**

点「检查 AR Engine」确认可用 → 点「校准（弹出对准视图）」，把脸移进暖白框、正视屏幕，浅青色网格与中心十字重合后点「确认校准」→ 点「开始追踪」。

看到 VTS 模型跟着动就通了。不动的话去「日志」页看下行报文。

## 从源码构建

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 11、Gradle 9.6.0、AGP 9.4.1、compileSdk 37。

## 已知限制

- 部分参数需在 VTS 端手动绑定到 Live2D 参数，映射图形得自己配。
- 暂不支持相机预览（部分机型取不到相机画面），校准界面只有网格。
- 自适应探测只能测出中性点偏移和输出量程。眉毛这类双极通道反推出的量程中点不等于模型静息姿态，需要的话在 VTS 端重设中性锚点。
- `MouthX` 方向翻转没有 UI 入口，用全局脸部镜像统一处理。
- 眼球与部分表情通道的增益是经验值，不同模型观感有差异，需自行微调灵敏度。

## 开源协议

本项目基于 [PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0)，详见 [LICENSE](LICENSE)，个人 VTuber 豁免条款详见 [LICENSE-ADDITIONAL.md](LICENSE-ADDITIONAL.md)。

- 个人下载、编译、本地运行、学习修改、免费分发安装包，都可以。
- 个人 VTuber 的直播与视频收益（含打赏、会员），个人视频创作者的视频收益，豁免 Noncommercial 限制。
- 售卖本体或衍生版本、集成进付费直播套件或付费数字人 SDK、公司内部商业使用，需要单独授权。

完整条款见 [app/src/main/res/raw/license.txt](app/src/main/res/raw/license.txt)。

## 关于 tools/

`tools/` 下是写映射参数时用的 VTS 探针脚本，App 本体不依赖它们。

- `probe_vts_params.py`：只读。拉 `InputParameterList`，列出所有可注入参数及其 `min` / `max` / `defaultValue`。
- `probe_mapping_range.py`：主动注入。30Hz 持续注入已知探针值，读回 Live2D 参数做差分，反推参数映射的 IN 范围。算法与 App 内自适应探测同源。

```bash
pip install websocket-client
python tools/probe_vts_params.py --ip 192.168.1.10 --port 8001
```

`probe_mapping_range.py` 会驱动模型动作，别在正式直播时跑。用法见 [tools/README.md](tools/README.md)。
