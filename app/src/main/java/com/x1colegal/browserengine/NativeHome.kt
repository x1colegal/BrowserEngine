package com.x1colegal.browserengine

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor

@Immutable
internal data class HomeEntry(
    val title: String,
    val details: String,
    val url: String?,
    val imageUrl: String? = null
)

@Immutable
internal data class HomeSection(val title: String, val source: HomeEntry, val entries: List<HomeEntry>)

@Immutable
internal data class WeatherInfo(
    val location: String,
    val temperatureC: Double,
    val feelsLikeC: Double?,
    val humidity: Int?,
    val windKmh: Double?,
    val pressureHpa: Double?,
    val cloudCover: Int?,
    val precipitationMm: Double?,
    val isDay: Boolean,
    val condition: String,
    val icon: String,
    val updatedAt: String
)

@Immutable
internal data class HomeFeedData(
    val sections: List<HomeSection>,
    val weather: WeatherInfo?
)

internal fun JSONObject.toWeatherInfo(): WeatherInfo? {
    val location = optString("location").takeIf { it.isNotBlank() && it != "null" } ?: return null
    val temp = optDouble("temperature_c")
    if (temp.isNaN()) return null
    return WeatherInfo(
        location = location,
        temperatureC = temp,
        feelsLikeC = optDouble("feels_like_c").takeIf { !it.isNaN() },
        humidity = optInt("humidity", -1).takeIf { it >= 0 },
        windKmh = optDouble("wind_kmh").takeIf { !it.isNaN() },
        pressureHpa = optDouble("pressure_hpa").takeIf { !it.isNaN() },
        cloudCover = optInt("cloud_cover", -1).takeIf { it >= 0 },
        precipitationMm = optDouble("precipitation_mm").takeIf { !it.isNaN() },
        isDay = optBoolean("is_day", true),
        condition = optString("condition").ifBlank { "Weather" },
        icon = optString("icon").ifBlank { "cloudy" },
        updatedAt = optString("updated_at")
    )
}

internal fun JSONObject.homeFeedData(useDynamicTabs: Boolean = false): HomeFeedData =
    HomeFeedData(sections = homeSections(useDynamicTabs), weather = optJSONObject("weather")?.toWeatherInfo())

