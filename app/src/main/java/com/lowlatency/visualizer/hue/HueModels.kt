package com.lowlatency.visualizer.hue

/**
 * Plain data types shared across the Hue integration.
 */

/** A bridge found on the LAN (or via the cloud discovery endpoint). */
data class HueBridge(
    val id: String,
    val ip: String,
)

/** One channel (a controllable light/segment) inside an Entertainment Area. */
data class HueChannel(
    val channelId: Int,
)

/** An Entertainment Configuration (a.k.a. "Entertainment Area") on the bridge. */
data class HueEntertainmentArea(
    val id: String,            // 36-char UUID — also goes in the stream header
    val name: String,
    val channels: List<HueChannel>,
)

/** Persisted pairing credentials for one bridge. */
data class HueCredentials(
    val bridgeIp: String,
    val username: String,      // "hue-application-key" / DTLS identity
    val clientKey: String,     // hex string; hex-decoded => DTLS PSK
)
