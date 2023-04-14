package com.spectre7.spmp.api

import com.beust.klaxon.*
import com.spectre7.spmp.platform.ProjectPreferences
import com.spectre7.spmp.model.Settings
import com.spectre7.utils.getString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.text.StringSubstitutor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse

fun <T> Result.Companion.failure(response: Response): Result<T> {
    return failure(RuntimeException("${response.message}: ${response.body?.string()} (${response.code})"))
}
fun <I, O> Result<I>.cast(): Result<O> {
    if (isSuccess) {
        return Result.success(getOrNull() as O)
    }
    else {
        return Result.failure(exceptionOrNull()!!)
    }
}
val <T> Result<T>.data get() = getOrThrowHere()

fun <T> Result<T>.getOrThrowHere(): T {
    if (isFailure) {
        throw Exception(exceptionOrNull()!!)
    }
    return getOrThrow()
}

fun <T> Result<T>.getOrReport(error_key: String): T? {
    return fold(
        {
            it
        },
        {
            TODO()
//            SpMp.error_manager.onError(error_key, it)
//            null
        }
    )
}

class DataApi {

    companion object {
        private val client: OkHttpClient = OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1)).build()
        val user_agent: String get() = getString("ytm_user_agent")

        private val enum_converter = object : Converter {
            override fun canConvert(cls: Class<*>): Boolean {
                return cls.isEnum
            }

            override fun fromJson(jv: JsonValue): Enum<*>? {
                val cls = jv.propertyClass
                if (cls !is Class<*> || !cls.isEnum) {
                    throw IllegalArgumentException("Could not convert $jv to enum")
                }

                val name = jv.inside as String? ?: return null
                val field = cls.declaredFields
                    .firstOrNull { it.name == name || it.getAnnotation(Json::class.java)?.name == name }
                    ?: throw IllegalArgumentException("Could not find enum value for $name")

                return field.get(null) as Enum<*>
            }

            override fun toJson(value: Any): String {
                return "\"${(value as Enum<*>).name}\""
            }
        }
        val klaxon: Klaxon get() = Klaxon().converter(enum_converter)

        enum class YoutubeiContextType {
            BASE,
            ALT,
            ANDROID;

            fun getContext(): JsonObject {
                return when (this) {
                    BASE -> youtubei_context
                    ALT -> youtubei_context_alt
                    ANDROID -> youtubei_context_android
                }
            }
        }

        private lateinit var youtubei_context: JsonObject
        private lateinit var youtubei_context_alt: JsonObject
        private lateinit var youtubei_context_android: JsonObject

        private lateinit var youtubei_headers: Headers

        private val prefs_change_listener = object : ProjectPreferences.Listener {
            override fun onChanged(prefs: ProjectPreferences, key: String) {
                when (key) {
                    Settings.KEY_YTM_AUTH.name -> updateYtmHeaders()
                    Settings.KEY_LANG_DATA.name -> updateYtmContext()
                }
            }
        }

        fun request(request: Request, allow_fail_response: Boolean = false): Result<Response> {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful || allow_fail_response) {
                    return Result.success(response)
                }
                return Result.failure(response)
            }
            catch (e: Throwable) {
                return Result.failure(e)
            }
        }

        fun Response.getStream(): InputStream {
            return GZIPInputStream(body!!.byteStream())
        }

        private fun updateYtmContext() {
            val context_substitutor = StringSubstitutor(
                mapOf(
                    "user_agent" to user_agent,
                    "hl" to SpMp.data_language
                ),
                "\${", "}"
            )

            youtubei_context = klaxon.parseJsonObject(
                context_substitutor.replace(getString("ytm_context")).reader()
            )
            youtubei_context_alt = klaxon.parseJsonObject(
                context_substitutor.replace(getString("ytm_context_alt")).reader()
            )
            youtubei_context_android = klaxon.parseJsonObject(
                context_substitutor.replace(getString("ytm_context_android")).reader()
            )
        }

        private fun updateYtmHeaders() {
            val headers_builder = Headers.Builder().add("user-agent", user_agent)

            val ytm_auth = YoutubeMusicAuthInfo(Settings.get(Settings.KEY_YTM_AUTH))
            if (ytm_auth.initialised) {
                headers_builder["cookie"] = ytm_auth.cookie
                for (header in ytm_auth.headers) {
                    headers_builder[header.key] = header.value
                }
            }
            else {
                TODO()
//                val headers = MainActivity.resources.getStringArray(R.array.ytm_headers)
//                var i = 0
//                while (i < headers.size) {
//                    val key = headers[i++]
//                    val value = headers[i++]
//                    headers_builder[key] = value
//                }
            }

            headers_builder["accept-encoding"] = "gzip, deflate"
            headers_builder["content-encoding"] = "gzip"

            youtubei_headers = headers_builder.build()
        }

        fun initialise() {
            updateYtmContext()
            updateYtmHeaders()

            Settings.prefs.addListener(prefs_change_listener)

            NewPipe.init(object : Downloader() {
                override fun execute(request: NewPipeRequest): NewPipeResponse {
                    val url = request.url()
                    val request_body: RequestBody? = request.dataToSend()?.let {
                        it.toRequestBody(null, 0, it.size)
                    }

                    val request_builder = Request.Builder()
                        .method(request.httpMethod(), request_body).url(url)
                        .addHeader("User-Agent", user_agent)

                    for ((headerName, headerValueList) in request.headers()) {
                        if (headerValueList.size > 1) {
                            request_builder.removeHeader(headerName)
                            for (headerValue in headerValueList) {
                                request_builder.addHeader(headerName, headerValue)
                            }
                        } else if (headerValueList.size == 1) {
                            request_builder.header(headerName, headerValueList[0])
                        }
                    }

                    val response = request(request_builder.build(), true).getOrThrowHere()
                    if (response.code == 429) {
                        response.close()
                        throw ReCaptchaException("reCaptcha Challenge requested", url)
                    }

                    return NewPipeResponse(response.code,
                        response.message,
                        response.headers.toMultimap(),
                        response.body?.string(),
                        response.request.url.toString()
                    )
                }
            })
        }

        internal fun Request.Builder.addYtHeaders(plain: Boolean = false): Request.Builder {
            if (plain) {
                for (header in listOf("accept-language", "user-agent", "accept-encoding", "content-encoding")) {
                    header(header, youtubei_headers[header]!!)
                }
            }
            else {
                headers(youtubei_headers)
            }
            return this
        }

        internal fun Request.Builder.ytUrl(endpoint: String): Request.Builder {
            val joiner = if (endpoint.contains('?')) '&' else '?'
            return url("https://music.youtube.com$endpoint${joiner}prettyPrint=false")
        }

        internal fun getYoutubeiRequestBody(body: String? = null, context: YoutubeiContextType = YoutubeiContextType.BASE): RequestBody {
            val final_body = if (body != null) context.getContext() + klaxon.parseJsonObject(body.reader()) else context.getContext()
            return klaxon.toJsonString(final_body).toRequestBody("application/json".toMediaType())
        }
    }
}

