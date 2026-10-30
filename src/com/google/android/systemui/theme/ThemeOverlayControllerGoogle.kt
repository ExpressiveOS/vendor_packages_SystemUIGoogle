/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.systemui.theme

import android.app.ActivityManager
import android.app.UiModeManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.SystemPropertiesHelper
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.theme.ThemeOverlayApplier
import com.android.systemui.theme.ThemeOverlayController
import com.android.systemui.user.utils.UserScopedService
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.settings.SecureSettings
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject

@SysUISingleton
class ThemeOverlayControllerGoogle
@Inject
constructor(
    private val context: Context,
    private val broadcastDispatcher: BroadcastDispatcher,
    @Background bgHandler: Handler,
    @Main private val mainExecutor: Executor,
    @Background bgExecutor: Executor,
    themeOverlayApplier: ThemeOverlayApplier,
    private val secureSettings: SecureSettings,
    wallpaperManager: WallpaperManager,
    userManager: UserManager,
    deviceProvisionedController: DeviceProvisionedController,
    private val userTracker: UserTracker,
    dumpManager: DumpManager,
    featureFlags: FeatureFlags,
    @Main resources: Resources,
    wakefulnessLifecycle: WakefulnessLifecycle,
    javaAdapter: JavaAdapter,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    uiModeManager: UiModeManager,
    uiModeManagerProvider: UserScopedService<UiModeManager>,
    activityManager: ActivityManager,
    systemPropertiesHelper: SystemPropertiesHelper,
    configurationController: ConfigurationController,
) :
    ThemeOverlayController(
        context,
        broadcastDispatcher,
        bgHandler,
        mainExecutor,
        bgExecutor,
        themeOverlayApplier,
        secureSettings,
        wallpaperManager,
        userManager,
        deviceProvisionedController,
        userTracker,
        dumpManager,
        featureFlags,
        resources,
        wakefulnessLifecycle,
        javaAdapter,
        keyguardTransitionInteractor,
        uiModeManager,
        uiModeManagerProvider,
        activityManager,
        systemPropertiesHelper,
        configurationController,
    ) {
    private val configurationChangedListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onThemeChanged() {
                if (userTracker.userId != 0) {
                    return
                }
                try {
                    val bootColors = getBootColors()
                    for ((i, color) in bootColors.withIndex()) {
                        mSystemPropertiesHelper.set(
                            "persist.bootanim.color${i + 1}",
                            color.toString(),
                        )
                        Log.d(
                            TAG,
                            "Writing boot animation colors ${i + 1}: ${Integer.toHexString(color)}",
                        )
                    }
                } catch (unused: RuntimeException) {
                    Log.w(
                        TAG,
                        "Cannot set sysprop. Look for 'init' and 'dmesg' logs for more info.",
                    )
                }
            }
        }

    init {
        configurationController.addCallback(configurationChangedListener)
        broadcastDispatcher.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    configurationChangedListener.onThemeChanged()
                }
            },
            IntentFilter(ACTION_BOOTANIM_STYLE_CHANGED),
            mainExecutor,
            UserHandle.ALL,
        )
        val bootColors = getBootColors()
        for ((i, color) in bootColors.withIndex()) {
            Log.d(TAG, "Boot animation colors ${i + 1}: $color")
        }
    }

    override fun start() {
        super.start()
        // Also once per boot: the listener only writes for user 0, so a holiday that ended
        // while another user was in the foreground left its colors behind
        configurationChangedListener.onThemeChanged()
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        super.dump(pw, args)
        pw.println("ThemeOverlayControllerGoogle: yes")
    }

    fun getBootColors(): IntArray {
        return getHolidayBootColors()
            ?: intArrayOf(
                context.getColor(com.android.internal.R.color.system_brand_a_dark),
                context.getColor(com.android.internal.R.color.system_brand_c_dark),
                context.getColor(com.android.internal.R.color.system_brand_d_dark),
                context.getColor(com.android.internal.R.color.system_brand_b_dark),
            )
    }

    /**
     * The holiday's four petal colors while Evolver's holiday themes have the holiday boot
     * animation on, else null. The setting is "RRGGBB,RRGGBB,RRGGBB,RRGGBB" for user 0; anything
     * else is ignored.
     */
    private fun getHolidayBootColors(): IntArray? {
        val value =
            secureSettings.getStringForUser(HOLIDAY_BOOTANIM_SETTING, UserHandle.USER_SYSTEM)
        if (value.isNullOrEmpty()) {
            return null
        }
        if (!HOLIDAY_BOOT_COLORS.matches(value)) {
            Log.w(TAG, "Ignoring malformed $HOLIDAY_BOOTANIM_SETTING: $value")
            return null
        }
        return value.split(',').map { it.toInt(16) or 0xFF000000.toInt() }.toIntArray()
    }

    companion object {
        private const val TAG = "ThemeOverlayController"
        private const val ACTION_BOOTANIM_STYLE_CHANGED =
            "org.evolution.intent.action.BOOTANIM_STYLE_CHANGED"
        private const val HOLIDAY_BOOTANIM_SETTING = "expressiveos_holiday_bootanim"
        private val HOLIDAY_BOOT_COLORS = Regex("[0-9A-Fa-f]{6}(,[0-9A-Fa-f]{6}){3}")
    }
}
