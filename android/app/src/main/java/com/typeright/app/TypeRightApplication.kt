package com.typeright.app

import android.app.Application
import com.typeright.app.analytics.AnalyticsFactory
import com.typeright.keyboard.TypeRightServices

/**
 * Installs the analytics sink for the whole process.
 *
 * The IME service and the host app live in the same process, so this runs before either of them and the keyboard
 * can report through `TypeRightServices.analytics` without depending on the app module.
 */
class TypeRightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TypeRightServices.get(this).analytics = AnalyticsFactory.create(this)
    }
}