//fun getApiAuthHeaders(callback: (header: Headers) -> Unit) {
//    getAuthToken { token ->
//        callback(Headers.Builder().add("Authorization", "Bearer $token").build())
//    }
//}
//
//fun getAuthToken(callback: (String) -> Unit) {
//    val auth_state = MainActivity.auth_state
//    val auth_service = MainActivity.auth_service
//
//    fun onFinished() {
//        auth_state.performActionWithFreshTokens(auth_service) { token: String?, id: String?, exception: AuthorizationException? ->
//            if (exception != null) {
//                throw exception
//            }
//            callback(token!!)
//        }
//    }
//
//    fun requestToken() {
//        auth_service.performTokenRequest(
//            auth_state.lastAuthorizationResponse!!.createTokenExchangeRequest()
//        ) { response, exception ->
//            if (exception != null) {
//                throw exception
//            }
//
//            auth_state.update(response, null)
//            MainActivity.saveAuthState()
//
//            onFinished()
//        }
//    }
//
//    if (auth_state.refreshToken == null) {
//        MainActivity.startAuthLogin { exception ->
//            if (exception != null) {
//                throw exception
//            }
//            requestToken()
//        }
//        return
//    }
//
//    if (auth_state.needsTokenRefresh) {
//        requestToken()
//        return
//    }
//
//    onFinished()
//}