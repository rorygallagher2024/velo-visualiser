package com.lowlatency.visualizer.nanoleaf

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain HTTP calls against one panel's REST API. Everything here blocks and
 * swallows network failures into null/false returns — call off the main thread.
 */
internal object NanoleafApi {

    private const val TAG = "Nanoleaf"
    private const val TIMEOUT_MS = 2000
    const val DEFAULT_UDP_PORT = 60222

    /**
     * The device's user-assigned name ("Living Room Shapes"), or null when the
     * device is unreachable or rejects the token — so this doubles as the
     * reachability probe: a non-null return is proof the pairing works. May be
     * empty if the device reports no name.
     */
    fun fetchName(host: String, port: Int, token: String): String? = try {
        val conn = open("http://$host:$port/api/v1/$token/")
        val name = if (conn.responseCode == 200) {
            parseName(conn.inputStream.bufferedReader().readText())
        } else {
            null
        }
        conn.disconnect()
        name
    } catch (_: Exception) {
        null
    }

    private fun parseName(body: String): String = try {
        JSONObject(body).optString("name")
    } catch (_: Exception) {
        ""
    }

    /** Poll the pairing endpoint. Answers only while the device's window is open. */
    fun requestToken(host: String, port: Int): String? = try {
        val conn = open("http://$host:$port/api/v1/new")
        conn.requestMethod = "POST"
        val token = if (conn.responseCode == 200 || conn.responseCode == 201) {
            JSONObject(conn.inputStream.bufferedReader().readText()).getString("auth_token")
        } else {
            null
        }
        conn.disconnect()
        token
    } catch (_: Exception) {
        null
    }

    /** Static on/off/colour state, for the scene buttons and the stop-sync blackout. */
    fun setState(creds: NanoleafCredentials, on: Boolean, bri: Float?, hue: Float?, sat: Float?) {
        try {
            val conn = open("http://${creds.ip}:${creds.port}/api/v1/${creds.authToken}/state")
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject()
            body.put("on", JSONObject().put("value", on))
            bri?.let {
                body.put("brightness", JSONObject().put("value", (it * 100).toInt().coerceIn(1, 100)))
            }
            hue?.let { body.put("hue", JSONObject().put("value", it.toInt().coerceIn(0, 360))) }
            sat?.let { body.put("sat", JSONObject().put("value", (it * 100).toInt().coerceIn(0, 100))) }

            conn.outputStream.write(body.toString().toByteArray())
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    /** The device's panel ids from its layout. Id 0 is the controller, not a panel. */
    fun fetchPanelIds(creds: NanoleafCredentials): List<Int> {
        val conn = open("http://${creds.ip}:${creds.port}/api/v1/${creds.authToken}/")
        val response = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val posData = JSONObject(response)
            .getJSONObject("panelLayout").getJSONObject("layout").getJSONArray("positionData")
        val ids = mutableListOf<Int>()
        for (i in 0 until posData.length()) {
            val pId = posData.getJSONObject(i).getInt("panelId")
            if (pId != 0) ids.add(pId)
        }
        return ids
    }

    /** Enable ExtControl v2 streaming. Returns the UDP port, or null on refusal. */
    fun enableExtControl(creds: NanoleafCredentials): Int? {
        val conn = open("http://${creds.ip}:${creds.port}/api/v1/${creds.authToken}/effects")
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        val payload = JSONObject().apply {
            put("write", JSONObject().apply {
                put("command", "display")
                put("animType", "extControl")
                put("extControlVersion", "v2")
            })
        }
        conn.outputStream.write(payload.toString().toByteArray())
        val code = conn.responseCode
        var udpPort: Int? = if (code == 200 || code == 204) DEFAULT_UDP_PORT else null
        if (code == 200) {
            try {
                val putJson = JSONObject(conn.inputStream.bufferedReader().readText())
                if (putJson.has("streamControlPort")) {
                    udpPort = putJson.getInt("streamControlPort")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse extControl response: ${e.message}")
            }
        } else if (udpPort == null) {
            Log.e(TAG, "Failed to start extControl on ${creds.ip}: $code")
        }
        conn.disconnect()
        return udpPort
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
}
