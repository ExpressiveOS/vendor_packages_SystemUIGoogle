/*
 * Copyright (C) 2026 The ExpressiveOS Project
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

package com.google.android.systemui.power.batteryhealth;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import lineageos.app.LineageContextConstants;

/**
 * Google's own battery HAL, vendor.google.google_battery (IGoogleBattery V5).
 *
 * Phones that run Google's stock vendor image (the Pixel 11 series) have no
 * LineageOS health HAL, so LineageOS charging control is not there. Stock
 * SystemUIGoogle sets the 80 % limit on these phones with one call,
 * IGoogleBattery.setChargingPolicy(int) (transaction 23; 1 = default,
 * 2 = long life), and applies it again at every boot because the HAL does not
 * keep it. The stock vendor policy lets platform_app (SystemUI) call the HAL.
 *
 * Transaction code and values: Google's stock SystemUIGoogle, CP4A.261105.040.D1
 * (ChargeLimitController.updateChargingPolicy -> IGoogleBattery$Stub$Proxy).
 */
final class GoogleBatteryHal {
    private static final String TAG = "EosHealthService";

    private static final String DESCRIPTOR = "vendor.google.google_battery.IGoogleBattery";
    private static final String INSTANCE = DESCRIPTOR + "/default";
    private static final int TRANSACTION_SET_CHARGING_POLICY = 23;

    /** Settings.Secure key Settings Intelligence writes: 1 = "Limit to 80%" on. */
    static final String CHARGE_OPTIMIZATION_MODE = "charge_optimization_mode";

    private GoogleBatteryHal() {}

    /** True when LineageOS charging control is running (phones built with its health HAL). */
    static boolean hasLineageHealth() {
        return ServiceManager.checkService(LineageContextConstants.LINEAGE_HEALTH_INTERFACE) != null;
    }

    /** True when the device declares Google's battery HAL. */
    static boolean isDeclared() {
        return ServiceManager.isDeclared(INSTANCE);
    }

    static boolean setChargingPolicy(int policy) {
        if (!isDeclared()) {
            Log.w(TAG, "GoogleBattery: " + INSTANCE + " not declared");
            return false;
        }
        final long token = Binder.clearCallingIdentity();
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            final IBinder hal = Binder.allowBlocking(ServiceManager.waitForDeclaredService(INSTANCE));
            if (hal == null) {
                Log.w(TAG, "GoogleBattery: " + INSTANCE + " not running");
                return false;
            }
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(policy);
            if (!hal.transact(TRANSACTION_SET_CHARGING_POLICY, data, reply, 0)) {
                Log.w(TAG, "GoogleBattery: setChargingPolicy not implemented");
                return false;
            }
            reply.readException();
            Log.i(TAG, "GoogleBattery: setChargingPolicy(" + policy + ") done");
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "GoogleBattery: setChargingPolicy(" + policy + ") failed", e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Applies the saved switch state again, as stock does at boot. */
    static void reapply(Context context) {
        if (hasLineageHealth()) {
            return; // LineageOS keeps its own settings across reboots.
        }
        final int mode = Settings.Secure.getInt(context.getContentResolver(),
                CHARGE_OPTIMIZATION_MODE, 0);
        if (mode == 1) {
            setChargingPolicy(HealthService.POLICY_LONG_LIFE);
        }
    }
}
