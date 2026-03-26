package com.scieford.laserctrlmouse.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.scieford.laserctrlmouse.utils.LogManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 网络客户端实现
 * 负责发现受控端、建立连接、通信等功能
 */
class NetworkClient(private val useAlternativePort: Boolean = false) {
    companion object {
        private const val TAG = "NetworkClient"

        // 连接状态常量
        const val STATUS_NONE = 0
        const val STATUS_CONNECTED = 1
        const val STATUS_BUILDING = 2
        const val STATUS_DISCONNECTED = -1
    }

    // 受控端列表
    private val servers = ArrayList<ServerInfo>()
    private var currentServer: ServerInfo? = null
    
    // 网络组件
    private var socket: DatagramSocket
    private var executor: ExecutorService = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 状态变量
    private var connStatus = STATUS_NONE
    private var lastPingTime = 0L
    private var keepPing = false
    private var receiving = false
    
    // 等待确认的消息队列
    private val pendingAcks = ConcurrentHashMap<String, Long>()
    
    // 网络统计数据
    private val networkStats = NetworkStats()
    
    // 回调接口
    private var onConnectListener: OnConnectListener? = null
    private var onDisconnectListener: OnDisconnectListener? = null
    private var onStatusChangeListener: OnStatusChangeListener? = null
    private var onControlListener: OnControlListener? = null
    
    // 当前使用的端口
    private val clientPort: Int = if (useAlternativePort) 
                                    NetworkProtocol.CLIENT_PORT_ALTERNATIVE 
                                 else 
                                    NetworkProtocol.CLIENT_PORT

    /**
     * 受控端信息类
     */
    class ServerInfo(
        var name: String,
        var ip: String,
        var port: Int,
        var screenSize: IntArray,
        var version: String
    ) {
        var timestamp: Long = System.currentTimeMillis()
    }
    
    /**
     * 网络统计信息类
     */
    class NetworkStats {
        private val rttSamples = ArrayList<Double>()
        private val clockDiffSamples = ArrayList<Double>()
        private var minRtt = 0.0
        private var maxRtt = 0.0
        private var avgRtt = 0.0
        private var avgClockDiff = 0.0
        
        fun addRttSample(rtt: Double, clockDiff: Double) {
            rttSamples.add(rtt)
            clockDiffSamples.add(clockDiff)
            
            // 最多保留100个样本
            if (rttSamples.size > 100) {
                rttSamples.removeAt(0)
                clockDiffSamples.removeAt(0)
            }
            
            // 更新统计数据
            if (rttSamples.isNotEmpty()) {
                minRtt = Double.MAX_VALUE
                maxRtt = 0.0
                var rttSum = 0.0
                var clockDiffSum = 0.0
                
                for (sample in rttSamples) {
                    minRtt = minOf(minRtt, sample)
                    maxRtt = maxOf(maxRtt, sample)
                    rttSum += sample
                }
                
                for (sample in clockDiffSamples) {
                    clockDiffSum += sample
                }
                
                avgRtt = rttSum / rttSamples.size
                avgClockDiff = clockDiffSum / clockDiffSamples.size
            }
        }
        
        fun getStats(): Map<String, Double> {
            val stats = HashMap<String, Double>()
            stats["minRtt"] = minRtt
            stats["maxRtt"] = maxRtt
            stats["avgRtt"] = avgRtt
            stats["avgClockDiff"] = avgClockDiff
            stats["sampleCount"] = rttSamples.size.toDouble()
            return stats
        }
        
        fun clear() {
            rttSamples.clear()
            clockDiffSamples.clear()
            minRtt = 0.0
            maxRtt = 0.0
            avgRtt = 0.0
            avgClockDiff = 0.0
        }
    }

    // 回调接口定义
    fun interface OnConnectListener {
        fun onConnect()
    }

    fun interface OnDisconnectListener {
        fun onDisconnect()
    }

    fun interface OnStatusChangeListener {
        fun onStatusChange(status: Int)
    }

