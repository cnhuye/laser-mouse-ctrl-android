package com.scieford.laserctrlmouse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.scieford.laserctrlmouse.network.NetworkClient

/**
 * 受控端列表适配器
 */
class ServerListAdapter(
    private val serverList: List<NetworkClient.ServerInfo>,
    private val listener: OnServerClickListener
) : RecyclerView.Adapter<ServerListAdapter.ViewHolder>() {
    
    /**
     * 受控端点击监听器接口
     */
    interface OnServerClickListener {
        fun onServerClick(server: NetworkClient.ServerInfo)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_server, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = serverList[position]
        val context = holder.itemView.context
        
        // 设置受控端信息
        holder.serverNameText.text = server.name
        holder.serverIpText.text = context.getString(R.string.client_ip, "${server.ip}:${server.port}")
        holder.screenSizeText.text = context.getString(R.string.screen_resolution, "${server.screenSize[0]}x${server.screenSize[1]}")
        
        // 设置连接按钮点击事件
        holder.connectButton.setOnClickListener {
            listener.onServerClick(server)
        }
        
        // 检查是否为当前连接的受控端
        if (ConnectActivity.connectedServer != null && 
            ConnectActivity.connectedServer!!.ip == server.ip &&
            ConnectActivity.connectedServer!!.port == server.port) {
            holder.connectButton.text = context.getString(R.string.connected_status)
            holder.connectButton.isEnabled = false
        } else {
            holder.connectButton.text = context.getString(R.string.connect)
            holder.connectButton.isEnabled = true
        }
    }
    
    override fun getItemCount(): Int = serverList.size
    
    /**
     * ViewHolder类
     */
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val serverNameText: TextView = itemView.findViewById(R.id.serverNameText)
        val serverIpText: TextView = itemView.findViewById(R.id.serverIpText)
        val screenSizeText: TextView = itemView.findViewById(R.id.screenSizeText)
        val connectButton: Button = itemView.findViewById(R.id.connectButton)
    }
} 