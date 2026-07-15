package ame.project.nlstudio.scene

import org.json.JSONArray
import org.json.JSONObject

data class VoiceAnimItem(
    var threshold: Float, // 0.0 to 1.0
    var imageUri: String
)

data class VoiceAnimConfig(
    val items: MutableList<VoiceAnimItem> = mutableListOf(),
    var minBrightness: Float = 0.5f,
    var scaleIntensity: Float = 0.04f,
    var effectThresholdStart: Float = 0.0f,
    var effectThresholdEnd: Float = 0.5f
) {
    fun toJson(): String {
        val o = JSONObject()
        val arr = JSONArray()
        items.forEach {
            val io = JSONObject()
            io.put("threshold", it.threshold.toDouble())
            io.put("imageUri", it.imageUri)
            arr.put(io)
        }
        o.put("items", arr)
        o.put("minBrightness", minBrightness.toDouble())
        o.put("scaleIntensity", scaleIntensity.toDouble())
        o.put("effectThresholdStart", effectThresholdStart.toDouble())
        o.put("effectThresholdEnd", effectThresholdEnd.toDouble())
        return o.toString()
    }

    companion object {
        fun fromJson(json: String?): VoiceAnimConfig {
            if (json == null || json.isEmpty()) return VoiceAnimConfig()
            return try {
                val o = JSONObject(json)
                val arr = o.getJSONArray("items")
                val items = mutableListOf<VoiceAnimItem>()
                for (i in 0 until arr.length()) {
                    val io = arr.getJSONObject(i)
                    items.add(
                        VoiceAnimItem(
                            threshold = io.getDouble("threshold").toFloat(),
                            imageUri = io.getString("imageUri")
                        )
                    )
                }
                VoiceAnimConfig(
                    items = items,
                    minBrightness = o.optDouble("minBrightness", 0.5).toFloat(),
                    scaleIntensity = o.optDouble("scaleIntensity", 0.04).toFloat(),
                    effectThresholdStart = o.optDouble("effectThresholdStart", 0.0).toFloat(),
                    effectThresholdEnd = o.optDouble("effectThresholdEnd", 0.5).toFloat()
                )
            } catch (e: Exception) {
                VoiceAnimConfig()
            }
        }
    }
}
