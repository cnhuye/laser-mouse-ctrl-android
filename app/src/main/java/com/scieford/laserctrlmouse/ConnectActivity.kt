package com.scieford.laserctrlmouse

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scieford.laserctrlmouse.network.NetworkClient
import com.scieford.laserctrlmouse.utils.LogManager

class ConnectActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ConnectActivity"
        
        // 静态变量，用于在应用程序中持有当前连接的服务器信息
        var connectedServer: NetworkClient.ServerInfo? = null
        
        // 标记是否已连接
        var isServerConnected = false
        
        /**
         * 获取网络客户端实例
         */
        fun getNetworkClient(): NetworkClient {
            // 这个方法将在MainActivity中调用，以获取网络客户端实例
            return SingletonHolder.INSTANCE
        }
        
        /**
         * 单例模式持有网络客户端实例
         */
        private object SingletonHolder {
            // 修改为延迟初始化，避免在类加载时就创建实例
            val INSTANCE: NetworkClient by lazy {
                // 如果已有实例，优先使用已有实例
                if (isServerConnected && connectedServer != null) {
                    try {
                        // 尝试获取当前活动的NetworkClient
                        val field = ConnectActivity::class.java.getDeclaredField("networkClient")
                        field.isAccessible = true
                        val instance = field.get(null) as? NetworkClient
                        if (instance != null) return@lazy instance
                    } catch (e: Exception) {
                        LogManager.e(TAG, "获取已有NetworkClient实例失败: ${e.message}")
                    }
                }
                
                try {
                    NetworkClient()
                } catch (e: Exception) {
                    LogManager.e(TAG, "创建NetworkClient实例失败: ${e.message}")
                    // 如果创建失败，尝试使用备用方案
                    NetworkClient(useAlternativePort = true)
                }
            }
        }
    }
    
    // UI组件
    private lateinit var serverListView: RecyclerView
    private lateinit var refreshButton: Button
    private lateinit var backButton: Button
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var pcClientLinkText: TextView
    
    // 网络客户端
    private lateinit var networkClient: NetworkClient
    
    // 适配器
    private lateinit var serverListAdapter: ServerListAdapter
    
    // 服务器列表
    private val serverList = ArrayList<NetworkClient.ServerInfo>()
    
    // 当前连接状态
    private var isConnecting = false
    
    // 处理UI更新的Handler
    private val handler = Handler(Looper.getMainLooper())
    
    // 用于定期刷新服务器列表的Runnable
    private lateinit var refreshRunnable: Runnable
    
    // 记录是否已发送模板请求
    private var templateRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_connect)
        
        // 初始化UI组件
        serverListView = findViewById(R.id.serverListView)
        refreshButton = findViewById(R.id.refreshButton)
        backButton = findViewById(R.id.backButton)
        statusText = findViewById(R.id.statusText)
        emptyText = findViewById(R.id.emptyText)
        progressBar = findViewById(R.id.progressBar)
        pcClientLinkText = findViewById(R.id.pcClientLinkText)
        
        // 设置RecyclerView
        serverListView.layoutManager = LinearLayoutManager(this)
        serverListAdapter = ServerListAdapter(serverList, object : ServerListAdapter.OnServerClickListener {
            override fun onServerClick(server: NetworkClient.ServerInfo) {
                connectToServer(server)
            }
        })
        serverListView.adapter = serverListAdapter
        
        // 初始化网络客户端
        networkClient = getNetworkClient()
        
        // 设置按钮点击事件
        refreshButton.setOnClickListener { refreshServerList() }
        backButton.setOnClickListener { onBackPressed() }
        
        // 设置获取电脑端链接点击事件
        pcClientLinkText.setOnClickListener { openPcClientDownload() }
        
        // 设置网络客户端回调
        setupNetworkCallbacks()
        
        // 开始扫描网络
        startNetworkScan()
        
        // 设置定时刷新
        setupRefreshTask()
    }
    
    override fun onResume() {
        super.onResume()
        // 如果已经连接，则显示连接状态
        updateConnectionStatus()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 停止定时刷新
        handler.removeCallbacks(refreshRunnable)
        
        // 不在这里销毁NetworkClient，因为它是单例
        // NetworkClient应该在应用程序退出时由MainActivity或Application销毁
        // 这里只停止接收数据，避免内存泄漏
        if (!isServerConnected) {
            LogManager.d(TAG, "ConnectActivity销毁，停止网络接收但不销毁NetworkClient")
            networkClient.stopReceiving()
        }
    }
    
    /**
     * 设置网络客户端回调
     */
    private fun setupNetworkCallbacks() {
        // 连接成功回调
        networkClient.setOnConnectListener {
            LogManager.i(TAG, "受控端连接成功回调")
            isConnecting = false
            
            // 【修复】确保在连接成功回调中立即更新全局状态
            val currentServer = networkClient.getCurrentServer()
            if (currentServer != null) {
                isServerConnected = true
                connectedServer = currentServer
                LogManager.i(TAG, "连接成功回调中设置全局状态 - isServerConnected: true, server: ${currentServer.name}")
            } else {
                LogManager.w(TAG, "连接成功但getCurrentServer()返回null")
            }

            // 更新UI
            runOnUiThread {
                Toast.makeText(this@ConnectActivity, getString(R.string.connection_success), Toast.LENGTH_SHORT).show()
                statusText.text = getString(R.string.connected_to_server)
                
                // 请求显示模板图像
                if (!templateRequested) {
                    networkClient.noticeShowTemplate("https://www.whilefoto.com/template.jpeg")
                    templateRequested = true
                    LogManager.i(TAG, "已发送显示模板图像请求")
                }
                
                // 连接成功后返回主页面
                Handler().postDelayed({
                    LogManager.i(TAG, "连接成功，准备返回主界面")
                    val intent = Intent()
                    setResult(RESULT_OK, intent)
                    finish()
                }, 1000) // 延迟1秒返回，让用户看到连接成功的提示
            }
        }
        
        // 断开连接回调
        networkClient.setOnDisconnectListener {
            LogManager.i(TAG, "受控端断开连接回调")
            isConnecting = false
            
            // 【修复】确保在断开连接回调中立即清理全局状态
            isServerConnected = false
            connectedServer = null
            templateRequested = false // 重置模板请求标记
            LogManager.i(TAG, "断开连接回调中清理全局状态 - isServerConnected: false")
            
            // 更新UI
            runOnUiThread {
                Toast.makeText(this@ConnectActivity, getString(R.string.connection_lost), Toast.LENGTH_SHORT).show()
                statusText.text = getString(R.string.connection_lost)
                updateConnectionStatus()
            }
        }
        
        // 状态变化回调
        networkClient.setOnStatusChangeListener { status ->
            LogManager.i(TAG, "连接状态变化: $status")
            
            // 更新UI
            runOnUiThread {
                when (status) {
                    NetworkClient.STATUS_BUILDING -> {
                        statusText.text = getString(R.string.connecting_to, "")
                        isServerConnected = false // 确保连接中状态下isServerConnected为false
                    }
                    NetworkClient.STATUS_CONNECTED -> {
                        statusText.text = getString(R.string.connected)
                        connectedServer = networkClient.getCurrentServer()
                        isServerConnected = true // 只有在真正连接成功时才设置为true
                        LogManager.i(TAG, "设置isServerConnected = true")
                    }
                    NetworkClient.STATUS_DISCONNECTED -> {
                        statusText.text = getString(R.string.connection_lost)
                        isConnecting = false
                        isServerConnected = false
                        connectedServer = null
                        LogManager.i(TAG, "设置isServerConnected = false (断开)")
                    }
                    else -> {
                        statusText.text = getString(R.string.searching_servers)
                        isConnecting = false
                        isServerConnected = false
                        connectedServer = null
                        LogManager.i(TAG, "设置isServerConnected = false (其他状态)")
                    }
                }
                
                updateConnectionStatus()
            }
        }
    }
    
    /**
     * 开始扫描网络
     */
    private fun startNetworkScan() {
        // 开始接收网络数据
        networkClient.startReceiving()
        
        // 更新UI
        statusText.text = getString(R.string.searching_servers)
        progressBar.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        pcClientLinkText.visibility = View.GONE
    }
    
    /**
     * 刷新受控端列表
     */
    private fun refreshServerList() {
        // 清空受控端列表
        serverList.clear()
        serverListAdapter.notifyDataSetChanged()
        
        // 显示进度条，隐藏空提示和链接
        progressBar.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        pcClientLinkText.visibility = View.GONE
        
        // 重新开始扫描
        startNetworkScan()
    }
    
    /**
     * 设置定时刷新任务
     */
    private fun setupRefreshTask() {
        refreshRunnable = Runnable {
            // 获取当前受控端列表
            val servers = networkClient.getServers()
            
            // 如果列表为空，显示提示信息
            if (servers.isEmpty()) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (serverList.isEmpty()) {
                        emptyText.visibility = View.VISIBLE
                        pcClientLinkText.visibility = View.VISIBLE
                    }
                }
            } else {
                // 更新UI
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    emptyText.visibility = View.GONE
                    pcClientLinkText.visibility = View.GONE
                    
                    // 更新列表
                    serverList.clear()
                    serverList.addAll(servers)
                    serverListAdapter.notifyDataSetChanged()
                }
            }
            
            // 每2秒刷新一次
            handler.postDelayed(refreshRunnable, 2000)
        }
        
        // 开始定时刷新
        handler.postDelayed(refreshRunnable, 1000)
    }
    
    /**
     * 连接到受控端
     */
    private fun connectToServer(server: NetworkClient.ServerInfo) {
        if (isConnecting) {
            Toast.makeText(this, getString(R.string.connecting_please_wait), Toast.LENGTH_SHORT).show()
            return
        }
        
        // 标记为正在连接
        isConnecting = true
        
        // 更新UI
        statusText.text = getString(R.string.connecting_to, server.name)
        
        // 连接受控端
        networkClient.connectToServer(server)
    }
    
    /**
     * 更新连接状态UI
     */
    private fun updateConnectionStatus() {
        val status = networkClient.getConnectionStatus()
        
        when (status) {
            NetworkClient.STATUS_CONNECTED -> {
                statusText.text = getString(R.string.connected_to_server)
                connectedServer = networkClient.getCurrentServer()
                isServerConnected = true
            }
            NetworkClient.STATUS_BUILDING -> {
                statusText.text = getString(R.string.connecting_to, "")
            }
            NetworkClient.STATUS_DISCONNECTED -> {
                statusText.text = getString(R.string.connection_lost)
                isServerConnected = false
                connectedServer = null
            }
            else -> {
                if (connectedServer != null) {
                    statusText.text = getString(R.string.connected_to_server)
                    isServerConnected = true
                } else {
                    statusText.text = getString(R.string.searching_servers)
                    isServerConnected = false
                }
            }
        }
    }
    
    /**
     * 打开电脑端下载页面
     */
    private fun openPcClientDownload() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://laser.scieford.com/download"))
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                LogManager.d(TAG, "已启动浏览器打开电脑端下载页面")
            } else {
                Toast.makeText(this, getString(R.string.no_browser_found), Toast.LENGTH_SHORT).show()
                LogManager.w(TAG, "没有找到可用的浏览器应用")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "打开电脑端下载页面失败: ${e.message}")
            Toast.makeText(this, getString(R.string.open_website_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }
} 