    fun interface OnControlListener {
        fun onControl(command: String, params: Array<String>)
    }

    /**
     * 构造函数
     */
    init {
        try {
            // 创建UDP套接字并绑定到客户端端口
            socket = DatagramSocket(clientPort)
            socket.soTimeout = 100 // 设置超时时间为100ms
            
            // 增大缓冲区
            socket.receiveBufferSize = 262144 // 256KB
            socket.sendBufferSize = 262144    // 256KB
            
            LogManager.i(TAG, "成功创建网络Socket，使用端口: $clientPort")
        } catch (e: SocketException) {
            LogManager.e(TAG, "创建socket失败: ${e.message}")
            throw e // 在Kotlin中，我们需要显式抛出异常，因为这是必要的初始化
        }
    }

    /**
     * 设置回调监听器
     */
    fun setOnConnectListener(listener: OnConnectListener) {
        this.onConnectListener = listener
    }

    fun setOnDisconnectListener(listener: OnDisconnectListener) {
        this.onDisconnectListener = listener
    }

    fun setOnStatusChangeListener(listener: OnStatusChangeListener) {
        this.onStatusChangeListener = listener
    }

    fun setOnControlListener(listener: OnControlListener) {
        this.onControlListener = listener
    }

    /**
     * 设置连接状态
     */
    private fun setConnectionStatus(value: Int) {
        val oldStatus = connStatus
        connStatus = value
        
        LogManager.d(TAG, "连接状态变化: $oldStatus -> $value")

        // 如果变成已连接，则需要做一些操作
        if (connStatus == STATUS_CONNECTED) {
            // 重要：立即更新lastPingTime，避免刚连接就被超时断开
            lastPingTime = System.currentTimeMillis()
            LogManager.d(TAG, "连接成功，立即更新lastPingTime: $lastPingTime")
            
            startPing()
            startConnCheck()
        }

        // 如果断开连接, 则停止一些线程
        if (connStatus == STATUS_DISCONNECTED || connStatus == STATUS_NONE) {
            stopPing()
            stopConnCheck()
            
                    // 清除当前受控端信息
        if (connStatus == STATUS_DISCONNECTED) {
            LogManager.d(TAG, "连接断开，清除当前受控端信息")
            currentServer = null
        }
        }

        // 通知状态变化
        onStatusChangeListener?.let {
            mainHandler.post { 
                LogManager.d(TAG, "通知状态变化回调: $value")
                it.onStatusChange(value) 
            }
        }
    }

    /**
     * 连接到指定受控端
     */
    fun connectToServer(server: ServerInfo) {
        currentServer = server
        setConnectionStatus(STATUS_BUILDING)
        sendCmd(NetworkProtocol.CLIENT_CMD_CONNECT, clientPort.toString())
    }

