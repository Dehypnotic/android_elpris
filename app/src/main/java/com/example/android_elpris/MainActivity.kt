package com.example.android_elpris

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.example.android_elpris.network.PricePoint
import com.example.android_elpris.network.fetchPrices
import com.example.android_elpris.ui.theme.Android_elprisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            val fetchedPrices = withContext(Dispatchers.IO) {
                fetchPrices(LocalDate.now(), "NO3")
            }
            prices = fetchedPrices
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            error != null -> {
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            else -> {
                PriceList(prices = prices)
            }
        }
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
