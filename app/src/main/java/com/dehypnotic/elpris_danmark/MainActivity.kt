package com.dehypnotic.elpris_danmark

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.dehypnotic.elpris_danmark.network.PricePoint
import com.dehypnotic.elpris_danmark.network.fetchPrices
import com.dehypnotic.elpris_danmark.ui.theme.Android_elprisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.ceil
import kotlin.math.floor

private const val VAT_MULTIPLIER = 1.25

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

    val zones = listOf("DK1", "DK2")

    var selectedZone by remember {
        val savedZone = sharedPrefs.getString("selected_zone", "DK1") ?: "DK1"
        mutableStateOf(if (savedZone in zones) savedZone else "DK1")
    }
    var isMoms by remember {
        mutableStateOf(sharedPrefs.getBoolean("is_moms", true))
    }
    var isOtherFees by remember {
        mutableStateOf(sharedPrefs.getBoolean("is_other_fees", false))
    }
    var otherFeesAmount by remember {
        mutableStateOf(sharedPrefs.getFloat("other_fees_amount", 0f))
    }

    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    // Logic for other fees and amount state
    LaunchedEffect(isMoms) {
        sharedPrefs.edit {
            putBoolean("is_moms", isMoms)
        }
    }
    LaunchedEffect(isOtherFees) {
        sharedPrefs.edit {
            putBoolean("is_other_fees", isOtherFees)
        }
    }
    LaunchedEffect(otherFeesAmount) {
        sharedPrefs.edit {
            putFloat("other_fees_amount", otherFeesAmount)
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
            if (fetchedPrices.isEmpty()) {
                val today = LocalDate.now()
                val url = "https://www.elprisenligenu.dk/api/v1/prices/${selectedDate.year}/${String.format("%02d-%02d", selectedDate.monthValue, selectedDate.dayOfMonth)}_${selectedZone}.json"
                error = when {
                    selectedDate.isAfter(today.plusDays(1)) ->
                        "Fremtidige priser strækker sig kun til følgende dag efter udgivelse tidligst kl. 13"
                    selectedDate.isEqual(today.plusDays(1)) && LocalTime.now().hour < 13 ->
                        "Priserne for i morgen er ikke klar endnu. De offentliggøres normalt efter kl. 13."
                    else ->
                        "Ingen priser fundet for denne dag ($selectedDate, $selectedZone).\nURL forsøgt: $url"
                }
            } else {
                prices = fetchedPrices
            }
        } catch (e: Exception) {
            error = "Der opstod en fejl: ${e.localizedMessage ?: e.message}"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            BottomBar(
                isMoms = isMoms,
                onMomsChange = { isMoms = it },
                isOtherFees = isOtherFees,
                onOtherFeesChange = { isOtherFees = it },
                otherFeesAmount = otherFeesAmount,
                onOtherFeesAmountChange = { otherFeesAmount = it }
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
                            isMoms = isMoms,
                            isOtherFees = isOtherFees,
                            otherFeesAmount = otherFeesAmount,
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
    isMoms: Boolean,
    onMomsChange: (Boolean) -> Unit,
    isOtherFees: Boolean,
    onOtherFeesChange: (Boolean) -> Unit,
    otherFeesAmount: Float,
    onOtherFeesAmountChange: (Float) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Moms", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = isMoms, onCheckedChange = onMomsChange)
                }

                Spacer(Modifier.width(24.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Andre afgifter", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = isOtherFees, onCheckedChange = onOtherFeesChange)
                }

                if (isOtherFees) {
                    Spacer(Modifier.width(8.dp))
                    var textFieldValue by remember(otherFeesAmount) {
                        mutableStateOf(if (otherFeesAmount == 0f) "" else otherFeesAmount.toString().replace('.', ','))
                    }
                    OutlinedTextField(
                        value = textFieldValue,
                        onValueChange = { newValue: String ->
                            val sanitized = newValue.replace(',', '.')
                            if (sanitized.isEmpty() || sanitized == "." || sanitized.toFloatOrNull() != null) {
                                textFieldValue = newValue
                                val numericValue = sanitized.toFloatOrNull() ?: 0f
                                onOtherFeesAmountChange(numericValue)
                            }
                        },
                        modifier = Modifier
                            .width(80.dp)
                            .height(56.dp),
                        label = { Text("Beløb", style = MaterialTheme.typography.labelSmall) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun ZoneSelector(selectedZone: String, onZoneSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    val zones = listOf("DK1", "DK2")
    val selectedIndex = zones.indexOf(selectedZone).coerceAtLeast(0)

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
    isMoms: Boolean,
    isOtherFees: Boolean,
    otherFeesAmount: Float,
    currentTime: LocalTime,
    modifier: Modifier = Modifier
) {
    val pricesInfo = remember(prices, isMoms, isOtherFees, otherFeesAmount) {
        prices.map { pricePoint ->
            val priceExVat = pricePoint.price_per_kWh
            val basePriceWithVat = if (isMoms) priceExVat * VAT_MULTIPLIER else priceExVat
            val originalPriceInOre = basePriceWithVat * 100

            var effectivePrice = basePriceWithVat
            if (isOtherFees) {
                effectivePrice += (otherFeesAmount / 100.0)
            }
            val effectivePriceInOre = effectivePrice * 100

            PriceInfo(pricePoint, effectivePriceInOre, originalPriceInOre)
        }
    }

    val maxPriceForScaling = remember(pricesInfo) { pricesInfo.maxOfOrNull { it.originalPrice } ?: 1.0 }
    val minOriginalPrice = remember(pricesInfo) { pricesInfo.filter { it.originalPrice >= 0 }.minOfOrNull { it.originalPrice } ?: 0.0 }
    val minPrice = remember(pricesInfo) { pricesInfo.filter { it.effectivePrice >= 0 }.minOfOrNull { it.effectivePrice } ?: 0.0 }
    val maxEffectivePrice = remember(pricesInfo) { pricesInfo.maxOfOrNull { it.effectivePrice } ?: 1.0 }

    val averagePrice = remember(pricesInfo) { if (pricesInfo.isNotEmpty()) pricesInfo.map { it.effectivePrice }.average() else 0.0 }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.forLanguageTag("da-DK")) }
    val dateText = selectedDate.format(dateFormatter)

    val averagePriceFormatted = String.format(Locale.forLanguageTag("da-DK"), "%.2f", averagePrice)
    val headerText = if (isMoms) {
        "Priser i øre/kWh inkl. moms for $dateText. Gns: $averagePriceFormatted"
    } else {
        "Priser i øre/kWh ekskl. moms for $dateText. Gns: $averagePriceFormatted"
    }

    val currentHour = currentTime.hour

    val gridLines = remember(minOriginalPrice, maxPriceForScaling) {
        val diff = maxPriceForScaling - minOriginalPrice
        val step = when {
            diff <= 50 -> 10.0
            diff <= 125 -> 25.0
            else -> 50.0
        }
        val lines = mutableListOf<Double>()
        var current = ceil(minOriginalPrice / step) * step
        while (current <= maxPriceForScaling) {
            lines.add(current)
            current += step
        }
        // Limit to max 5 lines as requested
        if (lines.size > 5) {
            // If too many lines, take a subset or just keep the first 5
            lines.take(5)
        } else {
            lines
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AutoResizeText(
            text = headerText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        Box(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            // Grid lines in the background
            Canvas(modifier = Modifier.fillMaxSize().padding(start = 24.dp)) { // 24.dp matches hour Label width
                val minBarUiFraction = 0.14f
                val range = maxPriceForScaling - minOriginalPrice

                gridLines.forEach { price ->
                    val fraction = if (range > 0) {
                        val scaledFraction = ((price - minOriginalPrice) / range).toFloat()
                        minBarUiFraction + (1f - minBarUiFraction) * scaledFraction
                    } else {
                        0.5f
                    }
                    val x = size.width * fraction.coerceIn(0f, 1f)
                    drawLine(
                        color = Color.LightGray.copy(alpha = 0.5f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(pricesInfo) { priceInfo ->
                    ChartBar(
                        priceInfo = priceInfo,
                        maxPriceForScaling = maxPriceForScaling,
                        minOriginalPrice = minOriginalPrice,
                        minPrice = minPrice,
                        maxEffectivePrice = maxEffectivePrice,
                        selectedDate = selectedDate,
                        currentHour = currentHour,
                        modifier = Modifier.fillParentMaxHeight(1f / 24f)
                    )
                }
            }
        }
        // Price axis/labels at the bottom
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
        ) {
            // Horizontal line to separate chart from labels
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
            ) {
                drawLine(
                    color = Color.Gray,
                    start = Offset(24.dp.toPx(), 0f), // Match start of grid
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(24.dp)) // Match hour label width
                Box(modifier = Modifier.fillMaxWidth()) {
                    val minBarUiFraction = 0.14f
                    val range = maxPriceForScaling - minOriginalPrice

                    gridLines.forEach { price ->
                        val fraction = if (range > 0) {
                            val scaledFraction = ((price - minOriginalPrice) / range).toFloat()
                            minBarUiFraction + (1f - minBarUiFraction) * scaledFraction
                        } else {
                            0.5f
                        }

                        // Centering the text under the line:
                        // We use a Box with fillMaxWidth(fraction) and a child that is aligned to CenterEnd,
                        // but then offset by half its estimate width to truly center it.
                        // Or simpler: use an absolute offset based on size.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction.coerceIn(0f, 1f)),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = price.toInt().toString(),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.offset(x = 10.dp), // Offset forward slightly to center the digits
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

data class PriceInfo(val pricePoint: PricePoint, val effectivePrice: Double, val originalPrice: Double)

@SuppressLint("DefaultLocale")
@Composable
fun ChartBar(
    priceInfo: PriceInfo,
    maxPriceForScaling: Double,
    minOriginalPrice: Double,
    minPrice: Double,
    maxEffectivePrice: Double,
    selectedDate: LocalDate,
    currentHour: Int,
    modifier: Modifier = Modifier
) {
    val hour = remember(priceInfo.pricePoint.time_start) {
        val dt = java.time.OffsetDateTime.parse(priceInfo.pricePoint.time_start)
        String.format("%02d", dt.hour)
    }
    val priceText = String.format(Locale.forLanguageTag("da-DK"), "%.2f", priceInfo.effectivePrice)
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
            priceText = priceText
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
    priceText: String
) {
    val priceInOre = priceInfo.effectivePrice
    val originalPrice = priceInfo.originalPrice
    val colorRange = maxEffectivePrice - minPrice
    val colorFraction = if (colorRange > 0) {
        ((priceInOre - minPrice) / colorRange).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    val baseBarColor = if (priceInOre < 0) Color.Black else Color(red = colorFraction, green = 1 - colorFraction, blue = 0f)
    val luminance = 0.299 * baseBarColor.red + 0.587 * baseBarColor.green + 0.114 * baseBarColor.blue
    val textColorInside = if (luminance > 0.5) Color.Black else Color.White
    val minBarUiFraction = 0.14f

    val range = maxPriceForScaling - minOriginalPrice
    val fraction = if (range > 0) {
        val scaledFraction = ((originalPrice - minOriginalPrice) / range).toFloat()
        minBarUiFraction + (1f - minBarUiFraction) * scaledFraction
    } else {
        if (originalPrice > 0) 0.5f else 0f
    }
    val fullBarFraction = fraction.coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fullBarFraction)
                .background(baseBarColor)
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = priceText,
                style = MaterialTheme.typography.bodySmall,
                color = textColorInside,
                modifier = Modifier.padding(start = 4.dp),
                softWrap = false
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
    val textStyleState = remember(text, style) { mutableStateOf(style) }
    val readyToDraw = remember(text, style) { mutableStateOf(false) }

    Text(
        text = text,
        modifier = modifier.drawWithContent {
            if (readyToDraw.value) {
                drawContent()
            }
        },
        style = textStyleState.value,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        onTextLayout = { result ->
            if (result.hasVisualOverflow) {
                val currentFontSize = textStyleState.value.fontSize
                if (currentFontSize != TextUnit.Unspecified && currentFontSize.value > 2f) {
                    textStyleState.value = textStyleState.value.copy(
                        fontSize = currentFontSize * 0.9f
                    )
                } else {
                    readyToDraw.value = true
                }
            } else {
                readyToDraw.value = true
            }
        }
    )
}
