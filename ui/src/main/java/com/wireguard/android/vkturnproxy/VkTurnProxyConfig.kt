/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.vkturnproxy

import android.os.Parcel
import android.os.Parcelable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Configuration model for VK Turn Proxy settings
 */
data class VkTurnProxyConfig(
    val enabled: Boolean = false,
    val vkCallLink: String = "",
    val peerServerAddress: String = "",
    val peerServerPort: Int = 56000,
    val listenPort: Int = 9000,
    val mtu: Int = 1420,
    val connections: Int = 16,
    val useUdp: Boolean = false,
    val turnServer: String = "",
    val turnPort: Int = 19302,
    val realm: String = "call6-7.vkuser.net",
    val dnsServer: String = "8.8.8.8",  // DNS server for Go HTTP client
    val forceIpv4: Boolean = true       // Force IPv4 to avoid IPv6 DNS issues
) : Parcelable {
    
    constructor(parcel: Parcel) : this(
        parcel.readByte() != 0.toByte(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.readString() ?: "call6-7.vkuser.net",
        parcel.readString() ?: "8.8.8.8",
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (enabled) 1 else 0)
        parcel.writeString(vkCallLink)
        parcel.writeString(peerServerAddress)
        parcel.writeInt(peerServerPort)
        parcel.writeInt(listenPort)
        parcel.writeInt(mtu)
        parcel.writeInt(connections)
        parcel.writeByte(if (useUdp) 1 else 0)
        parcel.writeString(turnServer)
        parcel.writeInt(turnPort)
        parcel.writeString(realm)
        parcel.writeString(dnsServer)
        parcel.writeByte(if (forceIpv4) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<VkTurnProxyConfig> {
        override fun createFromParcel(parcel: Parcel): VkTurnProxyConfig = VkTurnProxyConfig(parcel)
        override fun newArray(size: Int): Array<VkTurnProxyConfig?> = arrayOfNulls(size)
        
        /** Length of VK call link code (the unique identifier portion of the URL) */
        const val VK_LINK_CODE_LENGTH = 43
        
        // DataStore preference keys
        val KEY_ENABLED = booleanPreferencesKey("vk_turn_proxy_enabled")
        val KEY_VK_CALL_LINK = stringPreferencesKey("vk_turn_proxy_call_link")
        val KEY_PEER_SERVER_ADDRESS = stringPreferencesKey("vk_turn_proxy_peer_address")
        val KEY_PEER_SERVER_PORT = intPreferencesKey("vk_turn_proxy_peer_port")
        val KEY_LISTEN_PORT = intPreferencesKey("vk_turn_proxy_listen_port")
        val KEY_MTU = intPreferencesKey("vk_turn_proxy_mtu")
        val KEY_CONNECTIONS = intPreferencesKey("vk_turn_proxy_connections")
        val KEY_USE_UDP = booleanPreferencesKey("vk_turn_proxy_use_udp")
        val KEY_TURN_SERVER = stringPreferencesKey("vk_turn_proxy_turn_server")
        val KEY_TURN_PORT = intPreferencesKey("vk_turn_proxy_turn_port")
        val KEY_REALM = stringPreferencesKey("vk_turn_proxy_realm")
        val KEY_DNS_SERVER = stringPreferencesKey("vk_turn_proxy_dns_server")
        val KEY_FORCE_IPV4 = booleanPreferencesKey("vk_turn_proxy_force_ipv4")
    }
    
    /**
     * Extract link code from VK call URL
     * VK call links have format: https://vk.com/call/join/XXXX...
     * The link code is the last VK_LINK_CODE_LENGTH characters of the URL
     */
    fun extractLinkCode(): String {
        if (vkCallLink.isBlank()) return ""
        val link = vkCallLink.trim()
        // Link format: https://vk.com/call/join/XXXXXX...
        return if (link.length >= VK_LINK_CODE_LENGTH) {
            link.substring(link.length - VK_LINK_CODE_LENGTH)
        } else {
            link
        }
    }
    
    /**
     * Get WireGuard endpoint to use when VK Turn Proxy is enabled
     */
    fun getLocalEndpoint(): String = "127.0.0.1:$listenPort"
    
    /**
     * Validate configuration
     */
    fun isValid(): Boolean {
        return vkCallLink.isNotBlank() && 
               peerServerAddress.isNotBlank() && 
               peerServerPort in 1..65535 &&
               listenPort in 1..65535 &&
               mtu in 576..65535 &&
               connections in 1..64
    }
}
