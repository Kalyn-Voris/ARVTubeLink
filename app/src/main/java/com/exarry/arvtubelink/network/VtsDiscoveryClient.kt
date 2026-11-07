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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** 局域网 VTube Studio 自动发现：UDP 47779 广播监听 + TCP 8001 网段扫描竞速 */
class VtsDiscoveryClient(
    context: Context,
    /** 上次成功连接的地址（ip to port）提供者，返回 null 或空串表示无缓存 */
    private val cachedAddressProvider: () -> Pair<String, Int>? = { null },
) {

    private val appContext: Context = context.applicationContext

    /** isApiActive：UDP 路取包体 active，TCP 路恒 true */
    sealed interface DiscoveryResult {
        data class Found(val ip: String, val port: Int, val isApiActive: Boolean) : DiscoveryResult
        data class Failed(val reason: Reason) : DiscoveryResult

        enum class Reason {
            NO_NETWORK,
            TIMEOUT,
        }
    }

    suspend fun discover(): DiscoveryResult = coroutineScope {
        // 缓存地址 300ms 试连，命中即返回
        cachedAddress()?.let { (ip, port) ->
            val reachable = withContext(Dispatchers.IO) {
                probeTcp(ip, port, CACHED_PROBE_TIMEOUT_MS)
            }
            if (reachable) {
                return@coroutineScope DiscoveryResult.Found(ip, port, isApiActive = true)
            }
        }

        val localIp = pickPrivateIpv4()
            ?: return@coroutineScope DiscoveryResult.Failed(DiscoveryResult.Reason.NO_NETWORK)
        val subnetPrefix = subnetPrefixOf(localIp)
        val hit = CompletableDeferred<DiscoveryResult.Found>()
        val udpBound = CompletableDeferred<Boolean>()

        val udpJob = launch(Dispatchers.IO) { listenUdpBroadcast(hit, udpBound) }
        val tcpJob = launch(Dispatchers.IO) {
            // bind 结果在 T0 附近返回：端口被占则提前启动兜底
            val bound = runCatching { udpBound.await() }.getOrDefault(false)
            delay(if (bound) TCP_FALLBACK_DELAY_MS else TCP_EARLY_FALLBACK_DELAY_MS)
            if (!hit.isCompleted) scanTcpSubnet(subnetPrefix, hit)
        }

        val found = withTimeoutOrNull(TOTAL_DEADLINE_MS) {
            udpJob.join()
            if (!hit.isCompleted) tcpJob.join()
            if (hit.isCompleted) hit.await() else null
        }
        udpJob.cancel()
        tcpJob.cancel()
        if (found != null) found else DiscoveryResult.Failed(DiscoveryResult.Reason.TIMEOUT)
    }

    /** 绑定 47779 收广播，窗口内首个合法包即命中 */
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
                // 阻塞 receive 不响应协程取消与线程中断，靠 soTimeout 轮询回到 isActive 检查
                socket.soTimeout = UDP_POLL_INTERVAL_MS.toInt()
                val buf = ByteArray(MAX_PACKET_BYTES)
                val deadline = System.currentTimeMillis() + UDP_WINDOW_MS
                while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val found = parseBroadcast(buf, packet) ?: continue
                    hit.complete(found)
                    break
                }
            } catch (e: IOException) {
                // bind 失败或收包异常，均按 UDP 不可用降级
                udpBound.complete(false)
            } finally {
                runCatching { socket.close() }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    /** 并发探测 /24 网段 8001，可连即候选 */
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
                        if (probeTcp(ip, TCP_SCAN_PORT, TCP_CONNECT_TIMEOUT_MS)) {
                            hit.complete(DiscoveryResult.Found(ip, TCP_SCAN_PORT, isApiActive = true))
                        }
                    }
                }
            }
        }
    }

    /** 阻塞调用，须在 IO 线程执行；异常一律视为不可达 */
    private fun probeTcp(ip: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
        }
        true
    }.getOrDefault(false)

    /** 取缓存的上次连接地址。只接受合法 IPv4 与非空端口 */
    private fun cachedAddress(): Pair<String, Int>? {
        val (rawIp, port) = runCatching { cachedAddressProvider() }.getOrNull() ?: return null
        val ip = rawIp.trim()
        if (ip.isEmpty() || port !in 1..65535) return null
        val isIpv4 = ip.count { it == '.' } == 3 &&
            ip.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        return if (isIpv4) ip to port else null
    }

    /** 校验并解析广播包：标准信封（apiName + messageType + data.port/active） */
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

    /** 部分 OEM 驱动按组播路径过滤广播包，持锁收包；获取失败静默降级 */
    private fun acquireMulticastLock(): MulticastLock? = runCatching {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock("arvtubelink-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    /** 取私有网段 IPv4，优先带硬件地址的网卡 */
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

    /** 192.168 / 10 / 172.16~31 网段判定 */
    private fun isPrivateIpv4(ip: Inet4Address): Boolean {
        val bytes = ip.address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return (first == 192 && second == 168) ||
            first == 10 ||
            (first == 172 && second in 16..31)
    }

    private fun subnetPrefixOf(ip: Inet4Address): String {
        val bytes = ip.address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        val third = bytes[2].toInt() and 0xFF
        return "$first.$second.$third"
    }

    companion object {
        /** VTS 标准 UDP 广播端口，每 2 秒一包 */
        private const val UDP_BROADCAST_PORT = 47779

        private const val API_NAME = "VTubeStudioPublicAPI"
        private const val MSG_TYPE = "VTubeStudioAPIStateBroadcast"

        /** 监听窗口：VTS 每 2s 一包 */
        private const val UDP_WINDOW_MS = 5_000L

        private const val UDP_POLL_INTERVAL_MS = 500L

        /** UDP 无果后启动 TCP 兜底；UI 层镜像引用同值 */
        const val TCP_FALLBACK_DELAY_MS = 1_500L

        private const val TCP_EARLY_FALLBACK_DELAY_MS = 500L

        /** 总闸，正常双路收工即提前终局 */
        private const val TOTAL_DEADLINE_MS = 8_000L

        /** VTS 默认 WS 端口 */
        private const val TCP_SCAN_PORT = 8001
        private const val TCP_CONNECT_TIMEOUT_MS = 200

        /** /24 共 254 个目标，64 并发 */
        private const val TCP_SCAN_CONCURRENCY = 64
        private const val TCP_SCAN_LAST_HOST = 254

        /** 局域网 RTT 通常 <10ms，300ms 覆盖 Wi-Fi 省电唤醒抖动 */
        private const val CACHED_PROBE_TIMEOUT_MS = 300

        private const val MAX_PACKET_BYTES = 1024
    }
}
