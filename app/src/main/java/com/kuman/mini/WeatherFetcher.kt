package com.kuman.mini

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a short weather summary from wttr.in (free, no API key).
 *
 * Privacy: only a coordinate pair (or a city name) is sent to the server.
 * No conversation content or personal data is ever transmitted.
 */
object WeatherFetcher {

    private const val TIMEOUT_MS = 8000

    /**
     * @param latLon "lat,lon" string, or null for a default location (Seoul).
     * @return Korean one-paragraph weather summary, or null on failure.
     */
    fun fetchSummary(latLon: String? = null): String? = try {
        val location = latLon ?: "Seoul"
        val url = URL("https://wttr.in/$location?format=j1&lang=ko")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "kuman-mini/1.0")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            return null
        }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        parseSummary(body)
    } catch (e: Exception) {
        null
    }

    private fun parseSummary(body: String): String? = try {
        val root = JSONObject(body)
        val current = root.getJSONArray("current_condition").getJSONObject(0)
        val today = root.getJSONArray("weather").getJSONObject(0)

        val temp = current.optString("temp_C")
        val feels = current.optString("FeelsLikeC")
        val desc = current.optJSONArray("weatherDesc")
            ?.optJSONObject(0)?.optString("value") ?: ""
        val humidity = current.optString("humidity")
        val wind = current.optString("windspeedKmph")
        val max = today.optString("maxtempC")
        val min = today.optString("mintempC")

        // Hourly rain chance for the next few hours
        val rainChance = today.optJSONArray("hourly")?.let { hours ->
            var maxChance = 0
            for (i in 0 until minOf(hours.length(), 8)) {
                val c = hours.getJSONObject(i).optString("chanceofrain").toIntOrNull() ?: 0
                if (c > maxChance) maxChance = c
            }
            maxChance
        } ?: 0

        buildString {
            append("현재 기온 ${temp}°C")
            if (feels.isNotEmpty()) append("(체감 ${feels}°C)")
            if (desc.isNotEmpty()) append(", $desc")
            append(". 습도 ${humidity}%, 바람 ${wind}km/h.")
            if (max.isNotEmpty() && min.isNotEmpty()) {
                append(" 오늘 최고 ${max}°C / 최저 ${min}°C.")
            }
            if (rainChance > 0) append(" 강수확률 최대 ${rainChance}%.")
        }
    } catch (e: Exception) {
        null
    }
}
