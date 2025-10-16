package com.example.android_elpris

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.widget.DatePicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.example.android_elpris.network.PricePoint
import com.example.android_elpris.network.fetchPrices
import com.example.android_elpris.ui.theme.Android_elprisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

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

    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    var selectedZone by remember {
        mutableStateOf(sharedPrefs.getString("selected_zone", "NO3") ?: "NO3")
    }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    LaunchedEffect(selectedZone, selectedDate) {
        isLoading = true
        error = null
        prices = emptyList()
        try {
            val fetchedPrices = withContext(Dispatchers.IO) {
                fetchPrices(selectedDate, selectedZone)
            }
            prices = fetchedPrices
            if (fetchedPrices.isEmpty()) {
                error = "Ingen priser funnet for denne dagen."
            }
        } catch (e: Exception) {
            val today = LocalDate.now()
            error = if (e.message?.contains("HTTP 404") == true) {
                when {
                    selectedDate.isAfter(today.plusDays(1)) ->
                        "Fremtidige priser strekker seg kun til påfølgende dag etter publisering tidligst kl 13"
                    selectedDate.isEqual(today.plusDays(1)) ->
                        "Prisene for i morgen er ikke klare ennå. De publiseres vanligvis etter kl. 13."
                    else ->
                        "Ingen priser funnet for denne dagen."
                }
            } else {
                "En feil oppstod: ${e.message}"
            }
        } finally {
            isLoading = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ZoneSelector(
            selectedZone = selectedZone,
            onZoneSelected = { newZone ->
                selectedZone = newZone
                sharedPrefs.edit { putString("selected_zone", newZone) }
            },
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
                        text = error!!,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                }
                else -> {
                    PriceChart(prices = prices, zone = selectedZone, selectedDate = selectedDate)
                }
            }
        }
    }
}

@Composable
fun ZoneSelector(selectedZone: String, onZoneSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    val zones = listOf("NO1", "NO2", "NO3", "NO4", "NO5")
    val selectedIndex = zones.indexOf(selectedZone)

    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier.fillMaxWidth()
    ) {
        zones.forEachIndexed { index, zone ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onZoneSelected(zone) },
                text = { Text(text = zone) }
            )
        }
    }
}

@Composable
fun DateSelector(selectedDate: LocalDate, onDateSelected: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isTomorrowAvailable = LocalTime.now().hour >= 13
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    val tomorrow = today.plusDays(1)

    val datePickerDialog = DatePickerDialog(
        context,
        { _: DatePicker, year: Int, month: Int, dayOfMonth: Int ->
            onDateSelected(LocalDate.of(year, month + 1, dayOfMonth))
        },
        selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth
    )

    val selectedButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { onDateSelected(yesterday) },
            colors = if (selectedDate == yesterday) selectedButtonColors else ButtonDefaults.buttonColors()
        ) { Text("I går") }
        Button(
            onClick = { onDateSelected(today) },
            colors = if (selectedDate == today) selectedButtonColors else ButtonDefaults.buttonColors()
        ) { Text("I dag") }
        Button(
            onClick = { onDateSelected(tomorrow) },
            enabled = isTomorrowAvailable,
            colors = if (selectedDate == tomorrow) selectedButtonColors else ButtonDefaults.buttonColors()
        ) { Text("I morgen") }
        Button(onClick = { datePickerDialog.show() }) { Text("Dato") }
    }
}

@Composable
fun PriceChart(prices: List<PricePoint>, zone: String, selectedDate: LocalDate, modifier: Modifier = Modifier) {
    val pricesWithVat = remember(prices, zone) {
        prices.map {
            val price = if (zone != "NO4") it.NOK_per_kWh * 1.25 else it.NOK_per_kWh
            it to price * 100 // Pair the original PricePoint with the calculated øre price
        }
    }

    val maxPriceAbs = remember(pricesWithVat) {
        pricesWithVat.maxOfOrNull { abs(it.second) } ?: 1.0
    }
    val minPrice = remember(pricesWithVat) { pricesWithVat.filter { it.second >= 0 }.minOfOrNull { it.second } ?: 0.0 }
    val maxPrice = remember(pricesWithVat) { pricesWithVat.maxOfOrNull { it.second } ?: 1.0 }


    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd. MMMM yy", Locale.forLanguageTag("no-NO")) }
    val dateText = selectedDate.format(dateFormatter)

    val headerText = if (zone != "NO4") {
        "Priser i øre/kWh inkl. mva for $dateText"
    } else {
        "Priser i øre/kWh for $dateText"
    }

    val currentHour = LocalTime.now().hour

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(text = headerText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val barHeight = maxHeight / 24
            LazyColumn {
                items(pricesWithVat) { (pricePoint, priceInOre) ->
                    ChartBar(
                        pricePoint = pricePoint,
                        priceInOre = priceInOre,
                        maxPriceAbs = maxPriceAbs,
                        minPrice = minPrice,
                        maxPrice = maxPrice,
                        selectedDate = selectedDate,
                        currentHour = currentHour,
                        modifier = Modifier.height(barHeight)
                    )
                }
            }
        }
    }
}

@Composable
fun ChartBar(
    pricePoint: PricePoint,
    priceInOre: Double,
    maxPriceAbs: Double,
    minPrice: Double,
    maxPrice: Double,
    selectedDate: LocalDate,
    currentHour: Int,
    modifier: Modifier = Modifier
) {
    val barFraction = if (priceInOre >= 0) {
        val priceRange = maxPrice - minPrice
        val minBarWidth = 0.12f
        val maxBarWidth = 1.0f
        val widthRange = maxBarWidth - minBarWidth

        if (priceRange > 0) {
            val priceFraction = ((priceInOre - minPrice) / priceRange).toFloat()
            (minBarWidth + (priceFraction * widthRange)).coerceIn(minBarWidth, maxBarWidth)
        } else {
            maxBarWidth // All positive prices are the same, make them full width
        }
    } else {
        (abs(priceInOre) / maxPriceAbs).toFloat()
    }

    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH") }
    val hour = remember(pricePoint.time_start) {
        timeFormatter.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(pricePoint.time_start))
    }

    val colorFraction = if (priceInOre >= 0) {
        val range = maxPrice - minPrice
        if (range > 0) {
            ((priceInOre - minPrice) / range).toFloat().coerceIn(0f, 1f)
        } else {
            0f // All positive prices are the same, default to green
        }
    } else {
        -1f // Sentinel for negative
    }

    val barColor = if (colorFraction < 0) {
        Color.Black
    } else {
        Color(
            red = colorFraction,
            green = 1 - colorFraction,
            blue = 0f
        )
    }

    val textColor = if (colorFraction < 0) { // Negative price, black background
        Color.White
    } else {
        // Luminance calculation: (0.299*R + 0.587*G + 0.114*B)
        val luminance = 0.299 * colorFraction + 0.587 * (1 - colorFraction)
        if (luminance > 0.5) Color.Black else Color.White
    }

    val priceText = "%.2f".format(priceInOre)

    val isCurrentHour = remember(hour, currentHour, selectedDate) {
        hour.toIntOrNull() == currentHour && selectedDate.isEqual(LocalDate.now())
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = hour,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(24.dp),
            color = if (isCurrentHour) Color.Red else Color.Unspecified,
            fontWeight = if (isCurrentHour) FontWeight.Bold else null
        )

        Box(
            modifier = Modifier
                .fillMaxWidth(barFraction)
                .fillMaxHeight()
                .background(barColor)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = priceText,
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
        }
    }
}
