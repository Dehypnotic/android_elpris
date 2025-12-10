package com.dehypnotic.elpris_norge

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.widget.DatePicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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
    private val refreshTrigger = mutableStateOf(0)
    private var lastPauseTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Android_elprisTheme {
                PriceScreen(
                    modifier = Modifier.fillMaxSize(),
                    refreshTrigger = refreshTrigger.value
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        lastPauseTime = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        if (lastPauseTime == 0L) { // App is starting, no need to refresh.
            return
        }

        val currentTime = System.currentTimeMillis()
        val hourInMillis = 60 * 60 * 1000

        val lastCal = java.util.Calendar.getInstance().apply { timeInMillis = lastPauseTime }
        val currentCal = java.util.Calendar.getInstance().apply { timeInMillis = currentTime }

        if (currentTime - lastPauseTime > hourInMillis || lastCal.get(java.util.Calendar.HOUR_OF_DAY) != currentCal.get(java.util.Calendar.HOUR_OF_DAY)) {
            refreshTrigger.value++
        }
    }
}

@Composable
fun PriceScreen(modifier: Modifier = Modifier, refreshTrigger: Int) {
    var prices by remember { mutableStateOf<List<PricePoint>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    var isNorgespris by remember {
        mutableStateOf(sharedPrefs.getBoolean("is_norgespris", false))
    }
    var isStromstotte by remember {
        mutableStateOf(sharedPrefs.getBoolean("is_stromstotte", false))
    }

    var selectedZone by remember {
        mutableStateOf(sharedPrefs.getString("selected_zone", "NO3") ?: "NO3")
    }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    LaunchedEffect(selectedZone, selectedDate, refreshTrigger) {
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
                    sharedPrefs.edit {
                        putBoolean("is_norgespris", isNorgespris)
                        putBoolean("is_stromstotte", isStromstotte)
                    }
                },
                onStromstotteChange = {
                    isStromstotte = it
                    if (it) isNorgespris = false
                    sharedPrefs.edit {
                        putBoolean("is_stromstotte", isStromstotte)
                        putBoolean("is_norgespris", isNorgespris)
                    }
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Norgespris", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Switch(checked = isNorgespris, onCheckedChange = onNorgesprisChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Strømstøtte", color = MaterialTheme.colorScheme.onPrimaryContainer)
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
    val pricesInfo = remember(prices, zone, isStromstotte) {
        prices.map { pricePoint ->
            val priceExVat = pricePoint.NOK_per_kWh
            val originalPriceInclVat = if (zone != "NO4") priceExVat * STROMSTOTTE_VAT_MULTIPLIER else priceExVat
            val originalPriceInOre = originalPriceInclVat * 100

            val effectivePrice = if (isStromstotte) {
                val threshold = if (zone != "NO4") STROMSTOTTE_THRESHOLD_INCL_VAT_ORE else STROMSTOTTE_THRESHOLD_EX_VAT_ORE
                if (originalPriceInOre > threshold) {
                    val subsidizedAmount = (originalPriceInOre - threshold) * STROMSTOTTE_SUBSIDY_PERCENTAGE
                    (originalPriceInOre - subsidizedAmount)
                } else {
                    originalPriceInOre
                }
            } else {
                originalPriceInOre
            }
            PriceInfo(pricePoint, effectivePrice, originalPriceInOre)
        }
    }

    val maxAbsoluteDeviation = remember(pricesInfo, isNorgespris) {
        if (isNorgespris) {
            pricesInfo
                .map { abs(it.originalPrice - NORGESPRIS_MIDPOINT_ORE) }
                .maxOfOrNull { it } ?: 1.0
        } else {
            1.0 // Default value, not used when not in Norgespris mode
        }
    }

    val maxPriceForScaling = remember(pricesInfo) { pricesInfo.maxOfOrNull { it.originalPrice } ?: 1.0 }
    val minOriginalPrice = remember(pricesInfo) { pricesInfo.filter { it.originalPrice >= 0 }.minOfOrNull { it.originalPrice } ?: 0.0 }
    val minPrice = remember(pricesInfo) { pricesInfo.filter { it.effectivePrice >= 0 }.minOfOrNull { it.effectivePrice } ?: 0.0 }
    val maxEffectivePrice = remember(pricesInfo) { pricesInfo.maxOfOrNull { it.effectivePrice } ?: 1.0 }
    val averagePrice = remember(pricesInfo) { if (pricesInfo.isNotEmpty()) pricesInfo.map { it.effectivePrice }.average() else 0.0 }

    val (percentBelow, percentAbove) = remember(pricesInfo, isNorgespris) {
        if (isNorgespris && pricesInfo.isNotEmpty()) {
            val sumBelow = pricesInfo
                .map { NORGESPRIS_MIDPOINT_ORE - it.originalPrice }
                .filter { it > 0 }
                .sum()

            val sumAbove = pricesInfo
                .map { it.originalPrice - NORGESPRIS_MIDPOINT_ORE }
                .filter { it > 0 }
                .sum()

            val totalDeviation = sumBelow + sumAbove
            if (totalDeviation > 0) {
                val below = (sumBelow / totalDeviation * 100).toInt()
                val above = 100 - below
                Pair(below, above)
            } else {
                Pair(0, 0)
            }
        } else {
            Pair(0, 0)
        }
    }

    val stromstotteThreshold = if (zone != "NO4") STROMSTOTTE_THRESHOLD_INCL_VAT_ORE else STROMSTOTTE_THRESHOLD_EX_VAT_ORE

    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd. MMMM yy", Locale.forLanguageTag("no-NO")) }
    val dateText = selectedDate.format(dateFormatter)

    val averagePriceText = String.format(Locale.forLanguageTag("no-NO"), "%.2f", averagePrice)
    val headerText = when {
        isStromstotte -> "Din pris etter strømstøtte for $dateText. Snitt: $averagePriceText"
        isNorgespris -> "Prisavvik fra Norgespris for $dateText. Under: $percentBelow%, Over: $percentAbove%"
        zone != "NO4" -> "Priser i øre/kWh inkl. mva for $dateText. Snitt: $averagePriceText"
        else -> "Priser i øre/kWh for $dateText. Snitt: $averagePriceText"
    }

    val currentHour = LocalTime.now().hour

    Column(modifier = modifier.fillMaxSize()) {
        AutoResizeText(
            text = headerText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(pricesInfo) { priceInfo ->
                ChartBar(
                    priceInfo = priceInfo,
                    maxPriceForScaling = maxPriceForScaling,
                    minOriginalPrice = minOriginalPrice,
                    minPrice = minPrice,
                    maxEffectivePrice = maxEffectivePrice,
                    stromstotteThreshold = stromstotteThreshold,
                    selectedDate = selectedDate,
                    currentHour = currentHour,
                    isNorgespris = isNorgespris,
                    isStromstotte = isStromstotte,
                    maxAbsoluteDeviation = maxAbsoluteDeviation,
                    modifier = Modifier.fillParentMaxHeight(1f / 24f)
                )
            }
        }
    }
}

data class PriceInfo(val pricePoint: PricePoint, val effectivePrice: Double, val originalPrice: Double)

@Composable
fun ChartBar(
    priceInfo: PriceInfo,
    maxPriceForScaling: Double,
    minOriginalPrice: Double,
    minPrice: Double,
    maxEffectivePrice: Double,
    stromstotteThreshold: Double,
    selectedDate: LocalDate,
    currentHour: Int,
    isNorgespris: Boolean,
    isStromstotte: Boolean,
    maxAbsoluteDeviation: Double,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH") }
    val hour = remember(priceInfo.pricePoint.time_start) {
        timeFormatter.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(priceInfo.pricePoint.time_start))
    }
    val priceText = String.format(Locale.forLanguageTag("no-NO"), "%.2f", priceInfo.effectivePrice)
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

        DefaultBar(
            priceInfo = priceInfo,
            maxPriceForScaling = maxPriceForScaling,
            minOriginalPrice = minOriginalPrice,
            minPrice = minPrice,
            maxEffectivePrice = maxEffectivePrice,
            stromstotteThreshold = stromstotteThreshold,
            priceText = priceText,
            isNorgespris = isNorgespris,
            isStromstotte = isStromstotte,
            maxAbsoluteDeviation = maxAbsoluteDeviation
        )
    }
}

