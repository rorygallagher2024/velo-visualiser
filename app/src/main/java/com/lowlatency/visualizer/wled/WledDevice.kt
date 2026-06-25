package com.lowlatency.visualizer.wled

import java.net.InetAddress

data class WledDevice(
    val name: String,
    val ip: InetAddress,
    var isSelected: Boolean = false
)
