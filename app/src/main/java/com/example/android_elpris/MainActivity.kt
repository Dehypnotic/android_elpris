package com.example.android_elpris

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.DatePicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
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
                    PriceList(prices = prices)
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
fun PriceList(prices: List<PricePoint>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(prices) { price ->
            PriceRow(price = price)
        }
    }
}

@Composable
fun PriceRow(price: PricePoint, modifier: Modifier = Modifier) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val startTime = remember(price.time_start) {
        price.time_start.let { timeFormatter.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(it)) }
    }
    val endTime = remember(price.time_end) {
        price.time_end.let { timeFormatter.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(it)) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "$startTime - $endTime")
        Text(text = "%.2f NOK/kWh".format(price.NOK_per_kWh))
    }
}