@Composable
fun DefaultBar(
    priceInfo: PriceInfo,
    maxPriceForScaling: Double,
    minOriginalPrice: Double,
    minPrice: Double,
    maxEffectivePrice: Double,
    stromstotteThreshold: Double,
    priceText: String,
    isNorgespris: Boolean,
    isStromstotte: Boolean,
    maxAbsoluteDeviation: Double
) {
    if (isNorgespris) {
        val priceBelowMidpointValue = max(0.0, NORGESPRIS_MIDPOINT_ORE - priceInfo.originalPrice)
        val priceAboveMidpointValue = max(0.0, priceInfo.originalPrice - NORGESPRIS_MIDPOINT_ORE)

        val belowFraction = if (maxAbsoluteDeviation > 0) (priceBelowMidpointValue / maxAbsoluteDeviation).toFloat() else 0f
        val aboveFraction = if (maxAbsoluteDeviation > 0) (priceAboveMidpointValue / maxAbsoluteDeviation).toFloat() else 0f

        val colorRange = max(1.0, maxPriceForScaling) - minOriginalPrice
        val colorFraction = if (colorRange > 0) {
            ((priceInfo.originalPrice - minOriginalPrice) / colorRange).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        val baseBarColor = if (priceInfo.originalPrice < 0) Color.Black else Color(red = colorFraction, green = 1 - colorFraction, blue = 0f)
        val darkerBarColor = baseBarColor.copy(red = baseBarColor.red * 0.8f, green = baseBarColor.green * 0.8f, blue = baseBarColor.blue * 0.8f)
        val luminance = 0.299 * baseBarColor.red + 0.587 * baseBarColor.green + 0.114 * baseBarColor.blue
        val textColor = if (luminance > 0.5) Color.Black else Color.White

        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // Left bar (below midpoint)
            Box(modifier = Modifier.weight(0.5f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(belowFraction)
                        .background(baseBarColor),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    val textBelow = String.format(Locale.forLanguageTag("no-NO"), "%.0f", priceBelowMidpointValue)
                    if (priceBelowMidpointValue > 0) {
                        Text(
                            text = textBelow,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                            modifier = Modifier.padding(end = 4.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }

            // Center divider
            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)))

            // Right bar (above midpoint)
            Box(modifier = Modifier.weight(0.5f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(aboveFraction)
                        .background(darkerBarColor),
                    contentAlignment = Alignment.CenterStart
                ) {
                    val textAbove = String.format(Locale.forLanguageTag("no-NO"), "%.0f", priceAboveMidpointValue)
                    if (priceAboveMidpointValue > 0) {
                        Text(
                            text = textAbove,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    } else {
        // Original logic for default and stromstotte views
        val priceInOre = priceInfo.effectivePrice
        val originalPrice = priceInfo.originalPrice
        val colorRange = maxEffectivePrice - minPrice
        val colorFraction = if (colorRange > 0) {
            ((priceInOre - minPrice) / colorRange).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

        val baseBarColor = if (priceInOre < 0) Color.Black else Color(red = colorFraction, green = 1 - colorFraction, blue = 0f)
        val darkerBarColor = baseBarColor.copy(red = baseBarColor.red * 0.8f, green = baseBarColor.green * 0.8f, blue = baseBarColor.blue * 0.8f)
        val luminance = 0.299 * baseBarColor.red + 0.587 * baseBarColor.green + 0.114 * baseBarColor.blue
        val textColor = if (luminance > 0.5) Color.Black else Color.White
        val minBarUiFraction = 0.14f

        val fullBarFraction = when {
            isStromstotte -> {
                val range = maxEffectivePrice - minPrice
                val fraction = if (range > 0) {
                    val scaledFraction = ((priceInOre - minPrice) / range).toFloat()
                    minBarUiFraction + (1f - minBarUiFraction) * scaledFraction
                } else {
                    if (priceInOre > 0) 0.5f else 0f
                }
                fraction.coerceIn(0f, 1f)
            }
            else -> { // Default view
                val maxOriginalPrice = maxPriceForScaling
                val range = maxOriginalPrice - minOriginalPrice
                val fraction = if (range > 0) {
                    val scaledFraction = ((originalPrice - minOriginalPrice) / range).toFloat()
                    minBarUiFraction + (1f - minBarUiFraction) * scaledFraction
                } else {
                    if (originalPrice > 0) 0.5f else 0f
                }
                fraction.coerceIn(0f, 1f)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fullBarFraction),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (isStromstotte && priceInOre > stromstotteThreshold) {
                val range = maxEffectivePrice - minPrice
                val thresholdBarFraction = if (range > 0) {
                    val scaledFraction = ((stromstotteThreshold - minPrice) / range).toFloat()
                    (minBarUiFraction + (1f - minBarUiFraction) * scaledFraction).coerceIn(0f, 1f)
                } else {
                    if (stromstotteThreshold > 0) 0.5f else 0f
                }

                val baseProportion = if (fullBarFraction > 0f) (thresholdBarFraction / fullBarFraction) else 0f

                if (baseProportion < 1f) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(baseProportion).background(baseBarColor))
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth().background(darkerBarColor))
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(baseBarColor))
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(baseBarColor))
            }
            }

            Text(
                text = priceText,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                modifier = Modifier.padding(start = 4.dp).align(Alignment.CenterStart)
            )
        }
    }
}

@Composable
fun AutoResizeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null
) {
    var resizedTextStyle by remember(text, style) { mutableStateOf(style) }
    var shouldDraw by remember { mutableStateOf(false) }

    Text(
        text = text,
        modifier = modifier.drawWithContent {
            if (shouldDraw) {
                drawContent()
            }
        },
        style = resizedTextStyle,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        onTextLayout = { result ->
            if (result.hasVisualOverflow) {
                if (resizedTextStyle.fontSize != TextUnit.Unspecified) {
                    resizedTextStyle = resizedTextStyle.copy(
                        fontSize = resizedTextStyle.fontSize * 0.95f
                    )
                }
            } else {
                shouldDraw = true
            }
        }
    )
}

