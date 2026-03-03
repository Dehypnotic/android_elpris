package com.dehypnotic.elpris_norge

import android.app.DatePickerDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.DatePicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.dehypnotic.elpris_norge.network.PricePoint
import com.dehypnotic.elpris_norge.network.fetchPrices
import com.dehypnotic.elpris_norge.ui.theme.Android_elprisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

private const val STROMSTOTTE_THRESHOLD_EX_VAT_ORE = 75.0
private const val STROMSTOTTE_VAT_MULTIPLIER = 1.25
private const val NORGESPRIS_MIDPOINT_INCL_VAT_ORE = 50.0
private const val NORGESPRIS_MIDPOINT_EX_VAT_ORE = NORGESPRIS_MIDPOINT_INCL_VAT_ORE / STROMSTOTTE_VAT_MULTIPLIER
private const val STROMSTOTTE_THRESHOLD_INCL_VAT_ORE = STROMSTOTTE_THRESHOLD_EX_VAT_ORE * STROMSTOTTE_VAT_MULTIPLIER
private const val STROMSTOTTE_SUBSIDY_PERCENTAGE = 0.90

data class PriceInfo(val pricePoint: PricePoint, val effectivePrice: Double, val originalPrice: Double)

class MainActivity : ComponentActivity() {
    private val refreshTrigger = mutableIntStateOf(0)
    private var lastPauseTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bruk versjonssjekk for å unngå avviklede API-er for heldekkende skjerm på Android 15+
        if (Build.VERSION.SDK_INT < 35) {
            enableEdgeToEdge()
        }
        setContent {
            Android_elprisTheme {
                PriceScreen(
                    modifier = Modifier.fillMaxSize(),
                    refreshTrigger = refreshTrigger.intValue
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
            refreshTrigger.intValue++
        }
    }
}

@Composable
fun PriceScreen(modifier: Modifier = Modifier, refreshTrigger: Int) {
    var prices by remember { mutableStateOf<List<PricePoint>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentTime by remember { mutableStateOf(LocalTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L) // 1 minute
            currentTime = LocalTime.now()
        }
    }

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
    var isMva by remember {
        mutableStateOf(sharedPrefs.getBoolean("is_mva", selectedZone != "NO4"))
    }

    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    // Logic for MVA toggle state
    LaunchedEffect(isNorgespris, isStromstotte, selectedZone) {
        if (selectedZone == "NO4") {
            isMva = false
        } else if (isNorgespris || isStromstotte) {
            isMva = true
        }
        // Save state
        sharedPrefs.edit {
            putBoolean("is_mva", isMva)
        }
    }

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
                isMva = isMva,
                selectedZone = selectedZone,
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
                },
                onMvaChange = {
                    isMva = it
                    sharedPrefs.edit {
                        putBoolean("is_mva", isMva)
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
                currentTime = currentTime,
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
                            selectedDate = selectedDate,
                            isNorgespris = isNorgespris,
                            isStromstotte = isStromstotte,
                            isMva = isMva,
                            currentTime = currentTime
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
    isMva: Boolean,
    selectedZone: String,
    onNorgesprisChange: (Boolean) -> Unit,
    onStromstotteChange: (Boolean) -> Unit,
    onMvaChange: (Boolean) -> Unit
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
                Spacer(Modifier.width(4.dp))
                Switch(checked = isNorgespris, onCheckedChange = onNorgesprisChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Støtte", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(4.dp))
                Switch(checked = isStromstotte, onCheckedChange = onStromstotteChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mva", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(4.dp))
                Switch(
                    checked = isMva,
                    onCheckedChange = onMvaChange,
                    enabled = !isNorgespris && !isStromstotte && selectedZone != "NO4"
                )
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
fun DateSelector(selectedDate: LocalDate, onDateSelected: (LocalDate) -> Unit, currentTime: LocalTime, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isTomorrowAvailable = currentTime.hour >= 13
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
    selectedDate: LocalDate,
    isNorgespris: Boolean,
    isStromstotte: Boolean,
    isMva: Boolean,
    currentTime: LocalTime,
    modifier: Modifier = Modifier
) {
    val effectiveMidpoint = if (isMva) NORGESPRIS_MIDPOINT_INCL_VAT_ORE else NORGESPRIS_MIDPOINT_EX_VAT_ORE

    val pricesInfo = remember(prices, isMva, isStromstotte) {
        prices.map { pricePoint ->
            val priceExVat = pricePoint.NOK_per_kWh
            val originalPrice = if (isMva) priceExVat * STROMSTOTTE_VAT_MULTIPLIER else priceExVat
            val originalPriceInOre = originalPrice * 100

            val effectivePrice = if (isStromstotte) {
                val threshold = if (isMva) STROMSTOTTE_THRESHOLD_INCL_VAT_ORE else STROMSTOTTE_THRESHOLD_EX_VAT_ORE
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

    val maxAbsoluteDeviation = remember(pricesInfo, isNorgespris, effectiveMidpoint) {
        if (isNorgespris) {
            pricesInfo
                .map { abs(it.originalPrice - effectiveMidpoint) }
                .maxOfOrNull { it } ?: 1.0
        } else {
            1.0
        }
    }

    val maxPriceForScaling = remember(pricesInfo) { pricesInfo.maxOfOrNull { it.originalPrice } ?: 1.0 }
    val minOriginalPrice = remember(pricesInfo) { pricesInfo.filter { it.originalPrice >= 0 }.minOfOrNull { it.originalPrice } ?: 0.0 }
    val minPrice = remember(pricesInfo) { pricesInfo.filter { it.effectivePrice >= 0 }.minOfOrNull { it.effectivePrice } ?: 0.0 }
    val maxEffectivePrice = remember(pricesInfo) { pricesInfo.maxOfOrNull { it.effectivePrice } ?: 1.0 }
    val averagePrice = remember(pricesInfo) { if (pricesInfo.isNotEmpty()) pricesInfo.map { it.effectivePrice }.average() else 0.0 }

    val (sumBelow, sumAbove) = remember(pricesInfo, isNorgespris, effectiveMidpoint) {
        if (isNorgespris && pricesInfo.isNotEmpty()) {
            val below = pricesInfo.map { effectiveMidpoint - it.originalPrice }.filter { it > 0 }.sum()
            val above = pricesInfo.map { it.originalPrice - effectiveMidpoint }.filter { it > 0 }.sum()
            Pair(below, above)
        } else Pair(0.0, 0.0)
    }

    val stromstotteThreshold = if (isMva) STROMSTOTTE_THRESHOLD_INCL_VAT_ORE else STROMSTOTTE_THRESHOLD_EX_VAT_ORE
    val dateText = selectedDate.format(remember { DateTimeFormatter.ofPattern("dd. MMMM yy", Locale.forLanguageTag("no-NO")) })
    val averagePriceText = String.format(Locale.forLanguageTag("no-NO"), "%.2f", averagePrice)
    val headerText = when {
        isStromstotte -> "Din pris etter strømstøtte for $dateText. Snitt: $averagePriceText"
        isNorgespris -> {
            val belowText = String.format(Locale.forLanguageTag("no-NO"), "%.0f", sumBelow)
            val aboveText = String.format(Locale.forLanguageTag("no-NO"), "%.0f", sumAbove)
            "Prisavvik fra Norgespris for $dateText. Under: $belowText øre, Over: $aboveText øre"
        }
        isMva -> "Priser i øre/kWh inkl. mva for $dateText. Snitt: $averagePriceText"
        else -> "Priser i øre/kWh eks. mva for $dateText. Snitt: $averagePriceText"
    }

    val overallMin = remember(pricesInfo, isNorgespris, effectiveMidpoint, isStromstotte, minPrice, minOriginalPrice) {
        if (isNorgespris) min(pricesInfo.minOfOrNull { it.originalPrice } ?: 0.0, effectiveMidpoint)
        else if (isStromstotte) minPrice else minOriginalPrice
    }
    val overallMax = remember(pricesInfo, isNorgespris, effectiveMidpoint, isStromstotte, maxEffectivePrice, maxPriceForScaling) {
        if (isNorgespris) max(pricesInfo.maxOfOrNull { it.originalPrice } ?: 0.0, effectiveMidpoint)
        else if (isStromstotte) maxEffectivePrice else maxPriceForScaling
    }
    val chartRange = overallMax - overallMin

    val marks = remember(overallMin, overallMax, chartRange) {
        val list = mutableListOf<Int>()
        if (chartRange <= 0) return@remember list
        val step = when { chartRange < 50 -> 10; chartRange < 125 -> 25; else -> 50 }
        var currentMark = (overallMin / step).toInt() * step
        if (currentMark < overallMin) currentMark += step
        while (currentMark <= overallMax) { list.add(currentMark); currentMark += step }
        list.take(5)
    }

    val isDarkTheme = isSystemInDarkTheme()
    val axisColor = if (isDarkTheme) Color.White else Color.Black

    val minBarUiFraction = 0.14f
    fun getXFraction(value: Double): Float {
        return if (isNorgespris) {
            if (maxAbsoluteDeviation > 0) 0.5f + ((value - effectiveMidpoint) / (2 * maxAbsoluteDeviation)).toFloat() else 0.5f
        } else {
            if (chartRange > 0) minBarUiFraction + (1f - minBarUiFraction) * ((value - overallMin) / chartRange).toFloat() else 0.5f
        }
    }

    val density = LocalDensity.current
    val labelPadding = 24.dp
    val labelPaddingPx = with(density) { labelPadding.toPx() }

    Column(modifier = modifier.fillMaxSize()) {
        AutoResizeText(text = headerText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        BoxWithConstraints(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            val constraintsScope = this
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Axis and price marks
                Box(modifier = Modifier.fillMaxWidth().height(25.dp)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawLine(color = axisColor, start = androidx.compose.ui.geometry.Offset(labelPaddingPx, size.height), end = androidx.compose.ui.geometry.Offset(size.width, size.height), strokeWidth = 2.dp.toPx())
                        marks.forEach { mark ->
                            val x = labelPaddingPx + (size.width - labelPaddingPx) * getXFraction(mark.toDouble())
                            if (x in labelPaddingPx..size.width) {
                                drawLine(color = axisColor, start = androidx.compose.ui.geometry.Offset(x, size.height), end = androidx.compose.ui.geometry.Offset(x, size.height - 4.dp.toPx()), strokeWidth = 2.dp.toPx())
                            }
                        }
                    }
                    marks.forEach { mark ->
                        val xPos = labelPadding + (constraintsScope.maxWidth - labelPadding) * getXFraction(mark.toDouble())
                        Text(text = mark.toString(), style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), modifier = Modifier.align(Alignment.BottomStart).offset(x = xPos - 12.dp, y = (-5).dp), textAlign = TextAlign.Center, color = axisColor)
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Grid lines
                        marks.forEach { mark ->
                            val x = labelPaddingPx + (size.width - labelPaddingPx) * getXFraction(mark.toDouble())
                            if (x in labelPaddingPx..size.width) {
                                drawLine(color = Color.Gray.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
                            }
                        }
                        // Red lines
                        val specialLineValues = if (isNorgespris) listOf(effectiveMidpoint) else listOf(stromstotteThreshold, effectiveMidpoint)
                        specialLineValues.forEach { lineVal ->
                            val x = labelPaddingPx + (size.width - labelPaddingPx) * getXFraction(lineVal)
                            if (x in labelPaddingPx..size.width) {
                                drawLine(color = Color.Red.copy(alpha = 0.6f), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
                            }
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = pricesInfo) { priceInfo ->
                            ChartBar(
                                priceInfo = priceInfo,
                                maxPriceForScaling = maxPriceForScaling,
                                minOriginalPrice = minOriginalPrice,
                                minPrice = minPrice,
                                maxEffectivePrice = maxEffectivePrice,
                                selectedDate = selectedDate,
                                currentHour = currentTime.hour,
                                isNorgespris = isNorgespris,
                                isStromstotte = isStromstotte,
                                maxAbsoluteDeviation = maxAbsoluteDeviation,
                                modifier = Modifier.fillParentMaxHeight(1f / 24f)
                            )
                        }
                    }
                }

                // Bottom line and special labels (Strømstøtte / Norgespris)
                Box(modifier = Modifier.fillMaxWidth().height(25.dp)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawLine(color = axisColor, start = androidx.compose.ui.geometry.Offset(labelPaddingPx, 0f), end = androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 2.dp.toPx())
                    }
                    val specialLines = if (isNorgespris) {
                        listOf(effectiveMidpoint to "Norgespris")
                    } else {
                        listOf(stromstotteThreshold to "Strømstøtte", effectiveMidpoint to "Norgespris")
                    }
                    specialLines.forEach { (lineVal, labelText) ->
                        val fraction = getXFraction(lineVal)
                        if (fraction in 0f..1f) {
                            val xPos = labelPadding + (constraintsScope.maxWidth - labelPadding) * fraction
                            val labelWidth = 65.dp
                            val isTooFarRight = xPos + labelWidth / 2 > constraintsScope.maxWidth
                            val labelTextAlign = if (isTooFarRight) TextAlign.End else TextAlign.Center
                            val offset = if (isTooFarRight) xPos - labelWidth else xPos - labelWidth / 2

                            Text(
                                text = labelText,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, color = Color.Red),
                                modifier = Modifier.align(Alignment.TopStart).width(labelWidth).offset(x = offset, y = 2.dp),
                                textAlign = labelTextAlign,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChartBar(
    priceInfo: PriceInfo,
    maxPriceForScaling: Double,
    minOriginalPrice: Double,
    minPrice: Double,
    maxEffectivePrice: Double,
    selectedDate: LocalDate,
    currentHour: Int,
    isNorgespris: Boolean,
    isStromstotte: Boolean,
    maxAbsoluteDeviation: Double,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH") }
    val hour = remember(priceInfo.pricePoint.time_start) { timeFormatter.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(priceInfo.pricePoint.time_start)) }
    val priceText = String.format(Locale.forLanguageTag("no-NO"), "%.2f", priceInfo.effectivePrice)
    val isCurrentHour = hour.toIntOrNull() == currentHour && selectedDate.isEqual(LocalDate.now())

    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = hour, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(24.dp), color = if (isCurrentHour) Color.Red else Color.Unspecified, fontWeight = if (isCurrentHour) FontWeight.Bold else null)
        DefaultBar(priceInfo = priceInfo, maxPriceForScaling = maxPriceForScaling, minOriginalPrice = minOriginalPrice, minPrice = minPrice, maxEffectivePrice = maxEffectivePrice, priceText = priceText, isNorgespris = isNorgespris, isStromstotte = isStromstotte, maxAbsoluteDeviation = maxAbsoluteDeviation)
    }
}

@Composable
fun DefaultBar(
    priceInfo: PriceInfo,
    maxPriceForScaling: Double,
    minOriginalPrice: Double,
    minPrice: Double,
    maxEffectivePrice: Double,
    priceText: String,
    isNorgespris: Boolean,
    isStromstotte: Boolean,
    maxAbsoluteDeviation: Double
) {
    if (isNorgespris) {
        val isMva = priceInfo.originalPrice > priceInfo.pricePoint.NOK_per_kWh * 100.1
        val effectiveMidpoint = if (isMva) NORGESPRIS_MIDPOINT_INCL_VAT_ORE else NORGESPRIS_MIDPOINT_EX_VAT_ORE
        val priceBelowMidpointValue = max(0.0, effectiveMidpoint - priceInfo.originalPrice)
        val priceAboveMidpointValue = max(0.0, priceInfo.originalPrice - effectiveMidpoint)
        val belowFraction = if (maxAbsoluteDeviation > 0) (priceBelowMidpointValue / maxAbsoluteDeviation).toFloat() else 0f
        val aboveFraction = if (maxAbsoluteDeviation > 0) (priceAboveMidpointValue / maxAbsoluteDeviation).toFloat() else 0f
        val colorRange = max(1.0, maxPriceForScaling) - minOriginalPrice
        val colorFraction = if (colorRange > 0) ((priceInfo.originalPrice - minOriginalPrice) / colorRange).toFloat().coerceIn(0f, 1f) else 0f
        val baseBarColor = if (priceInfo.originalPrice < 0) Color.Black else Color(red = colorFraction, green = 1 - colorFraction, blue = 0f)
        val luminance = 0.299 * baseBarColor.red + 0.587 * baseBarColor.green + 0.114 * baseBarColor.blue
        val textColorInside = if (luminance > 0.5) Color.Black else Color.White
        val textColorOutside = MaterialTheme.colorScheme.onSurface

        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(0.5f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                if (priceInfo.originalPrice < effectiveMidpoint && (priceBelowMidpointValue.roundToInt() > 0)) {
                    val textBelow = String.format(Locale.forLanguageTag("no-NO"), "-%d", priceBelowMidpointValue.roundToInt())
                    val barIsShort = belowFraction < 0.2f
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(belowFraction).background(baseBarColor), contentAlignment = Alignment.CenterEnd) { if (!barIsShort) Text(text = textBelow, style = MaterialTheme.typography.bodySmall, color = textColorInside, modifier = Modifier.padding(end = 4.dp), textAlign = TextAlign.End, softWrap = false) }
                    if (barIsShort) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { Text(text = textBelow, style = MaterialTheme.typography.bodySmall, color = textColorOutside, modifier = Modifier.padding(end = 4.dp), softWrap = false); Spacer(Modifier.fillMaxWidth(belowFraction)) }
                }
            }
            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)))
            Box(modifier = Modifier.weight(0.5f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (priceInfo.originalPrice >= effectiveMidpoint) {
                    val textAbove = String.format(Locale.forLanguageTag("no-NO"), "%d", priceAboveMidpointValue.roundToInt())
                    val barIsShort = aboveFraction < 0.2f
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(aboveFraction).background(baseBarColor), contentAlignment = Alignment.CenterStart) { if (!barIsShort) Text(text = textAbove, style = MaterialTheme.typography.bodySmall, color = textColorInside, modifier = Modifier.padding(start = 4.dp), softWrap = false) }
                    if (barIsShort) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start, modifier = Modifier.fillMaxWidth()) { Spacer(Modifier.fillMaxWidth(aboveFraction)); Text(text = textAbove, style = MaterialTheme.typography.bodySmall, color = textColorOutside, modifier = Modifier.padding(start = 4.dp), softWrap = false) }
                }
            }
        }
    } else {
        val priceInOre = priceInfo.effectivePrice
        val colorRange = maxEffectivePrice - minPrice
        val colorFraction = if (colorRange > 0) ((priceInOre - minPrice) / colorRange).toFloat().coerceIn(0f, 1f) else 0f
        val baseBarColor = if (priceInOre < 0) Color.Black else Color(red = colorFraction, green = 1 - colorFraction, blue = 0f)
        val luminance = 0.299 * baseBarColor.red + 0.587 * baseBarColor.green + 0.114 * baseBarColor.blue
        val textColor = if (luminance > 0.5) Color.Black else Color.White
        val minBarUiFraction = 0.14f
        val fullBarFraction = (if (isStromstotte) { if (maxEffectivePrice - minPrice > 0) minBarUiFraction + (1f - minBarUiFraction) * ((priceInOre - minPrice) / (maxEffectivePrice - minPrice)).toFloat() else if (priceInOre > 0) 0.5f else 0f } else { if (maxPriceForScaling - minOriginalPrice > 0) minBarUiFraction + (1f - minBarUiFraction) * ((priceInfo.originalPrice - minOriginalPrice) / (maxPriceForScaling - minOriginalPrice)).toFloat() else if (priceInfo.originalPrice > 0) 0.5f else 0f }).coerceIn(0f, 1f)
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(fullBarFraction), contentAlignment = Alignment.CenterStart) {
            Box(modifier = Modifier.fillMaxSize().background(baseBarColor))
            Text(text = priceText, style = MaterialTheme.typography.bodySmall, color = textColor, modifier = Modifier.padding(start = 4.dp).align(Alignment.CenterStart))
        }
    }
}

@Composable
fun AutoResizeText(text: String, style: TextStyle, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    val resizedTextStyleState = remember(text, style) { mutableStateOf(style) }
    var shouldDraw by remember { mutableStateOf(false) }

    Text(
        text = text,
        modifier = modifier.drawWithContent {
            if (shouldDraw) {
                drawContent()
            }
        },
        style = resizedTextStyleState.value,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        onTextLayout = { result ->
            if (result.hasVisualOverflow) {
                val currentSize = resizedTextStyleState.value.fontSize
                if (currentSize != TextUnit.Unspecified) {
                    resizedTextStyleState.value = resizedTextStyleState.value.copy(fontSize = currentSize * 0.95f)
                }
            } else {
                shouldDraw = true
            }
        }
    )
}
