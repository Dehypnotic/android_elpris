package com.dehypnotic.elpris_norge.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate

data class PricePoint(
    val NOK_per_kWh: Double,
    val EUR_per_kWh: Double,
    val EXR: Double,
    val time_start: String,
    val time_end: String
)

suspend fun fetchPrices(date: LocalDate, zone: String): List<PricePoint> {
    val url = "https://www.hvakosterstrommen.no/api/v1/prices/%d/%02d-%02d_%s.json".format(
        date.year,
        date.monthValue,
        date.dayOfMonth,
        zone
    )

    val client = OkHttpClient()
    val req = Request.Builder().url(url).build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        val body = resp.body?.string() ?: error("Empty body")
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(List::class.java, PricePoint::class.java)
        val adapter = moshi.adapter<List<PricePoint>>(type)
        return adapter.fromJson(body) ?: emptyList()
    }
}
