package de.timklge.headwind.client.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.timklge.headwind.client.HeadwindClient
import de.timklge.headwind.client.HeadwindForecastPoint
import de.timklge.headwind.client.HeadwindSnapshot
import de.timklge.headwind.client.TrueWindData
import kotlinx.coroutines.flow.catch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CurrentDataScreen()
                }
            }
        }
    }
}

@Composable
private fun CurrentDataScreen() {
    val context = LocalContext.current
    val client = remember(context) { HeadwindClient(context) }
    val installed = remember(client) { client.isInstalled() }

    var snapshot by remember { mutableStateOf<HeadwindSnapshot?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(client, installed) {
        if (!installed) return@LaunchedEffect
        client.snapshots()
            .catch { failure = it.message ?: it.toString() }
            .collect {
                snapshot = it
                failure = null
            }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Headwind client example", style = MaterialTheme.typography.titleLarge)
        }

        if (!installed) {
            item {
                Text(
                    "karoo-headwind is not installed on this device.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@LazyColumn
        }

        failure?.let { message ->
            item {
                Text("Connection error: $message", color = MaterialTheme.colorScheme.error)
            }
        }

        val current = snapshot
        if (current == null) {
            if (failure == null) {
                item { Text("Waiting for data from karoo-headwind...") }
            }
            return@LazyColumn
        }

        item {
            Text("Provider: ${current.provider?.label ?: "unknown"}")
        }
        item {
            Text("Wind unit: ${current.windUnit?.label ?: "unknown"}")
        }
        item {
            Text("Last successful fetch: ${formatTime(current.lastSuccessfulFetchEpochSeconds)}")
        }
        item {
            Text("Last failed fetch: ${formatTime(current.lastFailedFetchEpochSeconds)}")
        }
        current.error?.let { error ->
            item {
                Text("Service error: $error", color = MaterialTheme.colorScheme.error)
            }
        }

        item { Text("Schema version: ${current.version}") }
        current.trueWind?.let { tw ->
            item { TrueWindCard(tw) }
        }

        val points = current.forecast.orEmpty()
        if (points.isEmpty()) {
            item { Text("No weather data cached yet.") }
        }
        items(points) { point ->
            ForecastPointCard(point)
        }
    }
}

@Composable
private fun TrueWindCard(tw: TrueWindData) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("TrueWind (${tw.status})", style = MaterialTheme.typography.titleMedium)
        tw.riderWindSpeed?.let { Text("Rider wind: %.1f m/s from %.0f°".format(it, tw.riderWindDirection ?: 0.0)) }
        tw.windSpeed10m?.let { Text("10 m wind: %.1f m/s from %.0f°".format(it, tw.windDirection10m ?: 0.0)) }
        tw.headwind?.let { Text("Headwind: %.1f m/s, crosswind %.1f m/s".format(it, tw.crosswind ?: 0.0)) }
        Text("Confidence: ${tw.confidence} (${tw.confidenceBand ?: "-"})")
        Text("Air density: %.3f kg/m³".format(tw.airDensity))
        tw.measuredHeadwind?.let { Text("Measured headwind: %.1f m/s".format(it)) }
        if (tw.models.isNotEmpty()) Text("Models: ${tw.models.joinToString { it.substringAfter(':') }}")
        tw.nearestStationId?.let { Text("Station: $it, %.1f km".format(tw.nearestStationDistanceKm ?: 0.0)) }
        Text("Last update: ${formatTime(tw.lastUpdateEpochSeconds)}")
    }
}

@Composable
private fun ForecastPointCard(point: HeadwindForecastPoint) {
    val weather = point.current

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Lat %.4f, Lon %.4f".format(point.lat, point.lon),
            style = MaterialTheme.typography.titleMedium,
        )
        point.distanceAlongRoute?.let { distance ->
            Text("Distance along route: %.1f".format(distance))
        }
        Text("Observed: ${formatTime(weather.time)}")
        Text("Forecast hours: ${point.hourly.size}")
        Text("Temperature: %.1f °C".format(weather.temperature))
        Text("Wind: %.1f m/s from %.0f°".format(weather.windSpeed, weather.windDirection))
        Text("Gusts: %.1f m/s".format(weather.windGusts))
        Text("Humidity: ${weather.relativeHumidity} %")
        Text("Precipitation: %.2f".format(weather.precipitation))
        Text("Cloud cover: %.0f %%".format(weather.cloudCover))
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

private fun formatTime(epochSeconds: Long?): String {
    return epochSeconds?.let { timeFormatter.format(Instant.ofEpochSecond(it)) } ?: "never"
}
