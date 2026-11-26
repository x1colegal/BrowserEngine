package com.x1colegal.browserengine

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult

internal data class SavedLogin(
    val origin: String,
    val username: String,
    val password: String
)

internal data class PendingLoginSave(
    val login: SavedLogin,
    val isUpdate: Boolean
)

internal object PasswordManager {
    var pendingSave by mutableStateOf<PendingLoginSave?>(null)
    var pendingSubmit by mutableStateOf<SavedLogin?>(null)
    var pendingFocusDomain by mutableStateOf<String?>(null)
    var activeSuggestions by mutableStateOf<List<SavedLogin>>(emptyList())

    fun listForDomain(context: Context, domain: String): List<SavedLogin> {
        val host = domain.trim().lowercase()
        if (host.isBlank()) return emptyList()
        return loadAll(context).filter {
            val savedHost = (it.origin.toHttpUriOrNull()?.host ?: it.origin).trim().lowercase()
            if (savedHost.isBlank()) false
            else savedHost == host ||
                (host.contains('.') && savedHost.endsWith(".$host")) ||
                (savedHost.contains('.') && host.endsWith(".$savedHost"))
        }
    }

    fun saveOrUpdate(context: Context, login: SavedLogin) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.origin == login.origin && it.username == login.username }
        if (idx >= 0) list[idx] = login else list.add(login)
        persist(context, list)
    }

    fun delete(context: Context, login: SavedLogin) {
        val list = loadAll(context).filterNot {
            it.origin == login.origin && it.username == login.username && it.password == login.password
        }
        persist(context, list)
        activeSuggestions = activeSuggestions.filterNot {
            it.origin == login.origin && it.username == login.username && it.password == login.password
        }
    }

    fun onFormLoginSubmitted(context: Context, origin: String, username: String, password: String) {
        if (origin.isBlank() || password.isBlank()) return
        val existing = loadAll(context).firstOrNull { it.origin == origin && it.username == username }
        if (existing == null) {
            pendingSave = PendingLoginSave(SavedLogin(origin, username, password), isUpdate = false)
        } else if (existing.password != password) {
            pendingSave = PendingLoginSave(SavedLogin(origin, username, password), isUpdate = true)
        }
    }

    fun createStorageDelegate(context: Context): Autocomplete.StorageDelegate =
        object : Autocomplete.StorageDelegate {
            override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>> {
                val matches = listForDomain(context, domain)
                activeSuggestions = matches
                val entries = matches.map {
                    Autocomplete.LoginEntry.Builder()
                        .origin(it.origin)
                        .username(it.username)
                        .password(it.password)
                        .build()
                }.toTypedArray()
                return GeckoResult.fromValue(entries)
            }

            override fun onLoginSave(login: Autocomplete.LoginEntry) {
                val origin = login.origin.orEmpty()
                val username = login.username.orEmpty()
                val password = login.password.orEmpty()
                onFormLoginSubmitted(context, origin, username, password)
            }
        }

    private fun loadAll(context: Context): List<SavedLogin> {
        val raw = AppPrefs.get(context, "saved_logins").getString("items", null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { idx ->
            val obj = array.optJSONObject(idx) ?: return@mapNotNull null
            val origin = obj.optString("origin")
            val username = obj.optString("username")
            val password = obj.optString("password")
            if (origin.isNotBlank() && password.isNotBlank()) SavedLogin(origin, username, password) else null
        }
    }

    private fun persist(context: Context, list: List<SavedLogin>) {
        val array = JSONArray()
        for (item in list) {
            array.put(
                JSONObject()
                    .put("origin", item.origin)
                    .put("username", item.username)
                    .put("password", item.password)
            )
        }
        AppPrefs.get(context, "saved_logins").edit().putString("items", array.toString()).apply()
    }
}

@Composable
internal fun LoginSuggestionBar(
    suggestions: List<SavedLogin>,
    modifier: Modifier = Modifier,
    onSelect: (SavedLogin) -> Unit,
    onDelete: (SavedLogin) -> Unit
) {
    if (suggestions.isEmpty()) return
    var confirmDelete by remember { mutableStateOf<SavedLogin?>(null) }
    confirmDelete?.let { target ->
        val label = target.username.ifBlank { target.origin }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete saved password?") },
            text = { Text("Remove saved login for $label?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (item in suggestions) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.clickable { onSelect(item) }
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                item.username.ifBlank { "(no username)" },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "••••••••",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { confirmDelete = suggestions.firstOrNull() }) {
                Icon(Icons.Default.Close, contentDescription = "Delete saved password")
            }
        }
    }
}

