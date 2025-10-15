package com.example.android_elpris

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.DatePicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.android_elpris.network.PricePoint
import com.example.android_elpris.network.fetchPrices
import com.example.android_elpris.ui.theme.Android_elprisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Android_elprisTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PriceScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun PriceScreen(modifier: Modifier = Modifier) {
    var prices by remember { mutableStateOf<List<PricePoint>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var selectedZone by remember { mutableStateOf("NO3") }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    LaunchedEffect(selectedZone, selectedDate) {
        isLoading = true
        error = null
        try {
            val fetchedPrices = withContext(Dispatchers.IO) {
                fetchPrices(selectedDate, selectedZone)
            }
            prices = fetchedPrices
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ZoneSelector(
            selectedZone = selectedZone,
            onZoneSelected = { selectedZone = it },
            modifier = Modifier.padding(8.dp)
        )
        DateSelector(
            selectedDate = selectedDate,
            onDateSelected = { selectedDate = it },
            modifier = Modifier.padding(8.dp)
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                error != null -> {
                    Text(
                        text = "Error: $error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                }
                prices.isEmpty() && !isLoading -> {
                    Text(
                        text = "Ingen priser funnet for denne dagen.",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    PriceChart(prices = prices, zone = selectedZone)
                }
            }
        }
    }
}

@Composable
fun ZoneSelector(selectedZone: String, onZoneSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    val zones = listOf("NO1", "NO2", "NO3", "NO4", "NO5")
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        zones.forEach { zone ->
            Button(
                onClick = { onZoneSelected(zone) },
                enabled = selectedZone != zone
            ) {
                Text(text = zone)
            }
        }
    }
}

@Composable
fun DateSelector(selectedDate: LocalDate, onDateSelected: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isTomorrowAvailable = LocalTime.now().hour >= 13

    val year = selectedDate.year
    val month = selectedDate.monthValue - 1
    val day = selectedDate.dayOfMonth

    val datePickerDialog = DatePickerDialog(
        context,
        { _: DatePicker, selectedYear: Int, selectedMonth: Int, selectedDayOfMonth: Int ->
            onDateSelected(LocalDate.of(selectedYear, selectedMonth + 1, selectedDayOfMonth))
        },
        year, month, day
    )

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onDateSelected(LocalDate.now().minusDays(1)) }) { Text("I går") }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { onDateSelected(LocalDate.now()) }) { Text("I dag") }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { onDateSelected(LocalDate.now().plusDays(1)) }, enabled = isTomorrowAvailable) { Text("I morgen") }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { datePickerDialog.show() }) { Text("Velg dato") }
    }
}

@Composable
fun PriceChart(prices: List<PricePoint>, zone: String, modifier: Modifier = Modifier) {
    val pricesWithVat = remember(prices, zone) {
        prices.map {
            val price = if (zone != "NO4") it.NOK_per_kWh * 1.25 else it.NOK_per_kWh
            it to price * 100 // Pair the original PricePoint with the calculated øre price
        }
    }

    val maxPrice = remember(pricesWithVat) {
        pricesWithVat.maxOfOrNull { it.second } ?: 1.0
    }

    val vatLabel = if (zone != "NO4") "Priser i øre/kWh inkl. mva" else "Priser i øre/kWh"

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(text = vatLabel, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        LazyColumn {
            items(pricesWithVat) { (pricePoint, priceInOre) ->
                ChartBar(pricePoint = pricePoint, priceInOre = priceInOre, maxPrice = maxPrice)
            }
        }
    }
}

@Composable
fun ChartBar(pricePoint: PricePoint, priceInOre: Double, maxPrice: Double, modifier: Modifier = Modifier) {
    val barFraction = (priceInOre / maxPrice).toFloat()
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH") }
    val hour = remember(pricePoint.time_start) {
        timeFormatter.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(pricePoint.time_start))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = hour, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(24.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth(barFraction)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = "%.2f".format(priceInOre),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
