/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.vkturnproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.VkTurnProxyActivityBinding
import com.wireguard.android.util.resolveAttribute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Activity for configuring and controlling VK Turn Proxy
 */
class VkTurnProxyActivity : AppCompatActivity() {

    private lateinit var binding: VkTurnProxyActivityBinding
    private var proxyService: VkTurnProxyService? = null
    private var bound = false
    private lateinit var logAdapter: LogAdapter
    private var currentLogs: List<VkTurnProxyService.LogEntry> = emptyList()
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VkTurnProxyService.LocalBinder
            proxyService = binder.getService()
            bound = true
            
            // Observe service state
            proxyService?.isRunning?.onEach { isRunning ->
                updateUiState(isRunning)
            }?.launchIn(lifecycleScope)
            
            proxyService?.logLines?.onEach { logs ->
                currentLogs = logs
                logAdapter.submitList(logs)
                if (logs.isNotEmpty()) {
                    binding.logRecyclerView.scrollToPosition(logs.size - 1)
                }
            }?.launchIn(lifecycleScope)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = VkTurnProxyActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.vk_turn_proxy_title)
        }
        
        setupViews()
        loadSettings()
    }
    
    private fun setupViews() {
        // Setup log recycler view
        logAdapter = LogAdapter()
        binding.logRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@VkTurnProxyActivity)
            adapter = logAdapter
            addItemDecoration(DividerItemDecoration(context, LinearLayoutManager.VERTICAL))
        }
        
        // Toggle button
        binding.toggleButton.setOnClickListener {
            if (proxyService?.isRunning?.value == true) {
                stopProxy()
            } else {
                startProxy()
            }
        }
        
        // Save button
        binding.saveButton.setOnClickListener {
            saveSettings()
        }
        
        // Clear logs button
        binding.clearLogsButton.setOnClickListener {
            proxyService?.clearLogs()
        }
        
        // Copy logs button
        binding.copyLogsButton.setOnClickListener {
            copyLogsToClipboard()
        }
    }
    
    private fun copyLogsToClipboard() {
        if (currentLogs.isEmpty()) {
            return
        }
        
        val logsText = currentLogs.joinToString("\n") { entry ->
            "${entry.timestamp} [${entry.level}] ${entry.message}"
        }
        
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("VK Turn Proxy Logs", logsText)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(this, R.string.vk_turn_proxy_logs_copied, Toast.LENGTH_SHORT).show()
    }
    
    private fun loadSettings() {
        lifecycleScope.launch {
            val prefs = Application.getPreferencesDataStore().data.first()
            
            binding.apply {
                switchEnabled.isChecked = prefs[VkTurnProxyConfig.KEY_ENABLED] ?: false
                editVkLink.setText(prefs[VkTurnProxyConfig.KEY_VK_CALL_LINK] ?: "")
                editPeerAddress.setText(prefs[VkTurnProxyConfig.KEY_PEER_SERVER_ADDRESS] ?: "")
                editPeerPort.setText((prefs[VkTurnProxyConfig.KEY_PEER_SERVER_PORT] ?: 56000).toString())
                editListenPort.setText((prefs[VkTurnProxyConfig.KEY_LISTEN_PORT] ?: 9000).toString())
                editMtu.setText((prefs[VkTurnProxyConfig.KEY_MTU] ?: 1420).toString())
                editConnections.setText((prefs[VkTurnProxyConfig.KEY_CONNECTIONS] ?: 16).toString())
                switchUdp.isChecked = prefs[VkTurnProxyConfig.KEY_USE_UDP] ?: false
                editTurnServer.setText(prefs[VkTurnProxyConfig.KEY_TURN_SERVER] ?: "")
                editTurnPort.setText((prefs[VkTurnProxyConfig.KEY_TURN_PORT] ?: 19302).toString())
                editRealm.setText(prefs[VkTurnProxyConfig.KEY_REALM] ?: "call6-7.vkuser.net")
            }
        }
    }
    
    private fun saveSettings() {
        lifecycleScope.launch {
            try {
                Application.getPreferencesDataStore().edit { prefs ->
                    prefs[VkTurnProxyConfig.KEY_ENABLED] = binding.switchEnabled.isChecked
                    prefs[VkTurnProxyConfig.KEY_VK_CALL_LINK] = binding.editVkLink.text.toString()
                    prefs[VkTurnProxyConfig.KEY_PEER_SERVER_ADDRESS] = binding.editPeerAddress.text.toString()
                    prefs[VkTurnProxyConfig.KEY_PEER_SERVER_PORT] = binding.editPeerPort.text.toString().toIntOrNull() ?: 56000
                    prefs[VkTurnProxyConfig.KEY_LISTEN_PORT] = binding.editListenPort.text.toString().toIntOrNull() ?: 9000
                    prefs[VkTurnProxyConfig.KEY_MTU] = binding.editMtu.text.toString().toIntOrNull() ?: 1420
                    prefs[VkTurnProxyConfig.KEY_CONNECTIONS] = binding.editConnections.text.toString().toIntOrNull() ?: 16
                    prefs[VkTurnProxyConfig.KEY_USE_UDP] = binding.switchUdp.isChecked
                    prefs[VkTurnProxyConfig.KEY_TURN_SERVER] = binding.editTurnServer.text.toString()
                    prefs[VkTurnProxyConfig.KEY_TURN_PORT] = binding.editTurnPort.text.toString().toIntOrNull() ?: 19302
                    prefs[VkTurnProxyConfig.KEY_REALM] = binding.editRealm.text.toString()
                }
                Toast.makeText(this@VkTurnProxyActivity, R.string.vk_turn_proxy_settings_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@VkTurnProxyActivity, getString(R.string.vk_turn_proxy_settings_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun getConfigFromUi(): VkTurnProxyConfig {
        return VkTurnProxyConfig(
            enabled = binding.switchEnabled.isChecked,
            vkCallLink = binding.editVkLink.text.toString(),
            peerServerAddress = binding.editPeerAddress.text.toString(),
            peerServerPort = binding.editPeerPort.text.toString().toIntOrNull() ?: 56000,
            listenPort = binding.editListenPort.text.toString().toIntOrNull() ?: 9000,
            mtu = binding.editMtu.text.toString().toIntOrNull() ?: 1420,
            connections = binding.editConnections.text.toString().toIntOrNull() ?: 16,
            useUdp = binding.switchUdp.isChecked,
            turnServer = binding.editTurnServer.text.toString(),
            turnPort = binding.editTurnPort.text.toString().toIntOrNull() ?: 19302,
            realm = binding.editRealm.text.toString()
        )
    }
    
    private fun startProxy() {
        val config = getConfigFromUi()
        if (!config.isValid()) {
            Toast.makeText(this, R.string.vk_turn_proxy_invalid_config, Toast.LENGTH_LONG).show()
            return
        }
        
        saveSettings()
        VkTurnProxyService.startProxy(this, config)
    }
    
    private fun stopProxy() {
        VkTurnProxyService.stopProxy(this)
    }
    
    private fun updateUiState(isRunning: Boolean) {
        binding.apply {
            toggleButton.text = getString(
                if (isRunning) R.string.stop else R.string.vk_turn_proxy_start
            )
            toggleButton.setIconResource(
                if (isRunning) R.drawable.ic_action_delete else R.drawable.ic_tile
            )
            
            // Disable inputs while running
            switchEnabled.isEnabled = !isRunning
            editVkLink.isEnabled = !isRunning
            editPeerAddress.isEnabled = !isRunning
            editPeerPort.isEnabled = !isRunning
            editListenPort.isEnabled = !isRunning
            editMtu.isEnabled = !isRunning
            editConnections.isEnabled = !isRunning
            switchUdp.isEnabled = !isRunning
            editTurnServer.isEnabled = !isRunning
            editTurnPort.isEnabled = !isRunning
            editRealm.isEnabled = !isRunning
            saveButton.isEnabled = !isRunning
            
            statusText.text = getString(
                if (isRunning) R.string.vk_turn_proxy_status_running 
                else R.string.vk_turn_proxy_status_stopped
            )
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, VkTurnProxyService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.vk_turn_proxy, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.menu_help -> {
                showHelp()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun showHelp() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.vk_turn_proxy_help_title)
            .setMessage(R.string.vk_turn_proxy_help_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
    
    // Log adapter for RecyclerView
    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        
        private var items: List<VkTurnProxyService.LogEntry> = emptyList()
        
        private val defaultColor by lazy { resolveAttribute(com.google.android.material.R.attr.colorOnSurface) }
        private val debugColor by lazy { ResourcesCompat.getColor(resources, R.color.debug_tag_color, theme) }
        private val errorColor by lazy { ResourcesCompat.getColor(resources, R.color.error_tag_color, theme) }
        private val infoColor by lazy { ResourcesCompat.getColor(resources, R.color.info_tag_color, theme) }
        private val warningColor by lazy { ResourcesCompat.getColor(resources, R.color.warning_tag_color, theme) }
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val timestamp: MaterialTextView = view.findViewById(R.id.log_date)
            val message: MaterialTextView = view.findViewById(R.id.log_msg)
        }
        
        fun submitList(list: List<VkTurnProxyService.LogEntry>) {
            items = list
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.log_viewer_entry, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            holder.timestamp.text = entry.timestamp
            
            val color = when (entry.level) {
                "D" -> debugColor
                "E" -> errorColor
                "I" -> infoColor
                "W" -> warningColor
                else -> defaultColor
            }
            
            val spannable = SpannableString("[${entry.level}] ${entry.message}").apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(color), 0, 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            
            holder.message.text = spannable
        }
        
        override fun getItemCount(): Int = items.size
    }
}
