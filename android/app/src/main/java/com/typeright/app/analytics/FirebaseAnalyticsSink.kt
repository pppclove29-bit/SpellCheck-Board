package com.typeright.app.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.typeright.keyboard.analytics.Analytics
import com.typeright.keyboard.analytics.AnalyticsEvent
import com.typeright.keyboard.analytics.UserProperty

private const val TAG = "TypeRightAnalytics"

/**
 * Firebase Analytics sink.
 *
 * Firebase needs `app/google-services.json`, which is not in the repo. Without it [FirebaseApp] has no default
 * options, so [create] falls back to [Analytics.None] and the app runs exactly as before — no crash, no events.
 */
object AnalyticsFactory {
    fun create(context: Context): Analytics {
        val configured = runCatching { FirebaseApp.getInstance() }.isSuccess
        if (!configured) {
            Log.i(TAG, "Firebase not configured (no google-services.json): analytics disabled")
            return Analytics.None
        }
        return runCatching { FirebaseAnalyticsSink(FirebaseAnalytics.getInstance(context)) }
            .getOrElse {
                Log.w(TAG, "Firebase Analytics unavailable; continuing without analytics", it)
                Analytics.None
            }
    }
}

private class FirebaseAnalyticsSink(private val firebase: FirebaseAnalytics) : Analytics {

    override fun log(event: AnalyticsEvent) {
        val bundle = Bundle(event.params.size)
        for ((key, value) in event.params) {
            when (value) {
                is Int -> bundle.putLong(key, value.toLong())
                is Long -> bundle.putLong(key, value)
                is Double -> bundle.putDouble(key, value)
                // Only enum keys and booleans reach here; AnalyticsEventTest keeps user text out.
                else -> bundle.putString(key, value.toString())
            }
        }
        runCatching { firebase.logEvent(event.name, bundle) }
    }

    override fun setUserProperty(property: UserProperty, value: String) {
        runCatching { firebase.setUserProperty(property.key, value) }
    }
}
