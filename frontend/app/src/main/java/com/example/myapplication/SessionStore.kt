package com.example.myapplication

import android.content.Context

internal data class SavedSession(
    val accessToken: String,
    val expiresAt: String?,
    val userId: String,
    val email: String,
    val name: String? = null,
)

internal class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("running_auth_prefs", Context.MODE_PRIVATE)
    private val names = context.getSharedPreferences("running_names", Context.MODE_PRIVATE)

    fun saveSession(session: SavedSession) {
        prefs.edit()
            .putString(KEY_TOKEN, session.accessToken)
            .putString(KEY_EXPIRES_AT, session.expiresAt)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_NAME, session.name)
            .apply()
    }

    fun loadSession(): SavedSession? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        return SavedSession(
            accessToken = token,
            expiresAt = prefs.getString(KEY_EXPIRES_AT, null),
            userId = userId,
            email = email,
            name = prefs.getString(KEY_NAME, null),
        )
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    // 회원가입 때 입력한 이름을 이메일별로 기억 (로그아웃해도 유지)
    fun saveName(email: String, name: String) {
        names.edit().putString(email.trim().lowercase(), name.trim()).apply()
    }

    fun getName(email: String): String? = names.getString(email.trim().lowercase(), null)

    companion object {
        private const val KEY_TOKEN = "access_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_NAME = "name"
    }
}