package com.x1colegal.browserengine

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class HomeEntry(val title: String, val details: String, val url: String?)
internal data class HomeSection(val title: String, val source: HomeEntry, val entries: List<HomeEntry>)

internal fun JSONObject.homeSections(): List<HomeSection> {
    fun entries(key: String, convert: (JSONObject) -> HomeEntry?): List<HomeEntry> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { it ->
            array.optJSONObject(it)?.let(convert)
        }
    }

    fun JSONObject.entry(title: String, details: String): HomeEntry? =
        title.takeIf { it.isNotBlank() }?.let {
            val url = optString("url").takeIf { it.toHttpUriOrNull() != null }
            HomeEntry(title, details, url)
        }

    val wikipedia = HomeEntry("Wikipedia", "", "https://en.wikipedia.org/")
    val world = entries("world_news") {
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

    return buildList {
        add(HomeSection("News", HomeEntry("BBC News", "", "https://www.bbc.com/news/world"), world))
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
    loading: Boolean,
    search: (String) -> Unit,
    navigate: (String) -> Unit,
    retry: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val submit = { query.trim().takeIf(String::isNotEmpty)?.let(search); Unit }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "search", contentType = "search") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.ic_browser), "BrowserEngine icon", Modifier.size(96.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("BrowserEngine", style = MaterialTheme.typography.headlineLarge)
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
            if (loading && sections == null) {
                item(key = "loading", contentType = "loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
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
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { section.source.url?.let(navigate) }) { Text(section.source.title) }
                    }
                }
                if (section.entries.isEmpty()) {
                    item(key = "empty:${section.title}", contentType = "empty section") { Text("No stories available.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                itemsIndexed(
                    items = section.entries,
                    key = { index, _ -> "story:${section.title}:$index" },
                    contentType = { _, entry -> if (entry.url == null) "plain story" else "linked story" }
                ) { _, entry ->
                    val content: @Composable () -> Unit = {
                        ListItem(
                            headlineContent = { Text(entry.title) },
                            supportingContent = if (entry.details.isBlank()) null else ({ Text(entry.details) }),
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        )
                    }
                    val url = entry.url
                    if (url != null) Card(onClick = { navigate(url) }, modifier = Modifier.fillMaxWidth(), content = { content() })
                    else Card(modifier = Modifier.fillMaxWidth(), content = { content() })
                }
            }
        }
    }
}