internal fun JSONObject.homeSections(useDynamicTabs: Boolean = false): List<HomeSection> {
    val tabs = if (useDynamicTabs) {
        optJSONArray("tabs")?.let { array ->
            (0 until array.length()).mapNotNull { idx ->
                val obj = array.optJSONObject(idx) ?: return@mapNotNull null
                val uiName = obj.optString("ui_name").trim()
                val internalName = obj.optString("internal_name").trim()
                if (uiName.isNotEmpty() && internalName.isNotEmpty()) HomeTabConfig(uiName, internalName) else null
            }
        }.orEmpty()
    } else {
        emptyList()
    }
    fun entries(key: String, convert: (JSONObject) -> HomeEntry?): List<HomeEntry> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { it ->
            array.optJSONObject(it)?.let(convert)
        }
    }

    fun JSONObject.entry(title: String, details: String): HomeEntry? =
        title.takeIf { it.isNotBlank() }?.let {
            val url = optString("url").takeIf { it.toHttpUriOrNull() != null }
            val image = optString("image").takeIf { it.toHttpUriOrNull() != null }
            HomeEntry(title, details, url, image)
        }

    val wikipedia = HomeEntry("Wikipedia", "", "https://en.wikipedia.org/")
    val world = entries("world_news") {
        it.entry(it.optString("title"), listOf(
            it.optString("source"),
            it.optString("published_at").substringBefore('T').takeIf { date -> date != "null" }.orEmpty()
        ).filter(String::isNotBlank).joinToString(" · "))
    }
    val science = entries("science_news") {
        it.entry(it.optString("title"), listOf(
            it.optString("source"),
            it.optString("published_at").substringBefore('T').takeIf { date -> date != "null" }.orEmpty()
        ).filter(String::isNotBlank).joinToString(" · "))
    }
    val business = entries("business_news") {
        it.entry(it.optString("title"), listOf(
            it.optString("source"),
            it.optString("published_at").substringBefore('T').takeIf { date -> date != "null" }.orEmpty()
        ).filter(String::isNotBlank).joinToString(" · "))
    }
    val tech = entries("news") {
        it.entry(it.optString("title"), "${it.optString("host")} · ${it.optInt("comments")} comments")
    }
    val featured = optJSONObject("featured")?.let {
        it.entry(it.optString("title"), it.optString("description"))
    }
    val events = entries("on_this_day") {
        it.entry(it.optString("text"), it.optString("year"))
    }
    val facts = optJSONArray("did_you_know")?.let { array ->
        (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank)
            .map { HomeEntry(it, "", null) }
    }.orEmpty()

    if (tabs.isNotEmpty()) {
        return buildList {
            for (tab in tabs) {
                val items = when (tab.internalName) {
                    "news" -> tech
                    "featured" -> listOfNotNull(featured)
                    "on_this_day" -> events
                    "did_you_know" -> facts
                    else -> entries(tab.internalName) {
                        it.entry(it.optString("title"), listOf(
                            it.optString("source"),
                            it.optString("published_at").substringBefore('T').takeIf { date -> date != "null" }.orEmpty()
                        ).filter(String::isNotBlank).joinToString(" · "))
                    }
                }
                if (items.isNotEmpty()) {
                    add(HomeSection(tab.uiName, HomeEntry(tab.uiName, "", null), items))
                }
            }
        }
    }

    return buildList {
        add(HomeSection("News", HomeEntry("BBC News", "", "https://www.bbc.com/news/world"), world.take(8)))
        add(HomeSection("Tech news", HomeEntry("Hacker News", "", "https://news.ycombinator.com/"), tech))
        add(HomeSection("Explore", wikipedia, listOfNotNull(featured)))
        if (events.isNotEmpty()) add(HomeSection("On this day", wikipedia, events))
        if (facts.isNotEmpty()) add(HomeSection("Did you know?", HomeEntry(
            "Wikipedia", "", "https://en.wikipedia.org/wiki/Wikipedia:Recent_additions"
        ), facts))
    }
}

