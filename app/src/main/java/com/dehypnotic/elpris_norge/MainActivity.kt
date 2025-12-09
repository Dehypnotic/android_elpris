package com.dehypnotic.elpris_norge

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.widget.DatePicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.dehypnotic.elpris_norge.network.PricePoint
import com.dehypnotic.elpris_norge.network.fetchPrices
import com.dehypnotic.elpris_norge.ui.theme.Android_elprisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private const val NORGESPRIS_MIDPOINT_ORE = 50.0
private const val STROMSTOTTE_THRESHOLD_EX_VAT_ORE = 75.0
private const val STROMSTOTTE_VAT_MULTIPLIER = 1.25
private const val STROMSTOTTE_THRESHOLD_INCL_VAT_ORE = STROMSTOTTE_THRESHOLD_EX_VAT_ORE * STROMSTOTTE_VAT_MULTIPLIER
private const val STROMSTOTTE_SUBSIDY_PERCENTAGE = 0.90

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Android_elprisTheme {
                PriceScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun PriceScreen(modifier: Modifier = Modifier) {
    var prices by remember { mutableStateOf<List<PricePoint>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isNorgespris by remember { mutableStateOf(false) }
    var isStromstotte by remember { mutableStateOf(false) }

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

    Scaffold(
        modifier = modifier,
        bottomBar = {
            BottomBar(
                isNorgespris = isNorgespris,
                isStromstotte = isStromstotte,
                onNorgesprisChange = {
                    isNorgespris = it
                    if (it) isStromstotte = false
                },
                onStromstotteChange = {
                    isStromstotte = it
                    if (it) isNorgespris = false
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            ZoneSelector(
                selectedZone = selectedZone,
                onZoneSelected = { newZone ->
                    selectedZone = newZone
                    sharedPrefs.edit { putString("selected_zone", newZone) }
                },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            DateSelector(
                selectedDate = selectedDate,
                onDateSelected = { selectedDate = it },
                modifier = Modifier.padding(horizontal = 8.dp)
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
                        PriceChart(
                            prices = prices,
                            zone = selectedZone,
                            selectedDate = selectedDate,
                            isNorgespris = isNorgespris,
                            isStromstotte = isStromstotte
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BottomBar(
    isNorgespris: Boolean,
    isStromstotte: Boolean,
    onNorgesprisChange: (Boolean) -> Unit,
    onStromstotteChange: (Boolean) -> Unit
) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Norgespris")
                Spacer(Modifier.width(8.dp))
                Switch(checked = isNorgespris, onCheckedChange = onNorgesprisChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Strømstøtte")
                Spacer(Modifier.width(8.dp))
                Switch(checked = isStromstotte, onCheckedChange = onStromstotteChange)
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
fun PriceChart(
    prices: List<PricePoint>,
    zone: String,
    selectedDate: LocalDate,
    isNorgespris: Boolean,
    isStromstotte: Boolean,
    modifier: Modifier = Modifier
) {
    val pricesWithVat = remember(prices, zone, isStromstotte) {
        prices.map { pricePoint ->
            val priceExVat = pricePoint.NOK_per_kWh
            val priceInclVat = if (zone != "NO4") priceExVat * STROMSTOTTE_VAT_MULTIPLIER else priceExVat

            val effectivePrice = if (isStromstotte) {
                val threshold = if (zone != "NO4") STROMSTOTTE_THRESHOLD_INCL_VAT_ORE else STROMSTOTTE_THRESHOLD_EX_VAT_ORE
                val priceInOre = priceInclVat * 100
                if (priceInOre > threshold) {
                    val subsidizedAmount = (priceInOre - threshold) * STROMSTOTTE_SUBSIDY_PERCENTAGE
                    (priceInOre - subsidizedAmount)
                } else {
                    priceInOre
                }
            } else {
                priceInclVat * 100
            }
            pricePoint to effectivePrice
        }
    }

    val maxPriceForScaling = remember(pricesWithVat, isNorgespris) {
        if (isNorgespris) {
            pricesWithVat.maxOfOrNull { max(abs(it.second - NORGESPRIS_MIDPOINT_ORE), NORGESPRIS_MIDPOINT_ORE) } ?: 1.0
        } else {
            pricesWithVat.maxOfOrNull { it.second } ?: 1.0
        }
    }
    val minPrice = remember(pricesWithVat) { pricesWithVat.filter { it.second >= 0 }.minOfOrNull { it.second } ?: 0.0 }
    val averagePrice = remember(pricesWithVat) { if (pricesWithVat.isNotEmpty()) pricesWithVat.map { it.second }.average() else 0.0 }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd. MMMM yy", Locale.forLanguageTag("no-NO")) }
    val dateText = selectedDate.format(dateFormatter)

    val averagePriceText = String.format(Locale.forLanguageTag("no-NO"), "%.2f", averagePrice)
    val headerText = when {
        isStromstotte -> "Din pris etter strømstøtte for $dateText. Snitt: $averagePriceText"
        isNorgespris -> "Priser for $dateText. Snitt: $averagePriceText"
        zone != "NO4" -> "Priser i øre/kWh inkl. mva for $dateText. Snitt: $averagePriceText"
        else -> "Priser i øre/kWh for $dateText. Snitt: $averagePriceText"
    }

    val currentHour = LocalTime.now().hour
    val density = LocalDensity.current

    Column(modifier = modifier.padding(horizontal = 16.dp).fillMaxSize()) {
        Text(text = headerText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val barHeight = maxHeight / 24
            LazyColumn {
                items(pricesWithVat) { (pricePoint, priceInOre) ->
                    ChartBar(
                        pricePoint = pricePoint,
                        priceInOre = priceInOre,
                        maxPriceForScaling = maxPriceForScaling,
                        minPrice = minPrice,
                        selectedDate = selectedDate,
                        currentHour = currentHour,
                        isNorgespris = isNorgespris,
                        isStromstotte = isStromstotte,
                        zone = zone,
                        modifier = Modifier.height(barHeight)
                    )
                }
            }
            if (isStromstotte) {
                val threshold = if (zone != "NO4") STROMSTOTTE_THRESHOLD_INCL_VAT_ORE else STROMSTOTTE_THRESHOLD_EX_VAT_ORE
                if (maxPriceForScaling > threshold) {
                    val thresholdX = (threshold / maxPriceForScaling).toFloat()
                    val lineX = with(density) { (maxWidth * thresholdX).toPx() }

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawLine(
                            color = Color.Black,
                            start = Offset(lineX, 0f),
                            end = Offset(lineX, size.height),
                            strokeWidth = 2f
                        )
                    }
                }
            }
             if (isNorgespris) {
                val lineX = with(density) { (maxWidth / 2).toPx() }
                 Canvas(modifier = Modifier.fillMaxSize()) {
                     drawLine(
                         color = Color.LightGray,
                         start = Offset(lineX, 0f),
                         end = Offset(lineX, size.height),
                         strokeWidth = 1.dp.toPx()
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
    maxPriceForScaling: Double,
    minPrice: Double,
    selectedDate: LocalDate,
    currentHour: Int,
    isNorgespris: Boolean,
    isStromstotte: Boolean,
    zone: String,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH") }
    val hour = remember(pricePoint.time_start) {
        timeFormatter.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(pricePoint.time_start))
    }
    val priceText = String.format(Locale.forLanguageTag("no-NO"), "%.2f", priceInOre)
    val isCurrentHour = remember(hour, currentHour, selectedDate) {
        hour.toIntOrNull() == currentHour && selectedDate.isEqual(LocalDate.now())
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = hour,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(24.dp),
            color = if (isCurrentHour) Color.Red else Color.Unspecified,
            fontWeight = if (isCurrentHour) FontWeight.Bold else null
        )

        if (isNorgespris) {
            NorgesprisBar(priceInOre = priceInOre, maxPriceForScaling = maxPriceForScaling, priceText = priceText)
        } else {
            DefaultBar(priceInOre = priceInOre, maxPriceForScaling = maxPriceForScaling, minPrice = minPrice, priceText = priceText, isStromstotte = isStromstotte)
        }
    }
}

@Composable
fun RowScope.NorgesprisBar(priceInOre: Double, maxPriceForScaling: Double, priceText: String) {
    val diff = priceInOre - NORGESPRIS_MIDPOINT_ORE
    // The fraction should be based on how much it deviates from the midpoint, relative to the max possible deviation
    val barFraction = (abs(diff) / maxPriceForScaling).toFloat()

    // Color graduation: full red at 0, midpoint color at 50, full green at 100+
    val colorFraction = ((priceInOre - NORGESPRIS_MIDPOINT_ORE) / (maxPriceForScaling - NORGESPRIS_MIDPOINT_ORE)).toFloat().coerceIn(-1f, 1f)

    val barColor = if (colorFraction < 0) {
        Color(red = 1f, green = 1f - abs(colorFraction), blue = 0f) // More red as it approaches 0
    } else {
        Color(red = 1f - colorFraction, green = 1f, blue = 0f) // More green as it gets higher
    }

    val luminance = 0.299 * barColor.red + 0.587 * barColor.green + 0.114 * barColor.blue
    val textColor = if (luminance > 0.6) Color.Black else Color.White

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val totalWidth = maxWidth
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (diff < 0) {
                // Bar grows from right to left
                Spacer(modifier = Modifier.weight(0.5f - barFraction/2))
                Box(
                    modifier = Modifier
                        .weight(barFraction/2)
                        .fillMaxHeight()
                        .background(barColor)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                     Text(text = priceText, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = textColor)
                }
                Spacer(modifier = Modifier.weight(0.5f))
            } else {
                // Bar grows from left to right
                Spacer(modifier = Modifier.weight(0.5f))
                Box(
                    modifier = Modifier
                        .weight(barFraction/2)
                        .fillMaxHeight()
                        .background(barColor)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(text = priceText, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = textColor)
                }
                 Spacer(modifier = Modifier.weight(0.5f - barFraction/2))
            }
        }
    }
}


@Composable
fun RowScope.DefaultBar(priceInOre: Double, maxPriceForScaling: Double, minPrice: Double, priceText: String, isStromstotte: Boolean) {
    val barFraction = if (priceInOre >= 0) {
        if (maxPriceForScaling > 0) (priceInOre / maxPriceForScaling).toFloat() else 0f
    } else {
        if (maxPriceForScaling > 0) (abs(priceInOre) / maxPriceForScaling).toFloat() else 0f
    }

    val colorFraction = if (priceInOre >= 0) {
        val range = maxPriceForScaling - minPrice
        if (range > 0) ((priceInOre - minPrice) / range).toFloat().coerceIn(0f, 1f) else 0f
    } else {
        -1f // Sentinel for negative
    }

    val barColor = when {
        colorFraction < 0 -> Color.Black
        isStromstotte -> Color(0xFF6495ED) // CornflowerBlue
        else -> Color(red = colorFraction, green = 1 - colorFraction, blue = 0f)
    }

    val luminance = 0.299 * barColor.red + 0.587 * barColor.green + 0.114 * barColor.blue
    val textColor = if (luminance > 0.5) Color.Black else Color.White


    Box(
        modifier = Modifier
            .fillMaxWidth(barFraction.coerceIn(0f, 1f))
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
