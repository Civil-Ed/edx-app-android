package org.edx.mobile.module.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.edx.mobile.model.api.RemoteConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteFeaturePrefs @Inject constructor(@ApplicationContext context: Context) {
    private val pref: PrefManager = PrefManager(context, PrefManager.Pref.REMOTE_FEATURES)

    fun setRemoteConfig(remoteConfig: RemoteConfig) {
        pref.put(PrefManager.Key.VALUE_PROP, remoteConfig.isValuePropEnabled)
    }

    fun isValuePropEnabled(): Boolean = pref.getBoolean(PrefManager.Key.VALUE_PROP, false)
}
