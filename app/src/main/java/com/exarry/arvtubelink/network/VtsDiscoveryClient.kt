package com.exarry.arvtubelink.network

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 局域网 VTube Studio 自动发现（v1.2.5）：UDP 广播监听为主 + TCP 端口扫描兜底的双路竞速。
 *
 * 官方依据（DenchiSoft/VTubeStudio README「API Server Discovery (UDP)」节，逐字核实）：
 * VTS 每 2 秒经 UDP 47779 广播一次 API 状态（即使 API 开关关闭也发），信封含
 * messageType = VTubeStudioAPIStateBroadcast、data.port（WS 端口，默认 8001）、
 * data.active（API 开关）、data.instanceID；包体不含 IP，UDP 源地址即 VTS 所在电脑。
 * （注：21412/21413 不在官方文档，坊间记忆有误，勿改回。）
 *
 * 双路时序：T0 起 UDP 监听（窗口 UDP_WINDOW_MS）；T3（UDP bind 失败则 T0.5）起 TCP
 * 兜底并发扫网段 8001（单点 200ms 超时、32 并发）；任一路命中立即返回并取消另一路；
 * 两路都收工无命中提前终局（不等总闸）；TOTAL_DEADLINE_MS 总闸只兜异常挂起。
 * 结果只含 Found/Failed 两种，isApiActive 供 UI 层执行裁定「API 未开启只填入不连接」。
 *
 * 生命周期（红线②）：discover() 为挂起函数，由 UI 层 rememberCoroutineScope 启动；
 * Activity 销毁 = 协程取消 → UDP 收包循环（soTimeout 500ms 轮询）在 ≤500ms 内经 isActive
 * 检查自然退出并由 finally 关套接字、释放 MulticastLock（阻塞 receive 不响应取消与线程
 * 中断，唯有 close 能打断——轮询规避挂 Job 取消钩子），TCP 扫描随结构化并发整体取消，
 * 无泄漏。本类只持 applicationContext，绝不持 Activity。UI 层经状态守卫保证同一时间
 * 只有一个 discover() 在跑；每个 discover() 自建自毁套接字，无跨调用共享状态。
 *
 * 已知限制（实现时裁定记录）：
 *  - 路由器 AP 隔离（客户端隔离）同时挡死 UDP 广播与 TCP 对扫，软件无解 → 超时文案引导；
 *  - TCP 兜底固定扫 8001：VTS 改过 WS 端口的场景兜底无效（UDP 路不受影响，端口在包体里）；
 *  - 网段按 /24 假设推导（家庭网段几乎全部如此），非 /24 掩码时兜底漏扫；
 *  - TCP 命中不验证对端身份（8001 可连即候选），误命中由既有连接失败路径兜住；
 *  - 模拟器（MuMu 等）NAT 下 UDP 广播大概率被虚拟网卡挡住 = 预期行为，验收以真机为准。
 *
 * 红线①：搜索逻辑唯一落点在本文件，不动 VtsWsClient / VtsProtocol——命中后由 UI 层
 * 复用既有 connectVts 链路建连。
 */
class VtsDiscoveryClient(context: Context) {

    /** 只持 applicationContext：防 Activity 泄漏（MulticastLock/WifiManager 由此取）。 */
    private val appContext: Context = context.applicationContext

    /**
     * 发现结果。Found.isApiActive：UDP 路取广播 data.active；TCP 路恒 true——TCP 层
     * 无法探知 API 开关，且 API 关闭时 8001 通常不监听，该路基本不会命中关闭的实例。
     */
    sealed interface DiscoveryResult {
        data class Found(val ip: String, val port: Int, val isApiActive: Boolean) : DiscoveryResult
        data class Failed(val reason: Reason) : DiscoveryResult

        enum class Reason {
            /** 无可用局域网 IPv4（Wi-Fi/以太网未连，或地址非私有网段）。 */
            NO_NETWORK,

            /** 双路收工/总闸到期仍无命中（含 AP 隔离场景）。 */
            TIMEOUT,
        }
    }

