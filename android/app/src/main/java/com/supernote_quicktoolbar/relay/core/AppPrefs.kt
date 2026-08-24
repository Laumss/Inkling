package com.supernote_quicktoolbar.relay.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Typed accessors for AIRelay's SharedPreferences file.
 *
 * Deliberately small: query settings (prefill template, multi-reply) live on
 * the phone and are applied there — the plugin sends raw queries. What remains
 * here is the discovered phone endpoint (paired fingerprint + last known
 * address, the reconnect fallback when multicast is quiet) and the LLM bearer
 * token the phone pushes over WS (`relay_config`), which the plugin needs for
 * its direct image/formula HTTP calls.
 */
object AppPrefs {
    private const val FILE = "air_relay_prefs"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Last confirmed phone IP; empty when never paired. */
    fun phoneIp(ctx: Context): String =
        prefs(ctx).getString("phone_ip", "") ?: ""

    fun setPhoneIp(ctx: Context, ip: String) =
        prefs(ctx).edit().putString("phone_ip", ip).apply()

    /** Paired phone's discovery fingerprint; guards against foreign announcements. */
    fun phoneFingerprint(ctx: Context): String =
        prefs(ctx).getString("phone_fingerprint", "") ?: ""

    fun setPhoneFingerprint(ctx: Context, fingerprint: String) =
        prefs(ctx).edit().putString("phone_fingerprint", fingerprint).apply()

    /** RikkaHub web server port (REST + SSE on the same port). */
    fun phonePort(ctx: Context): Int =
        prefs(ctx).getInt("phone_port", 8080)

    fun setPhonePort(ctx: Context, port: Int) =
        prefs(ctx).edit().putInt("phone_port", port).apply()

    /** Forget the paired endpoint and its phone-supplied bearer token. */
    fun clearPhonePairing(ctx: Context) =
        prefs(ctx).edit()
            .remove("phone_ip")
            .remove("phone_fingerprint")
            .remove("phone_port")
            .remove("llm_token")
            .apply()

    /**
     * Bearer token for RikkaHub's web server when its JWT auth is enabled.
     * Empty means no auth — the common LAN setup.
     */
    fun llmToken(ctx: Context): String =
        prefs(ctx).getString("llm_token", "") ?: ""
}
