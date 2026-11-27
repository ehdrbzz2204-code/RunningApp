package com.example.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

internal class RunnerApiClient(private val baseUrl:String="http://127.0.0.1:3000") {
    suspend fun accountJson(token: String, path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val response = requestJson(path, method, body?.toString(), token)
            if (response.statusCode !in 200..299) {
                throw java.io.IOException(response.networkError ?: when (response.statusCode) {
                    401 -> "로그인 세션이 만료됐어요. 다시 로그인해 주세요."
                    else -> "요청 실패: " + (parseErrorCode(response.rawBody) ?: response.statusCode)
                })
            }
            JSONObject(response.rawBody.orEmpty())
        }

    private data class RawHttpResponse(
        val statusCode: Int,
        val rawBody: String?,
        val networkError: String? = null,
    )

    private fun requestJson(
        path: String,
        method: String,
        body: String? = null,
        bearerToken: String? = null,
    ): RawHttpResponse {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(baseUrl.trimEnd('/') + path)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                }
                if (!bearerToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }

            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }

            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.let { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }
            RawHttpResponse(statusCode = code, rawBody = text)
        } catch (e: Exception) {
            android.util.Log.e("AuthApi", "request to $path failed", e)
            RawHttpResponse(statusCode = -1, rawBody = null,
                networkError = "서버에 연결할 수 없어요. 네트워크나 서버 상태를 확인해 주세요.")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseErrorCode(rawBody: String?): String? {
        if (rawBody.isNullOrBlank()) return null
        return try {
            JSONObject(rawBody).optString("error").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
