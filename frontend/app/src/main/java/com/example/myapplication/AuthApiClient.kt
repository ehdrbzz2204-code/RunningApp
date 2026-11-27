package com.example.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

internal sealed interface AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>
    data class Error(val message: String, val code: Int? = null, val errorKey: String? = null) : AuthResult<Nothing>
}

internal data class AuthUser(
    val id: String,
    val email: String,
)

internal class AuthApiClient(
    // Android 에뮬레이터에서 PC의 127.0.0.1:3000에 접근할 때는 10.0.2.2 사용
    private val baseUrl: String = "http://127.0.0.1:3000",) {
    suspend fun register(email: String, password: String): AuthResult<AuthUser> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("email", email)
                .put("password", password)
                .toString()

            val response = requestJson(
                path = "/auth/register",
                method = "POST",
                body = payload,
            )

            if (response.statusCode == 201) {
                val json = JSONObject(response.rawBody.orEmpty())
                val userJson = json.optJSONObject("user")
                    ?: return@withContext AuthResult.Error("회원가입 응답 형식이 올바르지 않아요.", response.statusCode)
                AuthResult.Success(
                    AuthUser(
                        id = userJson.optString("id"),
                        email = userJson.optString("email"),
                    )
                )
            } else {
                val errorKey = parseErrorCode(response.rawBody)
                val message = when (response.statusCode) {
                    400 -> "이메일 형식이나 비밀번호(15~128자)를 확인해 주세요."
                    409 -> "이미 가입된 이메일이에요."
                    429 -> "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."
                    else -> response.networkError ?: "회원가입에 실패했어요. (${response.statusCode})"
                }
                AuthResult.Error(message, response.statusCode, errorKey)
            }
        }

    suspend fun login(email: String, password: String): AuthResult<SavedSession> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("email", email)
                .put("password", password)
                .toString()

            val response = requestJson(
                path = "/auth/login",
                method = "POST",
                body = payload,
            )

            if (response.statusCode == 200) {
                val json = JSONObject(response.rawBody.orEmpty())
                val token = json.optString("accessToken")
                val userJson = json.optJSONObject("user")
                if (token.isBlank() || userJson == null) {
                    return@withContext AuthResult.Error("로그인 응답 형식이 올바르지 않아요.", response.statusCode)
                }
                AuthResult.Success(
                    SavedSession(
                        accessToken = token,
                        expiresAt = json.optString("expiresAt").takeIf { it.isNotBlank() },
                        userId = userJson.optString("id"),
                        email = userJson.optString("email"),
                    )
                )
            } else {
                val errorKey = parseErrorCode(response.rawBody)
                val message = when (response.statusCode) {
                    400 -> "이메일과 비밀번호 형식을 확인해 주세요."
                    401 -> "이메일 또는 비밀번호가 일치하지 않아요."
                    429 -> "로그인 시도가 너무 많아요. 잠시 후 다시 시도해 주세요."
                    else -> response.networkError ?: "로그인에 실패했어요. (${response.statusCode})"
                }
                AuthResult.Error(message, response.statusCode, errorKey)
            }
        }

    suspend fun me(accessToken: String): AuthResult<AuthUser> =
        withContext(Dispatchers.IO) {
            val response = requestJson(
                path = "/auth/me",
                method = "GET",
                bearerToken = accessToken,
            )
            if (response.statusCode == 200) {
                val json = JSONObject(response.rawBody.orEmpty())
                val userJson = json.optJSONObject("user")
                    ?: return@withContext AuthResult.Error("계정 정보를 읽을 수 없어요.", response.statusCode)
                AuthResult.Success(
                    AuthUser(
                        id = userJson.optString("id"),
                        email = userJson.optString("email"),
                    )
                )
            } else {
                AuthResult.Error(
                    message = if (response.statusCode == 401) "세션이 만료되었어요. 다시 로그인해 주세요."
                    else response.networkError ?: "계정 확인에 실패했어요.",
                    code = response.statusCode,
                    errorKey = parseErrorCode(response.rawBody),
                )
            }
        }

    suspend fun logout(accessToken: String): AuthResult<Unit> =
        withContext(Dispatchers.IO) {
            val response = requestJson(
                path = "/auth/logout",
                method = "POST",
                bearerToken = accessToken,
            )
            if (response.statusCode in 200..299 || response.statusCode == 401) {
                AuthResult.Success(Unit)
            } else {
                AuthResult.Error(
                    message = response.networkError ?: "로그아웃 요청에 실패했어요.",
                    code = response.statusCode,
                    errorKey = parseErrorCode(response.rawBody),
                )
            }
        }

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
