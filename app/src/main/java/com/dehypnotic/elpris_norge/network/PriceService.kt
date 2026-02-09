package com.dehypnotic.elpris_norge.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate

data class ElprisetJustNuItem(
    val SEK_per_kWh: Double,
    val time_start: String,
    val time_end: String
)

data class PricePoint(
    val price_per_kWh: Double,
    val time_start: String
)

fun fetchPrices(date: LocalDate, zone: String): List<PricePoint> {
    val url = "https://www.elprisetjustnu.se/api/v1/prices/%d/%02d-%02d_%s.json".format(
        date.year,
        date.monthValue,
        date.dayOfMonth,
        zone
    )

    val client = OkHttpClient()
    val req = Request.Builder().url(url).build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            if (resp.code == 404) return emptyList()
            error("HTTP ${resp.code}")
        }
        val body = resp.body?.string() ?: error("Empty body")
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(List::class.java, ElprisetJustNuItem::class.java)
        val adapter = moshi.adapter<List<ElprisetJustNuItem>>(type)
        val items = adapter.fromJson(body) ?: emptyList()

        return items.chunked(4).map { chunk ->
            PricePoint(
                price_per_kWh = chunk.map { it.SEK_per_kWh }.average(),
                time_start = chunk.first().time_start
            )
        }
    }
}