    /**
     * 发送命令
     */
    fun sendCmd(cmd: String, content: String?) {
        // 如果未连接受控端，直接返回
        if (currentServer == null) {
            LogManager.i(TAG, "未连接受控端，无法发送命令")
            return
        }

        // 生成当前时间戳作为消息ID
        val timestampId = (System.currentTimeMillis() / 1000.0).toString()

        var data = cmd
        if (content != null) {
            data += NetworkProtocol.MESSAGE_SPLITER + content
        }

        // 对于非广播和连接确认的命令，添加时间戳ID，使用特殊分隔符
        if (cmd != NetworkProtocol.BROADCAST_PREFIX_WAIT && 
            cmd != NetworkProtocol.CLIENT_CMD_CONNECT && 
            cmd != NetworkProtocol.CLIENT_CMD_PING) {
            data += NetworkProtocol.TS_SPLITER + timestampId
        }

        val finalData = data
        executor.execute {
            try {
                LogManager.d(TAG, "[${System.currentTimeMillis()}] send_cmd $finalData")
                
                // 获取受控端IP地址
                val serverAddr = InetAddress.getByName(currentServer!!.ip)
                
                // 创建数据包并发送
                val buffer = finalData.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, serverAddr, currentServer!!.port)
                
                // 发送数据包
                socket.send(packet)
                
                // 记录发送的时间和ID，用于计算往返时间
                if (cmd != NetworkProtocol.BROADCAST_PREFIX_WAIT && 
                    cmd != NetworkProtocol.CLIENT_CMD_CONNECT && 
                    cmd != NetworkProtocol.CLIENT_CMD_PING) {
                    pendingAcks[timestampId] = System.currentTimeMillis()
                }
            } catch (e: IOException) {
                LogManager.e(TAG, "发送命令失败: ${e.message}")
            }
        }
    }

    /**
     * 发送玩家命令
     */
    fun sendPlayerCmd(cmd: String, player: String, content: String) {
        // 如果未连接受控端，直接返回
        if (currentServer == null) {
            LogManager.i(TAG, "未连接受控端，无法发送玩家命令")
            return
        }

        val data = "$player${NetworkProtocol.MESSAGE_SPLITER}$content"
        sendCmd(cmd, data)
    }

    /**
     * 开始接收数据线程
     */
    fun startReceiving() {
        // 检查线程池状态，如果已关闭则重新初始化
        if (executor.isShutdown || executor.isTerminated) {
            LogManager.w(TAG, "线程池已关闭，重新初始化NetworkClient")
            try {
                reinitialize()
            } catch (e: Exception) {
                LogManager.e(TAG, "重新初始化NetworkClient失败: ${e.message}")
                // 如果重新初始化失败，抛出异常让调用方知道
                throw RuntimeException("无法重新初始化NetworkClient: ${e.message}", e)
            }
        }
        
        servers.clear()
        receiving = true
        
        try {
            executor.execute {
                while (receiving) {
                    try {
                        receiveData()
                    } catch (e: Exception) {
                        LogManager.e(TAG, "接收数据时发生错误: ${e.message}")
                        try {
                            Thread.sleep(1000) // 等待1秒后重试
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "提交接收任务到线程池失败: ${e.message}")
            receiving = false
            throw e
        }
    }

    /**
     * 重新初始化NetworkClient
     * 当线程池被关闭后需要重新创建资源
     */
    private fun reinitialize() {
        try {
            LogManager.i(TAG, "重新初始化NetworkClient...")
            
            // 关闭旧的socket（如果还没关闭）
            try {
                if (!socket.isClosed) {
                    socket.close()
                }
            } catch (e: Exception) {
                LogManager.w(TAG, "关闭旧socket时出错: ${e.message}")
            }
            
            // 重新创建线程池
            executor = Executors.newCachedThreadPool()
            
            // 重新创建socket
            socket = DatagramSocket(clientPort)
            socket.soTimeout = 100
            socket.receiveBufferSize = 262144
            socket.sendBufferSize = 262144
            
            // 重置状态
            connStatus = STATUS_NONE
            receiving = false
            keepPing = false
            lastPingTime = 0L
            pendingAcks.clear()
            networkStats.clear()
            
            LogManager.i(TAG, "NetworkClient重新初始化完成，使用端口: $clientPort")
        } catch (e: Exception) {
            LogManager.e(TAG, "重新初始化NetworkClient失败: ${e.message}")
            throw e
        }
    }

    /**
     * 接收数据处理
     */
    private fun receiveData() {
        try {
            // 定期清理servers列表中过期的受控端
            updateServers()
            
            // 准备接收缓冲区
            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            
            // 接收数据包
            socket.receive(packet)
            
            // 解析接收到的数据
            val data = String(packet.data, 0, packet.length)
            val info = data.split(NetworkProtocol.MESSAGE_SPLITER)
            val cmd = info[0]
            
            // 处理受控端的回复确认消息
            if (cmd == NetworkProtocol.SERVER_CMD_ACK_RESPONSE && info.size >= 3) {
                try {
                    val msgId = info[1]
                    val serverTime = info[2].toDouble()
                    
                    // 更新最后收到受控端响应的时间，表示连接正常
                    lastPingTime = System.currentTimeMillis()
                    
                    // 计算往返时间
                    if (pendingAcks.containsKey(msgId)) {
                        val sendTime = pendingAcks[msgId]!!
                        val currentTime = System.currentTimeMillis()
                        val rttMs = (currentTime - sendTime).toDouble() // 转换为毫秒
                        
                        // 计算时间差
                        val timeDiffMs = (serverTime - sendTime / 1000.0) * 1000 // 受控端时间与本地发送时间之差
                        val clockDiffMs = timeDiffMs - rttMs / 2 // 估算时钟差异
                        
                        // 更新网络统计信息
                        networkStats.addRttSample(rttMs, clockDiffMs)
                        
                        LogManager.d(TAG, "消息ID: $msgId")
                        LogManager.d(TAG, String.format("往返时间: %.2fms", rttMs))
                        LogManager.d(TAG, String.format("系统时钟差: %.2fms", clockDiffMs))
                        
                        if (Math.abs(clockDiffMs) > 1000) { // 如果时钟差异超过1秒
                            LogManager.w(TAG, String.format("警告: 系统时钟差异较大: %.2f秒", clockDiffMs / 1000))
                        }
                        
                        // 清除已处理的消息ID
                        pendingAcks.remove(msgId)
                    } else {
                        LogManager.i(TAG, "收到未知消息ID的确认: $msgId")
                    }
                } catch (e: Exception) {
                    LogManager.e(TAG, "处理时间同步响应时出错: ${e.message}")
                    e.printStackTrace()
                }
            }
            
            // 处理广播消息（受控端发现）
            if (cmd == NetworkProtocol.BROADCAST_PREFIX_WAIT) {
                val hostName = info[1]
                val hostPort = info[2].toInt()
                val screenSizeParts = info[3].split(",")
                val screenSize = intArrayOf(
                    screenSizeParts[0].toInt(), 
                    screenSizeParts[1].toInt()
                )
                val serverVersion = info[4]
                
                // 动态更新受控端屏幕分辨率 - 只有当前连接的受控端才更新
                if (currentServer != null && currentServer!!.name == hostName && connStatus == STATUS_CONNECTED) {
                    try {
                        com.scieford.laserctrlmouse.MainActivity.setRealScreenSize(screenSize[0], screenSize[1])
                        LogManager.i(TAG, "已根据当前连接受控端[$hostName]的广播更新realScreenSize: ${screenSize[0]}x${screenSize[1]}")
                    } catch (e: Exception) {
                        LogManager.e(TAG, "更新realScreenSize失败: ${e.message}")
                    }
                } else {
                    if (currentServer != null && currentServer!!.name != hostName) {
                        LogManager.d(TAG, "收到其他受控端[$hostName]的广播，当前连接受控端[${currentServer!!.name}]，忽略分辨率更新")
                    } else if (connStatus != STATUS_CONNECTED) {
                        LogManager.d(TAG, "收到受控端[$hostName]的广播，但当前未连接状态，忽略分辨率更新")
                    }
                }
                
                // 检查是否已经在列表中
                var exists = false
                for (server in servers) {
                    if (server.name == hostName) {
                        server.timestamp = System.currentTimeMillis()
                        // 更新受控端列表中的屏幕尺寸信息
                        server.screenSize = screenSize
                        exists = true
                        break
                    }
                }
                
                // 不存在则添加到列表
                if (!exists) {
                    val serverInfo = ServerInfo(
                        hostName,
                        packet.address.hostAddress,
                        hostPort,
                        screenSize,
                        serverVersion
                    )
                    servers.add(serverInfo)
                }
            }
            
            // 处理受控端确认连接
            if (cmd == NetworkProtocol.SERVER_CMD_CONNECT_CONFIRM) {
                setConnectionStatus(STATUS_CONNECTED)
                onConnectListener?.let {
                    mainHandler.post { it.onConnect() }
                }
            }
            
            // 处理受控端断开连接
            if (cmd == NetworkProtocol.SERVER_CMD_DISCONNECT) {
                setConnectionStatus(STATUS_NONE)
                onDisconnectListener?.let {
                    mainHandler.post { it.onDisconnect() }
                }
            }
            
            // 处理控制命令
            if (cmd == NetworkProtocol.CLIENT_CMD_CONTROL) {
                onControlListener?.let {
                    // 提取控制命令参数
                    val params = info.subList(1, info.size).toTypedArray()
                    
                    // 发送到主线程处理
                    mainHandler.post { it.onControl(params[0], params) }
                }
            }
        } catch (e: SocketTimeoutException) {
            // 超时，忽略
        } catch (e: IOException) {
            LogManager.e(TAG, "接收数据IO异常: ${e.message}")
        }
    }

    /**
     * 停止接收数据
     */
    fun stopReceiving() {
        LogManager.i(TAG, "停止接收线程...")
        receiving = false
    }

    /**
     * 清理受控端列表中过期的受控端（3秒未更新）
     */
    private fun updateServers() {
        val iterator = servers.iterator()
        val currentTime = System.currentTimeMillis()
        
        while (iterator.hasNext()) {
            val server = iterator.next()
            if (currentTime - server.timestamp > 3000) {
                iterator.remove()
            }
        }
    }

    /**
     * 开始连接检测线程
     */
    private fun startConnCheck() {
        // 确保lastPingTime有初始值
        if (lastPingTime == 0L) {
            lastPingTime = System.currentTimeMillis()
            LogManager.d(TAG, "startConnCheck: 初始化lastPingTime: $lastPingTime")
        }
        
        executor.execute {
            LogManager.d(TAG, "连接检测线程已启动")
            while (connStatus == STATUS_CONNECTED) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastPing = currentTime - lastPingTime
                val timeoutMs = NetworkProtocol.PING_TIMEOUT * 1000L
                
                if (timeSinceLastPing > timeoutMs) {
                    LogManager.w(TAG, "连接超时 - 最后ping时间: $lastPingTime, 当前时间: $currentTime, 间隔: ${timeSinceLastPing}ms, 超时阈值: ${timeoutMs}ms")
                    setConnectionStatus(STATUS_DISCONNECTED)
                    break
                } else {
                    // 只有在接近超时时才打印日志，避免日志过多
                    if (timeSinceLastPing > timeoutMs * 0.8) {
                        LogManager.d(TAG, "连接检测 - 距离上次ping: ${timeSinceLastPing}ms / ${timeoutMs}ms")
                    }
                }
                
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    LogManager.d(TAG, "连接检测线程被中断")
                    Thread.currentThread().interrupt()
                    break
                }
            }
            LogManager.d(TAG, "连接检测线程已停止，当前状态: $connStatus")
        }
    }

    /**
     * 停止连接检测
     */
    private fun stopConnCheck() {
        // 连接检测会在状态改变时自动结束
    }

    /**
     * 处理控制命令
     */
    private fun handleControl(params: Array<String>) {
        LogManager.i(TAG, "收到控制命令: ${params.joinToString(", ")}")
        onControlListener?.let {
            val cmd = params[0]
            mainHandler.post { it.onControl(cmd, params) }
        }
    }

    /**
     * 开始定时发送PING
     */
    private fun startPing() {
        keepPing = true
        executor.execute {
            while (keepPing) {
                // 改为发送ACK请求而不是PING，这样可以通过SERVER_CMD_ACK_RESPONSE来确认连接状态
                sendCmd(NetworkProtocol.CLIENT_CMD_ACK_REQUEST, null)
                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    /**
     * 停止发送PING
     */
    private fun stopPing() {
        keepPing = false
    }

    /**
     * 请求时间同步
     */
    fun requestTimeSync() {
        if (connStatus != STATUS_CONNECTED) {
            LogManager.i(TAG, "未连接到受控端，无法进行时间同步")
            return
        }
        
        LogManager.i(TAG, "正在请求时间同步...")
        sendCmd(NetworkProtocol.CLIENT_CMD_ACK_REQUEST, null)
    }

    /**
     * 获取网络性能统计信息
     */
    fun getNetworkStats(): Map<String, Double> {
        return networkStats.getStats()
    }

    /**
     * 清除网络统计数据
     */
    fun clearNetworkStats() {
        networkStats.clear()
        LogManager.i(TAG, "网络统计数据已重置")
    }

    /**
     * 通知受控端屏幕检测失败
     */
    fun noticeDetectScreenFailed() {
        sendCmd(NetworkProtocol.CLIENT_CMD_ERROR, "screen_detect_failed")
    }

    /**
     * 请求显示模板图像
     */
    fun noticeShowTemplate(url: String) {
        sendCmd(NetworkProtocol.CLIENT_CMD_SHOW_TEMPLATE, url)
    }

    /**
     * 通知模板已检测到
     */
    fun noticeTemplateDetected() {
        sendCmd(NetworkProtocol.CLIENT_CMD_SHOW_TEMPLATE, null)
    }

    /**
     * 通知光标移动
     */
    fun noticePointMove(player: String, points: String) {
        sendPlayerCmd(NetworkProtocol.CLIENT_CMD_MOVE, player, points)
    }

    /**
     * 通知按钮按下
     */
    fun noticeBtnDown(player: String, btns: String) {
        sendPlayerCmd(NetworkProtocol.CLIENT_CMD_BTN_DOWN, player, btns)
    }

    /**
     * 通知按钮松开
     */
    fun noticeBtnUp(player: String, btns: String) {
        sendPlayerCmd(NetworkProtocol.CLIENT_CMD_BTN_UP, player, btns)
    }

    /**
     * 通知按键按下
     */
    fun noticeKeyDown(player: String, keys: String) {
        sendPlayerCmd(NetworkProtocol.CLIENT_CMD_KEY_DOWN, player, keys)
    }

    /**
     * 通知按键松开
     */
    fun noticeKeyUp(player: String, keys: String) {
        sendPlayerCmd(NetworkProtocol.CLIENT_CMD_KEY_UP, player, keys)
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        LogManager.i(TAG, "主动断开连接")
        
        // 停止ping和连接检测
        stopPing()
        stopConnCheck()
        
        // 清理状态
        currentServer = null
        lastPingTime = 0L
        
        // 设置状态为已断开
        setConnectionStatus(STATUS_DISCONNECTED)
        
        // 通知断开连接监听器
        onDisconnectListener?.let {
            mainHandler.post { it.onDisconnect() }
        }
    }

    /**
     * 销毁客户端，释放资源
     */
    fun destroy() {
        LogManager.i(TAG, "正在销毁网络客户端...")
        try {
            // 停止所有线程和定时任务
            stopPing()
            stopReceiving()
            
            // 断开连接
            disconnect()
            
            // 关闭套接字
            try {
                socket.close()
                LogManager.i(TAG, "套接字已关闭")
            } catch (e: Exception) {
                LogManager.e(TAG, "关闭套接字时出错: ${e.message}")
            }
            
            // 清除受控端列表和消息记录
            servers.clear()
            currentServer = null
            pendingAcks.clear()
            
            // 关闭线程池
            executor.shutdown()
            
            LogManager.i(TAG, "网络客户端销毁完成")
        } catch (e: Exception) {
            LogManager.e(TAG, "销毁网络客户端时出错: ${e.message}")
        }
    }
    
    /**
     * 获取当前发现的受控端列表
     */
    fun getServers(): List<ServerInfo> {
        return ArrayList(servers)
    }
    
    /**
     * 获取当前连接的受控端
     */
    fun getCurrentServer(): ServerInfo? {
        return currentServer
    }
    
    /**
     * 获取当前连接状态
     */
    fun getConnectionStatus(): Int {
        return connStatus
    }
} 