    /**
     * 执行一次发现（挂起，可随时取消）。调用方约定：同一时间只应有一个在跑（UI 层
     * 状态守卫防重入）；重复调用各自独立，互不共享套接字。
     */
    suspend fun discover(): DiscoveryResult = coroutineScope {
        val localIp = pickPrivateIpv4()
            ?: return@coroutineScope DiscoveryResult.Failed(DiscoveryResult.Reason.NO_NETWORK)
        val subnetPrefix = subnetPrefixOf(localIp)
        val hit = CompletableDeferred<DiscoveryResult.Found>()
        val udpBound = CompletableDeferred<Boolean>()

        val udpJob = launch(Dispatchers.IO) { listenUdpBroadcast(hit, udpBound) }
        val tcpJob = launch(Dispatchers.IO) {
            // bind 结果几乎即时（成功与 BindException 都发生在 T0 附近）：端口被占则兜底提前
            val bound = runCatching { udpBound.await() }.getOrDefault(false)
            delay(if (bound) TCP_FALLBACK_DELAY_MS else TCP_EARLY_FALLBACK_DELAY_MS)
            if (!hit.isCompleted) scanTcpSubnet(subnetPrefix, hit)
        }

        val found = withTimeoutOrNull(TOTAL_DEADLINE_MS) {
            // 收敛顺序：先等 UDP 路收工（窗口结束或命中）——命中则直接取值返回并掐掉
            // TCP 兜底；未命中再等 TCP 扫描收工，两路都到且无命中 = 提前终局（AP 隔离
            // 等失败场景不等 8s 总闸，典型 T5 出结果）
            udpJob.join()
            if (!hit.isCompleted) tcpJob.join()
            if (hit.isCompleted) hit.await() else null
        }
        udpJob.cancel()
        tcpJob.cancel()
        if (found != null) found else DiscoveryResult.Failed(DiscoveryResult.Reason.TIMEOUT)
    }

