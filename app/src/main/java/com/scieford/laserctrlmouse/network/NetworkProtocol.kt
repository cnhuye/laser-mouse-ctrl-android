package com.scieford.laserctrlmouse.network

/**
 * 网络通信协议定义类
 * 参照Python版network.py中的协议定义
 */
object NetworkProtocol {
    // 消息分隔符
    const val MESSAGE_SPLITER = "||"
    const val TS_SPLITER = "##"  // 时间戳使用的特殊分隔符
    
    // 广播前缀
    const val BROADCAST_PREFIX_WAIT = "_&*001033201_001"
    
    // 客户端命令
    const val CLIENT_CMD_CONNECT = "_&*001033201_002" // 客户端首次连接
    const val CLIENT_CMD_SHOW_TEMPLATE = "_&*001033201_003" // 显示模板图片
    const val CLIENT_CMD_MOVE = "_&*001033201_004" // 光标指向
    const val CLIENT_CMD_BTN_DOWN = "_&*001033201_005" // 鼠标点击
    const val CLIENT_CMD_BTN_UP = "_&*001033201_006" // 鼠标释放
    const val CLIENT_CMD_KEY_DOWN = "_&*001033201_007" // 键盘按键
    const val CLIENT_CMD_KEY_UP = "_&*001033201_008" // 键盘释放
    const val CLIENT_CMD_CONTROL = "_&*001033201_020" // 控制命令
    const val CLIENT_CMD_PING = "_&*001033201_088" // PING
    
    const val CLIENT_CMD_ERROR = "_&*001033201_009" // 错误通知命令
    const val CLIENT_CMD_DISCONNECT = "_&*001033201_010" // DISCONNECT
    
    // 服务端命令
    const val SERVER_CMD_CONNECT_CONFIRM = "_&*001033201_102" // 客户端的连接确认
    const val SERVER_CMD_CONTROL = "_&*001033201_106" // 控制命令
    const val SERVER_CMD_PING = "_&*001033201_108" // PING
    const val SERVER_CMD_DISCONNECT = "_&*001033201_109" // 服务端通知停止连接
    
    // 消息确认相关命令
    const val CLIENT_CMD_ACK_REQUEST = "_&*001033201_050" // 客户端请求消息确认
    const val SERVER_CMD_ACK_RESPONSE = "_&*001033201_150" // 服务端确认消息响应

    // 端口定义
    const val CLIENT_PORT = 5010
    const val CLIENT_PORT_ALTERNATIVE = 5020  // 备用客户端端口
    const val SERVER_PORT = 5011
    
    // 版本信息
    const val VERSION = "1.0.0"
    
    // PING超时
    const val PING_TIMEOUT = 5 // 5秒没ping就认为连接中断了
} 