@Composable
internal fun NativeHomeScreen(
    sections: List<HomeSection>?,
    weather: WeatherInfo?,
    savedLocation: String,
    weatherUpdating: Boolean,
    weatherError: String?,
    weatherLoadingPlaceholder: Boolean,
    backoffSeconds: Int,
    loading: Boolean,
    incognito: Boolean,
    executor: GeckoWebExecutor,
    sessionClient: HomeSessionClient,
    search: (String) -> Unit,
    navigate: (String) -> Unit,
    searchLocations: suspend (String) -> List<WeatherLocationResult>,
    updateWeather: (location: String, label: String?) -> Unit,
    refreshWeather: () -> Unit,
    retry: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var editingLocation by remember { mutableStateOf(false) }
    var locationInput by remember(savedLocation) { mutableStateOf("") }
    var locationResults by remember { mutableStateOf<List<WeatherLocationResult>>(emptyList()) }
    var searchingLocations by remember { mutableStateOf(false) }
    val submit = { query.trim().takeIf(String::isNotEmpty)?.let(search); Unit }

    LaunchedEffect(editingLocation, locationInput) {
        if (!editingLocation) return@LaunchedEffect
        val trimmed = locationInput.trim()
        if (trimmed.length < 2) {
            locationResults = emptyList()
            searchingLocations = false
            return@LaunchedEffect
        }
        searchingLocations = true
        delay(350)
        locationResults = runCatching { searchLocations(trimmed) }.getOrDefault(emptyList())
        searchingLocations = false
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "search", contentType = "search") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.ic_browser), "BrowserEngine icon", Modifier.size(96.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("BrowserEngine", style = MaterialTheme.typography.headlineLarge)
                    if (incognito) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Incognito Mode",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Search Google", maxLines = 1) },
                            shape = MaterialTheme.shapes.extraLarge,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submit() })
                        )
                        Button(onClick = submit, enabled = query.isNotBlank()) { Text("Search") }
                    }
                }
            }
            item(key = "weather", contentType = "weather") {
                WeatherCard(
                    weather = weather,
                    loading = weatherLoadingPlaceholder && (loading || weatherUpdating),
                    updating = weatherUpdating,
                    error = weatherError,
                    onRefresh = refreshWeather,
                    onEdit = {
                        locationInput = weather?.location ?: ""
                        locationResults = emptyList()
                        editingLocation = true
                    }
                )
            }
            item(key = "pinned_sites", contentType = "pinned_sites") {
                PinnedSitesGrid(
                    onOpenUrl = navigate,
                    executor = executor,
                    sessionClient = sessionClient
                )
            }
            if (loading && sections == null) {
                item(key = "loading", contentType = "loading") {
                    if (backoffSeconds > 0) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "Loading, Waiting ${backoffSeconds}s...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                }
            }
            if (!loading && sections == null) {
                item(key = "error", contentType = "error") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Couldn't load the news.", Modifier.weight(1f))
                        TextButton(onClick = retry) { Text("Try again") }
                    }
                }
            }
            sections.orEmpty().forEach { section ->
                item(key = "header:${section.title}", contentType = "section header") {
                    Text(
                        section.title,
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                if (section.entries.isEmpty()) {
                    item(key = "empty:${section.title}", contentType = "empty section") { Text("No stories available.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                itemsIndexed(
                    items = section.entries,
                    key = { index, _ -> "story:${section.title}:$index" },
                    contentType = { _, entry -> if (entry.url == null) "plain story" else "linked story" }
                ) { _, entry ->
                    HomeStoryCard(entry, executor, sessionClient, navigate)
                }
            }
        }
    }

    if (editingLocation) {
        AlertDialog(
            onDismissRequest = { editingLocation = false },
            title = { Text("Weather location") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = locationInput,
                        onValueChange = { locationInput = it },
                        singleLine = true,
                        placeholder = { Text("Search city (e.g. Sao Paulo)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (searchingLocations) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (locationResults.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                            itemsIndexed(locationResults, key = { idx, item -> "${item.coords}:$idx" }) { _, result ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            editingLocation = false
                                            updateWeather(result.coords, result.display)
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(result.display, style = MaterialTheme.typography.bodyMedium)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = locationInput.isNotBlank(),
                    onClick = {
                        val target = locationInput.trim()
                        editingLocation = false
                        updateWeather(target, null)
                    }
                ) { Text("Use text") }
            },
            dismissButton = {
                TextButton(onClick = { editingLocation = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun WeatherCard(
    weather: WeatherInfo?,
    loading: Boolean,
    updating: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onEdit: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val (bgColor, accentColor, icon) = when (weather?.icon) {
        "clear" -> if (weather.isDay) {
            Triple(
                if (isDark) Color(0xFF332600) else Color(0xFFFFF4D1),
                if (isDark) Color(0xFFFFCA28) else Color(0xFFE68900),
                Icons.Default.WbSunny
            )
        } else {
            Triple(
                if (isDark) Color(0xFF171E38) else Color(0xFFE5EBFF),
                if (isDark) Color(0xFF9FB4FF) else Color(0xFF3451B2),
                Icons.Default.NightsStay
            )
        }
        "rain" -> Triple(
            if (isDark) Color(0xFF102536) else Color(0xFFDDF0FF),
            if (isDark) Color(0xFF4FC3F7) else Color(0xFF0277BD),
            Icons.Default.WaterDrop
        )
        "storm" -> Triple(
            if (isDark) Color(0xFF261838) else Color(0xFFEDE2FF),
            if (isDark) Color(0xFFCE93D8) else Color(0xFF6A1B9A),
            Icons.Default.Thunderstorm
        )
        "snow" -> Triple(
            if (isDark) Color(0xFF172C38) else Color(0xFFE6F7FF),
            if (isDark) Color(0xFF81D4FA) else Color(0xFF0288D1),
            Icons.Default.AcUnit
        )
        "fog" -> Triple(
            if (isDark) Color(0xFF23262B) else Color(0xFFECEFF1),
            if (isDark) Color(0xFFB0BEC5) else Color(0xFF546E7A),
            Icons.Default.Dehaze
        )
        "cloudy" -> Triple(
            if (isDark) Color(0xFF1C242E) else Color(0xFFE6EEF5),
            if (isDark) Color(0xFF90CAF9) else Color(0xFF1976D2),
            Icons.Default.Cloud
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainer,
            MaterialTheme.colorScheme.primary,
            Icons.Default.Cloud
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        if (weather == null) {
            val showLoading = loading && error == null
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (showLoading) "Loading..." else "Weather",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when {
                            showLoading -> "Loading Weather, Please Wait"
                            error != null -> error
                            else -> "Set your city to view local weather."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (updating || showLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else IconButton(onClick = onEdit) {
                    Icon(Icons.Default.LocationOn, contentDescription = "Set weather location")
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(weather.location, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${String.format("%.1f", weather.temperatureC)}°C", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    }
                    if (updating) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Row {
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh weather")
                            }
                            IconButton(onClick = onEdit) {
                                Icon(Icons.Default.Edit, contentDescription = "Change weather location")
                            }
                        }
                    }
                }
                val summary = buildList {
                    add(weather.condition)
                    weather.feelsLikeC?.let { add("Feels like ${String.format("%.1f", it)}°C") }
                }.joinToString(" · ")
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    weather.humidity?.let { WeatherStat("Humidity", "$it%") }
                    weather.windKmh?.let { WeatherStat("Wind", "${String.format("%.0f", it)} km/h") }
                    weather.pressureHpa?.let { WeatherStat("Pressure", "${String.format("%.0f", it)} hPa") }
                    weather.cloudCover?.let { WeatherStat("Clouds", "$it%") }
                }
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun WeatherStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HomeStoryCard(entry: HomeEntry, executor: GeckoWebExecutor, sessionClient: HomeSessionClient, navigate: (String) -> Unit) {
    val (thumbnail, imageLoading) = rememberRemoteImageState(entry.imageUrl, executor, sessionClient)
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                if (entry.details.isNotBlank()) {
                    Text(
                        entry.details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (entry.imageUrl != null) {
                Box(
                    Modifier.size(64.dp).clip(MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (imageLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
    val url = entry.url
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    if (url != null) Card(onClick = { navigate(url) }, modifier = Modifier.fillMaxWidth(), colors = colors, content = { content() })
    else Card(modifier = Modifier.fillMaxWidth(), colors = colors, content = { content() })
}

@Composable
private fun rememberRemoteImageState(
    url: String?,
    executor: GeckoWebExecutor,
    sessionClient: HomeSessionClient
): Pair<ImageBitmap?, Boolean> {
    if (url == null) return null to false
    var image by remember(url) { mutableStateOf(HomeImageLoader.cached(url)) }
    var loading by remember(url) { mutableStateOf(image == null) }
    LaunchedEffect(url) {
        if (image == null) {
            loading = true
            try {
                image = withContext(Dispatchers.IO) { HomeImageLoader.fetch(executor, url, sessionClient) }
            } finally {
                loading = false
            }
        }
    }
    return image to loading
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedSitesGrid(
    onOpenUrl: (String) -> Unit,
    executor: GeckoWebExecutor,
    sessionClient: HomeSessionClient
) {
    val context = LocalContext.current.applicationContext
    var sites by remember { mutableStateOf(loadPinnedSites(context)) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 8.dp)
    ) {
        val tileWidth = ((maxWidth - 24.dp) / 4).coerceAtLeast(72.dp)
        val items = sites.mapIndexed { idx, site -> idx to site } + listOf(-1 to null)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for ((idx, site) in items) {
                if (site == null) {
                    Column(
                        modifier = Modifier
                            .width(tileWidth)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showAddDialog = true }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("+", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        Text(
                            "Add shortcut",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    val isMenuOpen = menuIndex == idx
                    val scale by animateFloatAsState(if (isMenuOpen) 0.92f else 1f, label = "shortcut_scale")
                    val faviconUrl = remember(site.first) {
                        runCatching {
                            val uri = android.net.Uri.parse(site.first)
                            val scheme = uri.scheme ?: "https"
                            val host = uri.host
                            if (!host.isNullOrBlank()) "$scheme://$host/favicon.ico" else null
                        }.getOrNull()
                    }
                    val (favicon, _) = rememberRemoteImageState(faviconUrl, executor, sessionClient)
                    Box(modifier = Modifier.width(tileWidth)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { onOpenUrl(site.first) },
                                    onLongClick = { menuIndex = idx }
                                )
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (favicon != null) {
                                        Image(
                                            bitmap = favicon,
                                            contentDescription = site.second,
                                            modifier = Modifier.size(28.dp).clip(CircleShape),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text(
                                            site.second.firstOrNull()?.uppercase() ?: "?",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                            Text(
                                site.second,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = isMenuOpen,
                            onDismissRequest = { menuIndex = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit shortcut") },
                                onClick = {
                                    menuIndex = null
                                    editingIndex = idx
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove") },
                                onClick = {
                                    menuIndex = null
                                    val updated = sites.toMutableList().apply { removeAt(idx) }
                                    sites = updated
                                    savePinnedSites(context, updated)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog || editingIndex != null) {
        val current = editingIndex?.let { sites.getOrNull(it) }
        var nameInput by remember(editingIndex, showAddDialog) { mutableStateOf(current?.second.orEmpty()) }
        var urlInput by remember(editingIndex, showAddDialog) { mutableStateOf(current?.first.orEmpty()) }
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editingIndex = null
            },
            title = { Text(if (current == null) "Add shortcut" else "Edit shortcut") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("URL") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmedUrl = urlInput.trim().let {
                        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
                    }
                    val trimmedName = nameInput.trim().ifEmpty { trimmedUrl }
                    if (urlInput.isNotBlank()) {
                        val updated = sites.toMutableList()
                        val idx = editingIndex
                        if (idx != null && idx in updated.indices) {
                            updated[idx] = trimmedUrl to trimmedName
                        } else {
                            updated.add(trimmedUrl to trimmedName)
                        }
                        sites = updated
                        savePinnedSites(context, updated)
                    }
                    showAddDialog = false
                    editingIndex = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    editingIndex = null
                }) { Text("Cancel") }
            }
        )
    }
}

private fun loadPinnedSites(context: Context): List<Pair<String, String>> {
    val raw = AppPrefs.get(context, "pinned_sites").getString("items", null) ?: return emptyList()
    val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { idx ->
        val obj = array.optJSONObject(idx) ?: return@mapNotNull null
        val url = obj.optString("url").trim()
        val name = obj.optString("name").trim()
        if (url.isNotEmpty() && name.isNotEmpty()) url to name else null
    }
}

private fun savePinnedSites(context: Context, sites: List<Pair<String, String>>) {
    val array = org.json.JSONArray()
    for ((url, name) in sites) {
        array.put(JSONObject().put("url", url).put("name", name))
    }
    AppPrefs.get(context, "pinned_sites").edit().putString("items", array.toString()).apply()
}