    /**
     * UDP 路：绑定官方广播端口 47779 收 VTS 广播（受限广播 + 组播路径一网打尽，无需
     * 加入组播组）。窗口 UDP_WINDOW_MS 自灭；首个合法包即命中（多实例裁定 = 竞速取
     * 第一个，不做列表）。收包线程为 Dispatchers.IO（阻塞 receive 占住线程属预期）。
     */
    private suspend fun listenUdpBroadcast(
        hit: CompletableDeferred<DiscoveryResult.Found>,
        udpBound: CompletableDeferred<Boolean>,
    ) {
        val lock = acquireMulticastLock()
        try {
            val socket = DatagramSocket(null)
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(UDP_BROADCAST_PORT))
                udpBound.complete(true)
                // soTimeout 轮询收包：阻塞 receive 不响应协程取消，DatagramSocket 也不响应
                // 线程中断（Android libcore 行为），唯有 close 能打断——轮询让循环周期性
                // 回到可检查 isActive 的位置，取消后 ≤500ms 自然退出并走 finally 关套接字，
                // 无需挂 Job 取消钩子（该 API 在本工程协程版本上不可用）
                socket.soTimeout = UDP_POLL_INTERVAL_MS.toInt()
                val buf = ByteArray(MAX_PACKET_BYTES)
                val deadline = System.currentTimeMillis() + UDP_WINDOW_MS
                while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue // 轮询空转：回循环头检查取消/窗口后继续收
                    }
                    val found = parseBroadcast(buf, packet) ?: continue
                    hit.complete(found)
                    break
                }
            } catch (e: IOException) {
                // 两类来源，统一按「UDP 路不可用」降级（对已完成的 true 无副作用）：
                //  1. bind 失败（端口被其他应用占用）→ TCP 兜底提前启动
                //  2. 收包中途网络异常 → TCP 兜底照常在 3s 启动
                udpBound.complete(false)
            } finally {
                runCatching { socket.close() }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    /**
     * TCP 兜底：并发探测本机 /24 网段 1..254 的 8001 端口（VTS 默认 WS 端口，见类 KDoc
     * 已知限制）。端口可连 = 候选命中（不验证对端身份）；isApiActive 恒 true 的理由见
     * DiscoveryResult KDoc。命中经 hit.tryComplete 竞速，先到先得。
     */
    private suspend fun scanTcpSubnet(
        subnetPrefix: String,
        hit: CompletableDeferred<DiscoveryResult.Found>,
    ) {
        coroutineScope {
            val gate = Semaphore(TCP_SCAN_CONCURRENCY)
            for (last in 1..TCP_SCAN_LAST_HOST) {
                launch {
                    gate.withPermit {
                        if (hit.isCompleted) return@withPermit
                        val ip = "$subnetPrefix.$last"
                        val reachable = runCatching {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(ip, TCP_SCAN_PORT), TCP_CONNECT_TIMEOUT_MS)
                            }
                            true
                        }.getOrDefault(false)
                        if (reachable) {
                            // complete 幂等（已完成返回 false）：多路并发命中时先到先得
                            hit.complete(DiscoveryResult.Found(ip, TCP_SCAN_PORT, isApiActive = true))
                        }
                    }
                }
            }
        }
    }

    /**
     * 校验并解析广播包：官方信封（apiName + messageType + data.port/active）。
     * IP 一律取 UDP 源地址（包体无 IP 字段）；JSON 脏包/半包/同端口其他应用的数据包
     * 一律丢弃，等 2s 后的下一个广播。active 缺省按 true（老版本包体缺字段不误伤自动连接）。
     */
    private fun parseBroadcast(buf: ByteArray, packet: DatagramPacket): DiscoveryResult.Found? {
        val text = String(buf, 0, packet.length, StandardCharsets.UTF_8).trim()
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (obj.optString("apiName") != API_NAME) return null
        if (obj.optString("messageType") != MSG_TYPE) return null
        val data = obj.optJSONObject("data") ?: return null
        val port = data.optInt("port", -1)
        if (port !in 1..65535) return null
        val senderIp = packet.address?.hostAddress ?: return null
        return DiscoveryResult.Found(
            ip = senderIp,
            port = port,
            isApiActive = data.optBoolean("active", true),
        )
    }

    /**
     * MulticastLock（用户指定保留）：VTS 发的是受限广播（broadcast）不是组播（multicast），
     * Android 严格说收广播不强制此锁，但部分 OEM WiFi 驱动把广播按组播路径过滤，且主流
     * 第三方实现（VTubeBridge 等）均持锁收包——照拿无害。获取失败静默降级（锁不是收包
     * 硬前置，收不到还有 TCP 兜底）。引用计数关闭：一次 acquire 配一次 release（finally）。
     */
    private fun acquireMulticastLock(): MulticastLock? = runCatching {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock("arvtubelink-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    /**
     * 取本机私有网段 IPv4（用户裁定实现，不写死网卡名）：遍历全部网卡，过滤 up 且非
     * 回环且非虚拟子接口，取 192.168 / 10 / 172.16~31 网段的 IPv4。多候选时优先带硬件
     * 地址的网卡（VPN tun 网卡无 MAC，避免选中 VPN 网段去扫描）；仍并列取枚举序首个。
     */
    private fun pickPrivateIpv4(): Inet4Address? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        val withMac = ArrayList<Inet4Address>()
        val withoutMac = ArrayList<Inet4Address>()
        for (nif in interfaces) {
            val usable = runCatching { nif.isUp && !nif.isVirtual && !nif.isLoopback }.getOrDefault(false)
            if (!usable) continue
            for (addr in nif.inetAddresses) {
                val v4 = addr as? Inet4Address ?: continue
                if (!isPrivateIpv4(v4)) continue
                val hasMac = runCatching { nif.hardwareAddress != null }.getOrDefault(false)
                if (hasMac) withMac.add(v4) else withoutMac.add(v4)
            }
        }
        return (withMac + withoutMac).firstOrNull()
    }

    /** 192.168 / 10 / 172.16~31 网段判定（RFC1918 私有地址）。 */
    private fun isPrivateIpv4(ip: Inet4Address): Boolean {
        val bytes = ip.address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return (first == 192 && second == 168) ||
            first == 10 ||
            (first == 172 && second in 16..31)
    }

    /** /24 网段前缀推导：192.168.1.23 → "192.168.1"（TCP 兜底扫描目标，见已知限制）。 */
    private fun subnetPrefixOf(ip: Inet4Address): String {
        val bytes = ip.address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        val third = bytes[2].toInt() and 0xFF
        return "$first.$second.$third"
    }

    companion object {
        /** VTS 官方 UDP 广播端口（官方文档实锤 47779，每 2 秒一包；非 21412/21413）。 */
        private const val UDP_BROADCAST_PORT = 47779

        /** 广播包校验字段（官方信封固定值）。 */
        private const val API_NAME = "VTubeStudioPublicAPI"
        private const val MSG_TYPE = "VTubeStudioAPIStateBroadcast"

        /** UDP 监听窗口：VTS 每 2s 一包，5s 可收 2~3 包（用户指定 5s 自动超时）。 */
        private const val UDP_WINDOW_MS = 5_000L

        /** UDP 轮询间隔（soTimeout）：收包周期性让位于取消/窗口检查。 */
        private const val UDP_POLL_INTERVAL_MS = 500L

        /**
         * TCP 兜底延迟：UDP 3s 无果才启动（UDP 首包期望 ≤2s，典型路径走不到兜底）。
         * public：UI 层 Scanning 态镜像转移引用同值，防两处时序漂移。
         */
        const val TCP_FALLBACK_DELAY_MS = 3_000L

        /** UDP 端口被占（bind 失败）时 TCP 兜底提前量：UDP 路已死，无需再等。 */
        private const val TCP_EARLY_FALLBACK_DELAY_MS = 500L

        /** 总超时闸门（正常双路收工即提前终局，此闸只兜异常挂起）。 */
        private const val TOTAL_DEADLINE_MS = 8_000L

        /** TCP 兜底扫描参数：VTS 默认 WS 端口 / 单点连接超时 / 并发协程数 / 网段末位上界。 */
        private const val TCP_SCAN_PORT = 8001
        private const val TCP_CONNECT_TIMEOUT_MS = 200
        private const val TCP_SCAN_CONCURRENCY = 32
        private const val TCP_SCAN_LAST_HOST = 254

        /** UDP 收包缓冲上限（官方广播包体不足 300 字节，余量充足）。 */
        private const val MAX_PACKET_BYTES = 1024
    }
}
