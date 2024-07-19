package com.toasterofbread.spmp.resources

import com.toasterofbread.spmp.platform.AppContext
import com.toasterofbread.spmp.platform.getUiLanguage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import java.util.MissingResourceException
import kotlin.concurrent.withLock
import nl.adaptivity.xmlutil.serialization.XML

private var _strings: Map<String, String>? = null
private var _string_arrays: Map<String, List<String>>? = null

private val strings: Map<String, String> get() = _strings!!
private val string_arrays: Map<String, List<String>> get() = _string_arrays!!

private val resource_load_lock = ReentrantLock()

@Suppress("BlockingMethodInNonBlockingContext")
fun initResources(language: String, context: AppContext) {
    fun formatText(text: String): String = text.replace("\\\"", "\"").replace("\\'", "'")

    resource_load_lock.withLock {
        if (_strings != null && _string_arrays != null) {
            return
        }

        runBlocking {
            val strs: MutableMap<String, String> = mutableMapOf()
            val str_arrays: MutableMap<String, List<String>> = mutableMapOf()

            fun loadFile(path: String) {
                val stream: InputStream
                try {
                    stream = context.openResourceFile(path)
                }
                catch (e: Throwable) {
                    if (e.javaClass != MissingResourceException::class.java) {
                        throw e
                    }
                    return
                }

                println("Loading resource file at $path")

                val string: String = stream.reader().readText()
                stream.close()

                val strings = XML.decodeFromString(Strings.serializer(), string)
                TODO(strings.toString())

                // val parser: MiniXmlPullParser = MiniXmlPullParser(string.iterator())

                // while (parser.eventType != EventType.END_DOCUMENT) {
                //     try {
                //         if (parser.eventType != EventType.START_TAG) {
                //             parser.next()
                //             continue
                //         }

                //         val key: String? = parser.getAttributeValue("", "name")
                //         if (key != null) {
                //             when (parser.name) {
                //                 "string" -> {
                //                     strs[key] = formatText(parser.nextText())
                //                 }
                //                 "string-array" -> {
                //                     val array = mutableListOf<String>()

                //                     parser.nextTag()
                //                     while (parser.name == "item") {
                //                         array.add(formatText(parser.nextText()))
                //                         parser.nextTag()
                //                     }

                //                     str_arrays[key] = array
                //                 }
                //                 "resources" -> {}
                //                 else -> throw NotImplementedError(parser.name)
                //             }
                //         }

                //         parser.next()
                //     }
                //     catch (e: Throwable) {
                //         throw RuntimeException("Error occurred while processing line ${parser.lineNumber} of $path", e)
                //     }
                // }
            }

            var language_best_match: String? = null
            val language_family = language.split('-', limit = 2).first()

            iterateValuesDirectories(context) { file_language, path ->
                if (file_language == null) {
                    return@iterateValuesDirectories false
                }

                if (file_language == language) {
                    language_best_match = path
                    return@iterateValuesDirectories true
                }

                if (file_language.split('-', limit = 2).first() == language_family) {
                    language_best_match = path
                }

                return@iterateValuesDirectories false
            }

            loadFile("values/strings.xml")
            if (language_best_match != null) {
                loadFile("$language_best_match/strings.xml")
            }

            _strings = strs
            _string_arrays = str_arrays
        }
    }
}

@Serializable
private data class Strings(val string: List<StringItem>, val string_array: List<StringArray>)

@Serializable
private data class StringItem(val name: String, val content: String)

@Serializable
private data class StringArray(val name: String, val item: List<String>)

fun getString(key: String): String = strings[key] ?: throw NotImplementedError(key)
fun getStringOrNull(key: String): String? = _strings?.get(key)
fun getStringTODO(temp_string: String): String = "$temp_string // TODO" // String to be localised
fun getStringArray(key: String): List<String> = string_arrays[key] ?: throw NotImplementedError(key)

fun getStringSafe(key: String, context: AppContext): String {
    resource_load_lock.withLock {
        if (_strings == null) {
            initResources(context.getUiLanguage(), context)
        }
        return strings[key] ?: throw NotImplementedError(key)
    }
}

fun getStringArraySafe(key: String, context: AppContext): List<String> {
    resource_load_lock.withLock {
        if (_string_arrays == null) {
            initResources(context.getUiLanguage(), context)
        }
        return string_arrays[key] ?: throw NotImplementedError(key)
    }
}

inline fun iterateValuesDirectories(context: AppContext, action: (language: String?, path: String) -> Boolean) {
    for (file in context.listResourceFiles("") ?: emptyList()) {
        if (!file.startsWith("values")) {
            continue
        }

        val file_language: String? = if (file.length == 6) null else file.substring(7)
        if (action(file_language, file)) {
            break
        }
    }
}
