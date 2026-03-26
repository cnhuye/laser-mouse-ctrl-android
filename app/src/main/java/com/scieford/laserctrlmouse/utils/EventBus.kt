package com.scieford.laserctrlmouse.utils

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 简单事件总线 - 用于在组件间传递事件
 */
object EventBus {
    private const val TAG = "EventBus"
    
    // 主线程Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 事件监听器
    private val listeners = mutableMapOf<String, MutableList<EventListener>>()
    
    /**
     * 事件监听器接口
     */
    interface EventListener {
        /**
         * 当事件发生时调用
         * @param event 事件名称
         * @param data 事件数据
         */
        fun onEvent(event: String, data: Map<String, Any>)
    }
    
    /**
     * 注册事件监听器
     * @param event 事件名称
     * @param listener 监听器
     */
    fun register(event: String, listener: EventListener) {
        synchronized(listeners) {
            val eventListeners = listeners.getOrPut(event) { mutableListOf() }
            if (!eventListeners.contains(listener)) {
                eventListeners.add(listener)
                LogManager.d(TAG, "注册事件监听器: $event, 现有监听器: ${eventListeners.size}")
            }
        }
    }
    
    /**
     * 注销事件监听器
     * @param event 事件名称
     * @param listener 监听器
     */
    fun unregister(event: String, listener: EventListener) {
        synchronized(listeners) {
            listeners[event]?.remove(listener)
            LogManager.d(TAG, "注销事件监听器: $event")
        }
    }
    
    /**
     * 注销所有特定事件的监听器
     * @param event 事件名称
     */
    fun unregisterAll(event: String) {
        synchronized(listeners) {
            listeners.remove(event)
            LogManager.d(TAG, "注销所有事件监听器: $event")
        }
    }
    
    /**
     * 发布事件 - 使用可变参数传递键值对
     * @param event 事件名称
     * @param data 事件数据
     */
    fun publish(event: String, vararg data: Pair<String, Any>) {
        val dataMap = mutableMapOf<String, Any>()
        data.forEach { dataMap[it.first] = it.second }
        
        // 在主线程上发布事件
        mainHandler.post {
            synchronized(listeners) {
                listeners[event]?.forEach { listener ->
                    try {
                        listener.onEvent(event, dataMap)
                    } catch (e: Exception) {
                        LogManager.e(TAG, "事件处理异常: $event, ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
        
        LogManager.d(TAG, "发布事件: $event, 数据: $dataMap")
    }
} 