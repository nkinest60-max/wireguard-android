/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.vkturnproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.collection.CircularArray
import androidx.core.app.NotificationCompat
import com.wireguard.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Foreground service that runs the VK Turn Proxy client
 */
class VkTurnProxyService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxyJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    private val _logLines = MutableStateFlow<List<LogEntry>>(emptyList())
    val logLines: StateFlow<List<LogEntry>> = _logLines.asStateFlow()
    
    private val logBuffer = CircularArray<LogEntry>(MAX_LOG_LINES)
    private var currentConfig: VkTurnProxyConfig? = null
    private var nativeProcess: Process? = null
    
    data class LogEntry(
        val timestamp: String,
        val level: String,
        val message: String
    )

    inner class LocalBinder : Binder() {
        fun getService(): VkTurnProxyService = this@VkTurnProxyService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CONFIG, VkTurnProxyConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CONFIG)
                }
                config?.let { startProxy(it) }
            }
            ACTION_STOP -> stopProxy()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopProxy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vk_turn_proxy_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vk_turn_proxy_notification_channel_desc)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, VkTurnProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vk_turn_proxy_running))
            .setContentText(getString(R.string.vk_turn_proxy_running_desc))
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(true)
            .addAction(R.drawable.ic_action_delete, getString(R.string.stop), stopPendingIntent)
            .build()
    }

    fun startProxy(config: VkTurnProxyConfig) {
        if (_isRunning.value) {
            addLogEntry("W", "Proxy already running")
            return
        }
        
        currentConfig = config
        
        if (!config.isValid()) {
            addLogEntry("E", "Invalid configuration")
            return
        }
        
        startForeground(NOTIFICATION_ID, createNotification())
        acquireWakeLock()
        
        proxyJob = serviceScope.launch {
            _isRunning.value = true
            addLogEntry("I", "Starting VK Turn Proxy...")
            addLogEntry("I", "Peer: ${config.peerServerAddress}:${config.peerServerPort}")
            addLogEntry("I", "Listen: 127.0.0.1:${config.listenPort}")
            addLogEntry("I", "Connections: ${config.connections}")
            addLogEntry("I", "UDP: ${config.useUdp}")
            
            runProxy(config)
        }
    }

    /**
     * Extract and prepare the native binary for execution.
     * Copies from nativeLibraryDir to filesDir and sets executable permission.
     */
    private fun prepareBinary(): File? {
        val binaryName = "vkturnproxy"
        val targetFile = File(filesDir, binaryName)
        
        // Check if binary already exists and is executable
        if (targetFile.exists() && targetFile.canExecute()) {
            addLogEntry("D", "Binary already prepared: ${targetFile.absolutePath}")
            return targetFile
        }
        
        // Try to find source binary - check multiple locations
        val sourceFile = findSourceBinary()
        
        if (sourceFile == null) {
            addLogEntry("E", "Source binary not found in any location")
            return null
        }
        
        try {
            val sourcePath = sourceFile.absolutePath
            if (sourcePath.contains("!/")) {
                val parts = sourcePath.split("!/")
                val apkPath = parts[0]
                val libPathInApk = parts[1]
                addLogEntry("D", "Extracting from APK: $apkPath, entry: $libPathInApk")
                if (extractBinaryFromApk(apkPath, libPathInApk, targetFile)) {
                    if (!targetFile.setExecutable(true, false)) {
                        addLogEntry("W", "Failed to set executable permission")
                    }
                    addLogEntry("I", "Binary extracted and prepared: ${targetFile.absolutePath}")
                    return targetFile
                }
                return null
            }

            addLogEntry("D", "Copying binary from $sourcePath")
            // Copy file to filesDir
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Set executable permission
            if (!targetFile.setExecutable(true, false)) {
                addLogEntry("W", "Failed to set executable permission")
            }
            
            addLogEntry("I", "Binary prepared: ${targetFile.absolutePath}")
            return targetFile
        } catch (e: Exception) {
            addLogEntry("E", "Failed to prepare binary: ${e.message}")
            return null
        }
    }

    /**
     * Extract a binary from an APK file.
     */
    private fun extractBinaryFromApk(apkPath: String, libraryPathInApk: String, targetFile: File): Boolean {
        try {
            ZipFile(apkPath).use { zipFile ->
                val entry = zipFile.getEntry(libraryPathInApk)
                if (entry == null) {
                    addLogEntry("E", "Entry $libraryPathInApk not found in APK")
                    return false
                }
                zipFile.getInputStream(entry).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            return true
        } catch (e: Exception) {
            addLogEntry("E", "Failed to extract from APK: ${e.message}")
            return false
        }
    }
    
    /**
     * Find the source binary in various possible locations.
     * Android may put native libraries in different directories depending on ABI.
     * The nativeLibraryDir may return short names (arm64) but files are in full ABI dirs (arm64-v8a).
     */
    private fun findSourceBinary(): File? {
        val binaryName = "libvkturnproxy.so"
        val libShortName = "vkturnproxy" // without lib prefix and .so suffix
        
        // 1. Try using ClassLoader to find the library path (most reliable method)
        try {
            val classLoader = applicationContext.classLoader
            // Use reflection to call findLibrary method
            val findLibraryMethod = classLoader.javaClass.getMethod("findLibrary", String::class.java)
            val libraryPath = findLibraryMethod.invoke(classLoader, libShortName) as? String
            if (libraryPath != null) {
                if (libraryPath.contains("!/")) {
                    addLogEntry("D", "ClassLoader returned path inside APK: $libraryPath")
                    return File(libraryPath)
                }
                val file = File(libraryPath)
                if (file.exists()) {
                    addLogEntry("D", "Found via ClassLoader: ${file.absolutePath}")
                    return file
                }
            }
        } catch (e: Exception) {
            addLogEntry("D", "ClassLoader method failed: ${e.message}")
        }
        
        // 2. Try nativeLibraryDir directly
        val nativeLibDir = File(applicationInfo.nativeLibraryDir)
        addLogEntry("D", "Checking nativeLibraryDir: ${nativeLibDir.absolutePath}")
        
        val standardPath = File(nativeLibDir, binaryName)
        if (standardPath.exists()) {
            addLogEntry("D", "Found in nativeLibraryDir: ${standardPath.absolutePath}")
            return standardPath
        }
        
        // 3. The nativeLibraryDir may have wrong ABI name (arm64 vs arm64-v8a)
        // Try to get the parent "lib" directory and scan all ABI subdirectories
        val libDir = nativeLibDir.parentFile
        if (libDir != null && libDir.name == "lib") {
            addLogEntry("D", "Scanning lib directory: ${libDir.absolutePath}")
            libDir.listFiles()?.forEach { abiDir ->
                if (abiDir.isDirectory) {
                    val binaryFile = File(abiDir, binaryName)
                    addLogEntry("D", "Checking ABI: ${abiDir.name} -> ${binaryFile.absolutePath}")
                    if (binaryFile.exists()) {
                        addLogEntry("D", "Found in ABI: ${binaryFile.absolutePath}")
                        return binaryFile
                    }
                }
            }
        }
        
        // 4. Also check common ABI names explicitly
        val abiNames = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        if (libDir != null) {
            for (abi in abiNames) {
                val binaryFile = File(File(libDir, abi), binaryName)
                if (binaryFile.exists()) {
                    addLogEntry("D", "Found via explicit ABI: ${binaryFile.absolutePath}")
                    return binaryFile
                }
            }
        }
        
        // 5. Try app's source directory
        val appDir = applicationInfo.sourceDir?.let { File(it).parentFile }
        if (appDir != null && appDir != libDir?.parentFile) {
            addLogEntry("D", "Checking app directory: ${appDir.absolutePath}")
            val appLibDir = File(appDir, "lib")
            if (appLibDir.exists() && appLibDir.isDirectory) {
                appLibDir.listFiles()?.forEach { abiDir ->
                    if (abiDir.isDirectory) {
                        val binaryFile = File(abiDir, binaryName)
                        if (binaryFile.exists()) {
                            addLogEntry("D", "Found in app lib: ${binaryFile.absolutePath}")
                            return binaryFile
                        }
                    }
                }
            }
        }
        
        addLogEntry("E", "Binary not found in any location")
        return null
    }

    private suspend fun runProxy(config: VkTurnProxyConfig) {
        try {
            val linkCode = config.extractLinkCode()
            if (linkCode.isBlank()) {
                addLogEntry("E", "Invalid VK call link")
                stopProxy()
                return
            }
            
            addLogEntry("I", "Connecting to TURN server...")
            
            // Prepare binary (copy to filesDir and set executable)
            val binaryFile = prepareBinary()
            if (binaryFile == null) {
                addLogEntry("E", "Failed to prepare native binary")
                addLogEntry("W", "Native VK Turn Proxy binary is not available.")
                addLogEntry("I", "Please rebuild the APK with vk-turn-proxy support.")
                addLogEntry("I", "See: https://github.com/cacggghp/vk-turn-proxy")
                stopProxy()
                return
            }
            
            // Build command arguments for native binary
            val args = mutableListOf(
                binaryFile.absolutePath,
                "-peer", "${config.peerServerAddress}:${config.peerServerPort}",
                "-link", linkCode,
                "-listen", "127.0.0.1:${config.listenPort}",
                "-n", config.connections.toString()
            )
            
            if (config.useUdp) {
                args.add("-udp")
            }
            
            if (config.turnServer.isNotBlank()) {
                args.addAll(listOf("-turn", config.turnServer))
                args.addAll(listOf("-port", config.turnPort.toString()))
            }
            
            if (config.realm.isNotBlank()) {
                args.addAll(listOf("-realm", config.realm))
            }
            
            addLogEntry("D", "Command: ${args.joinToString(" ")}")
            
            try {
                val processBuilder = ProcessBuilder(args)
                    .redirectErrorStream(true)
                
                // Set environment variables to force IPv4 DNS resolution
                val env = processBuilder.environment()
                
                // GODEBUG=netdns=go forces pure Go DNS resolver (works better on Android)
                // asyncpreemptoff=1 avoids crashes on some Android kernels/emulators
                val godebug = "netdns=go,asyncpreemptoff=1"
                env["GODEBUG"] = godebug
                
                // Force IPv4 preference to avoid IPv6 DNS issues
                if (config.forceIpv4) {
                    // Use custom DNS server via environment
                    env["DNS_SERVER"] = config.dnsServer
                    // Force IPv4 network preference
                    env["PREFER_IPV4"] = "1"
                    // Force disable IPv6
                    env["DISABLE_IPV6"] = "1"
                    addLogEntry("D", "Environment: GODEBUG=$godebug DNS_SERVER=${config.dnsServer} PREFER_IPV4=1 DISABLE_IPV6=1")
                } else {
                    addLogEntry("D", "Environment: GODEBUG=$godebug")
                }
                
                nativeProcess = processBuilder.start()
                
                addLogEntry("I", "Proxy process started")
                
                // Read output in separate coroutine
                val reader = BufferedReader(InputStreamReader(nativeProcess!!.inputStream))
                var line: String?
                
                while (reader.readLine().also { line = it } != null) {
                    line?.let { 
                        val level = when {
                            it.contains("error", ignoreCase = true) -> "E"
                            it.contains("warn", ignoreCase = true) -> "W"
                            it.contains("DTLS") || it.contains("established", ignoreCase = true) -> "I"
                            else -> "D"
                        }
                        addLogEntry(level, it) 
                    }
                }
                
                val exitCode = nativeProcess?.waitFor() ?: -1
                addLogEntry("I", "Proxy process exited with code: $exitCode")
                
            } catch (e: Exception) {
                addLogEntry("E", "Failed to start native process: ${e.message}")
                addLogEntry("W", "Native VK Turn Proxy binary is not available.")
                addLogEntry("I", "Please rebuild the APK with vk-turn-proxy support.")
                addLogEntry("I", "See: https://github.com/cacggghp/vk-turn-proxy")
                
                // Stop the service since native binary is not available
                stopProxy()
                return
            }
            
        } catch (e: Exception) {
            addLogEntry("E", "Proxy error: ${e.message}")
            Log.e(TAG, "Proxy error", e)
        } finally {
            stopProxy()
        }
    }

    fun stopProxy() {
        proxyJob?.cancel()
        proxyJob = null
        
        nativeProcess?.destroy()
        nativeProcess = null
        
        releaseWakeLock()
        _isRunning.value = false
        
        addLogEntry("I", "VK Turn Proxy stopped")
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VkTurnProxy::WakeLock"
            ).apply {
                acquire(MAX_WAKE_LOCK_DURATION_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun addLogEntry(level: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = LogEntry(timestamp, level, message)
        
        synchronized(logBuffer) {
            if (logBuffer.size() >= MAX_LOG_LINES) {
                logBuffer.popFirst()
            }
            logBuffer.addLast(entry)
            
            val list = mutableListOf<LogEntry>()
            for (i in 0 until logBuffer.size()) {
                list.add(logBuffer[i])
            }
            _logLines.value = list
        }
        
        Log.d(TAG, "[$level] $message")
    }
    
    fun clearLogs() {
        synchronized(logBuffer) {
            while (!logBuffer.isEmpty()) {
                logBuffer.popFirst()
            }
            _logLines.value = emptyList()
        }
    }

    fun getConfig(): VkTurnProxyConfig? = currentConfig

    companion object {
        private const val TAG = "WireGuard/VkTurnProxyService"
        private const val CHANNEL_ID = "vk_turn_proxy_channel"
        private const val NOTIFICATION_ID = 2
        private const val MAX_LOG_LINES = 1000
        /** Maximum duration for wake lock in milliseconds (10 hours) */
        private const val MAX_WAKE_LOCK_DURATION_MS = 10 * 60 * 60 * 1000L
        
        const val ACTION_START = "com.wireguard.android.action.VK_TURN_PROXY_START"
        const val ACTION_STOP = "com.wireguard.android.action.VK_TURN_PROXY_STOP"
        const val EXTRA_CONFIG = "config"
        
        fun startProxy(context: Context, config: VkTurnProxyConfig) {
            val intent = Intent(context, VkTurnProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, config)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopProxy(context: Context) {
            val intent = Intent(context, VkTurnProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
