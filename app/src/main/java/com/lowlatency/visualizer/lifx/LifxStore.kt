package com.lowlatency.visualizer.lifx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LifxStore(context: Context) {
    private val prefs = context.getSharedPreferences("lifx_prefs", Context.MODE_PRIVATE)
    private val KEY_BULBS = "cached_bulbs"

    fun saveBulbs(bulbs: List<LifxBulb>) {
        val jsonArray = JSONArray()
        for (bulb in bulbs) {
            val obj = JSONObject()
            obj.put("ip", bulb.ip)
            obj.put("mac", android.util.Base64.encodeToString(bulb.mac, android.util.Base64.NO_WRAP))
            obj.put("label", bulb.label)
            obj.put("isSelected", bulb.isSelected)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_BULBS, jsonArray.toString()).apply()
    }

    fun clearBulbs() {
        prefs.edit().remove(KEY_BULBS).apply()
    }

    fun loadBulbs(): List<LifxBulb> {
        val jsonString = prefs.getString(KEY_BULBS, null) ?: return emptyList()
        val bulbs = mutableListOf<LifxBulb>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ip = obj.getString("ip")
                val macBase64 = obj.getString("mac")
                val mac = android.util.Base64.decode(macBase64, android.util.Base64.NO_WRAP)
                val label = obj.getString("label")
                val isSelected = obj.getBoolean("isSelected")
                
                val bulb = LifxBulb(ip, mac, label)
                bulb.isSelected = isSelected
                bulbs.add(bulb)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return bulbs
    }
}
