package com.x1colegal.browserengine

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

internal class AppPrefs private constructor(
    private val file: File,
    legacyFile: File?
) : SharedPreferences {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val data = LinkedHashMap<String, Any?>()

    init {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            loadFromFile(file, CP1252)
        } else if (legacyFile != null && legacyFile.exists()) {
            loadFromFile(legacyFile, Charsets.UTF_8)
            persistLocked()
            runCatching { legacyFile.delete() }
        }
    }

    private fun loadFromFile(source: File, charset: Charset) {
        runCatching {
            FileInputStream(source).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, charset.name())
                var event = parser.eventType
                var inMap = false
                var currentSetKey: String? = null
                var currentSet: MutableSet<String>? = null
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "map" -> inMap = true
                                "set" -> if (inMap) {
                                    currentSetKey = parser.getAttributeValue(null, "name")
                                    currentSet = LinkedHashSet()
                                }
                                "string" -> {
                                    val name = parser.getAttributeValue(null, "name")
                                    val text = parser.nextText()
                                    if (currentSet != null) {
                                        currentSet.add(text)
                                    } else if (inMap && name != null) {
                                        data[name] = text
                                    }
                                }
                                "boolean" -> if (inMap) {
                                    val name = parser.getAttributeValue(null, "name")
                                    val value = parser.getAttributeValue(null, "value")
                                    if (name != null && value != null) data[name] = value.toBoolean()
                                }
                                "int" -> if (inMap) {
                                    val name = parser.getAttributeValue(null, "name")
                                    val value = parser.getAttributeValue(null, "value")?.toIntOrNull()
                                    if (name != null && value != null) data[name] = value
                                }
                                "long" -> if (inMap) {
                                    val name = parser.getAttributeValue(null, "name")
                                    val value = parser.getAttributeValue(null, "value")?.toLongOrNull()
                                    if (name != null && value != null) data[name] = value
                                }
                                "float" -> if (inMap) {
                                    val name = parser.getAttributeValue(null, "name")
                                    val value = parser.getAttributeValue(null, "value")?.toFloatOrNull()
                                    if (name != null && value != null) data[name] = value
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "set" && currentSetKey != null && currentSet != null) {
                                data[currentSetKey] = currentSet
                                currentSetKey = null
                                currentSet = null
                            } else if (parser.name == "map") {
                                inMap = false
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        }
    }

    private fun persistLocked(): Boolean {
        val dir = file.parentFile ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false
        val temp = File(dir, "${file.name}.tmp")
        return runCatching {
            FileOutputStream(temp).use { out ->
                val serializer = Xml.newSerializer()
                serializer.setOutput(out, CP1252.name())
                serializer.startDocument(CP1252.name(), true)
                serializer.text("\n")
                serializer.startTag(null, "map")
                serializer.text("\n")
                for ((key, value) in data) {
                    when (value) {
                        is String -> {
                            serializer.text("    ")
                            serializer.startTag(null, "string")
                            serializer.attribute(null, "name", key)
                            serializer.text(value)
                            serializer.endTag(null, "string")
                            serializer.text("\n")
                        }
                        is Boolean -> {
                            serializer.text("    ")
                            serializer.startTag(null, "boolean")
                            serializer.attribute(null, "name", key)
                            serializer.attribute(null, "value", value.toString())
                            serializer.endTag(null, "boolean")
                            serializer.text("\n")
                        }
                        is Int -> {
                            serializer.text("    ")
                            serializer.startTag(null, "int")
                            serializer.attribute(null, "name", key)
                            serializer.attribute(null, "value", value.toString())
                            serializer.endTag(null, "int")
                            serializer.text("\n")
                        }
                        is Long -> {
                            serializer.text("    ")
                            serializer.startTag(null, "long")
                            serializer.attribute(null, "name", key)
                            serializer.attribute(null, "value", value.toString())
                            serializer.endTag(null, "long")
                            serializer.text("\n")
                        }
                        is Float -> {
                            serializer.text("    ")
                            serializer.startTag(null, "float")
                            serializer.attribute(null, "name", key)
                            serializer.attribute(null, "value", value.toString())
                            serializer.endTag(null, "float")
                            serializer.text("\n")
                        }
                        is Set<*> -> {
                            serializer.text("    ")
                            serializer.startTag(null, "set")
                            serializer.attribute(null, "name", key)
                            serializer.text("\n")
                            for (item in value) {
                                if (item is String) {
                                    serializer.text("        ")
                                    serializer.startTag(null, "string")
                                    serializer.text(item)
                                    serializer.endTag(null, "string")
                                    serializer.text("\n")
                                }
                            }
                            serializer.text("    ")
                            serializer.endTag(null, "set")
                            serializer.text("\n")
                        }
                    }
                }
                serializer.endTag(null, "map")
                serializer.text("\n")
                serializer.endDocument()
            }
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }.getOrElse {
            runCatching { temp.delete() }
            false
        }
    }

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { LinkedHashMap(data) }

    override fun getString(key: String?, defValue: String?): String? = synchronized(lock) {
        (data[key] as? String) ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(lock) {
            val value = data[key] as? Set<String> ?: return@synchronized defValues
            LinkedHashSet(value)
        }

    override fun getInt(key: String?, defValue: Int): Int = synchronized(lock) {
        (data[key] as? Int) ?: defValue
    }

    override fun getLong(key: String?, defValue: Long): Long = synchronized(lock) {
        (data[key] as? Long) ?: defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float = synchronized(lock) {
        (data[key] as? Float) ?: defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(lock) {
        (data[key] as? Boolean) ?: defValue
    }

    override fun contains(key: String?): Boolean = synchronized(lock) { data.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.remove(listener)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = values?.let { LinkedHashSet(it) }
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = REMOVED
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
        }

        override fun commit(): Boolean {
            val changed = ArrayList<String>()
            val ok = synchronized(lock) {
                if (clearRequested) {
                    if (data.isNotEmpty()) {
                        changed.addAll(data.keys)
                        data.clear()
                    }
                    clearRequested = false
                }
                for ((key, value) in pending) {
                    if (value === REMOVED || value == null) {
                        if (data.containsKey(key)) {
                            data.remove(key)
                            if (key !in changed) changed.add(key)
                        }
                    } else if (data[key] != value) {
                        data[key] = value
                        if (key !in changed) changed.add(key)
                    }
                }
                pending.clear()
                persistLocked()
            }
            notifyListeners(changed)
            return ok
        }

        override fun apply() {
            commit()
        }
    }

    private fun notifyListeners(changed: List<String>) {
        if (changed.isEmpty() || listeners.isEmpty()) return
        val dispatch = Runnable {
            for (key in changed) {
                for (listener in listeners) {
                    listener.onSharedPreferenceChanged(this, key)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch.run()
        else mainHandler.post(dispatch)
    }

    companion object {
        private val CP1252: Charset = Charset.forName("windows-1252")
        private val REMOVED = Any()
        private val instances = ConcurrentHashMap<String, AppPrefs>()

        fun get(context: Context, name: String): SharedPreferences {
            val app = context.applicationContext
            return instances.getOrPut(name) {
                val prefsDir = File(app.filesDir, "prefs")
                val target = File(prefsDir, "$name.xml")
                val legacy = app.filesDir.parentFile?.let { File(File(it, "shared_prefs"), "$name.xml") }
                AppPrefs(target, legacy)
            }
        }
    }
}

