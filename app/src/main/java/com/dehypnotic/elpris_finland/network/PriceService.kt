package com.dehypnotic.elpris_finland.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate

data class ElprisetJustNuItem(
    val SEK_per_kWh: Double? = null,
    val DKK_per_kWh: Double? = null,
    val EUR_per_kWh: Double? = null,
    val time_start: String,
    val time_end: String
)

data class PricePoint(
    val price_per_kWh: Double,
    val time_start: String
)

fun fetchPrices(date: LocalDate, zone: String): List<PricePoint> {
    val year = date.year
    val month = String.format("%02d", date.monthValue)
    val day = String.format("%02d", date.dayOfMonth)
    val url = "https://www.elprisenligenu.dk/api/v1/prices/$year/$month-${day}_$zone.json"

    val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val req = Request.Builder()
        .url(url)
        .header("User-Agent", "ElprisDanmark/1.0 Android")
        .build()

    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            if (resp.code == 404) return emptyList()
            throw Exception("HTTP ${resp.code}")
        }
        val body = resp.body?.string() ?: throw Exception("Empty body")
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(List::class.java, ElprisetJustNuItem::class.java)
        val adapter = moshi.adapter<List<ElprisetJustNuItem>>(type)
        val items = adapter.fromJson(body) ?: emptyList()

        return items.map { item ->
            val price = item.DKK_per_kWh ?: item.SEK_per_kWh ?: item.EUR_per_kWh ?: 0.0
            PricePoint(
                price_per_kWh = price,
                time_start = item.time_start
            )
        }
    }
}
