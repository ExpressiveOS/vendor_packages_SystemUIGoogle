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

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import lineageos.health.HealthInterface;
import lineageos.providers.LineageSettings;

/**
 * Battery health service that Settings Intelligence binds to for
 * Settings > Battery > Charging optimisation and battery health telemetry.
 *
 * Settings Intelligence binds com.android.systemui /
 * com.google.android.systemui.power.batteryhealth.HealthService and calls
 * getHealthData (transaction 1), getHealthDataWithAlgo (transaction 5), and
 * setChargingPolicy (transaction 6). On setChargingPolicy, it saves the switch
 * state only if the call returns true. Without this service the bind never
 * connects and the switch turns itself off again.
 *
 * Google's own implementation is not in our SystemUIGoogle source, so this one
 * maps the policy onto LineageOS charging control where the device has
 * LineageOS's health HAL (vendor.lineage.health.IChargingControl, e.g. the
 * Pixel 7 Pro). Phones on Google's stock vendor image (the Pixel 11 series)
 * have no LineageOS health HAL; there policies and battery health telemetry go
 * to Google's own battery HAL, as on stock (see GoogleBatteryHal).
 *
 * The settings are written directly: LineageOS's ChargingControlController
 * watches them and applies the change. Its own binder setters write the same
 * settings without clearing the caller's identity, so a call from SystemUI
 * fails in LineageSettingsProvider ("Package android does not belong to <uid>").
 */
public class HealthService extends Service {
    private static final String TAG = "EosHealthService";

    private static final String DESCRIPTOR =
            "com.google.android.systemui.power.batteryhealth.IHealthService";
    private static final String SETTINGS_INTELLIGENCE =
            "com.google.android.settings.intelligence";
    private static final String TURBO = "com.google.android.apps.turbo";

    // Transaction codes from stock SystemUIGoogle HealthService$binder$1 (CP4A.261105.040.D1).
    private static final int TRANSACTION_GET_HEALTH_DATA = 1;
    private static final int TRANSACTION_REGISTER_HEALTH_LISTENER = 2;
    private static final int TRANSACTION_UNREGISTER_HEALTH_LISTENER = 3;
    private static final int TRANSACTION_GET_HEALTH_DATA_WITH_ALGO = 5;
    private static final int TRANSACTION_SET_CHARGING_POLICY = 6;

    // android.hardware.health.BatteryChargingPolicy
    static final int POLICY_DEFAULT = 1;
    static final int POLICY_LONG_LIFE = 2;

    private static final int CHARGE_LIMIT_PERCENT = 80;

    private final IBinder mBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(DESCRIPTOR);
            enforceCaller();

            if (code == TRANSACTION_GET_HEALTH_DATA) {
                final int[] healthData = GoogleBatteryHal.getHealthData();
                if (reply != null) {
                    reply.writeNoException();
                    writeHealthData(reply, healthData);
                }
                return true;
            }

            if (code == TRANSACTION_REGISTER_HEALTH_LISTENER
                    || code == TRANSACTION_UNREGISTER_HEALTH_LISTENER) {
                data.readStrongBinder();
                return true;
            }

            if (code == TRANSACTION_GET_HEALTH_DATA_WITH_ALGO) {
                final int algo = data.readInt();
                final int[] healthData = GoogleBatteryHal.getHealthDataWithAlgo(algo);
                if (reply != null) {
                    reply.writeNoException();
                    writeHealthData(reply, healthData);
                }
                return true;
            }

            if (code == TRANSACTION_SET_CHARGING_POLICY) {
                final int policy = data.readInt();
                final boolean ok = setChargingPolicy(policy);
                if (reply != null) {
                    reply.writeNoException();
                    reply.writeInt(ok ? 1 : 0);
                }
                return true;
            }

            // Remaining calls (incompatible charger, pulsar, bodega) are not implemented here.
            // Answer with an empty result: false, 0 or a null parcelable.
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(0);
            }
            return true;
        }
    };

    private static void writeHealthData(Parcel reply, int[] healthData) {
        if (healthData == null || healthData.length < 4) {
            reply.writeInt(0);
            return;
        }
        reply.writeInt(1);
        reply.writeInt(healthData[0]); // healthIndex
        reply.writeInt(healthData[1]); // performanceIndex (healthImpedanceIndex)
        reply.writeInt(healthData[2]); // capacityIndex (healthCapacityIndex)
        reply.writeInt(healthData[3]); // healthStatus
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /** Only system/self, Settings Intelligence, and Device Health Services may call this service. */
    private void enforceCaller() {
        final int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()
                || UserHandle.getAppId(uid) == android.os.Process.SYSTEM_UID) {
            return;
        }
        final PackageManager pm = getPackageManager();
        final String[] packages = pm.getPackagesForUid(uid);
        if (packages != null) {
            for (String pkg : packages) {
                if (!SETTINGS_INTELLIGENCE.equals(pkg) && !TURBO.equals(pkg)) continue;
                try {
                    final ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if (ai.isPrivilegedApp() || ai.isSystemApp()) {
                        return;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    // fall through
                }
            }
        }
        throw new SecurityException("uid " + uid + " may not call " + DESCRIPTOR);
    }

    private boolean setChargingPolicy(int policy) {
        if (!GoogleBatteryHal.hasLineageHealth()) {
            return GoogleBatteryHal.setChargingPolicy(
                    policy == POLICY_LONG_LIFE ? POLICY_LONG_LIFE : POLICY_DEFAULT);
        }
        final long token = Binder.clearCallingIdentity();
        try {
            final HealthInterface health = HealthInterface.getInstance(this);
            if (!health.isChargingControlSupported()) {
                Log.w(TAG, "setChargingPolicy(" + policy + "): charging control not supported");
                return false;
            }
            final ContentResolver cr = getContentResolver();
            final boolean ok;
            if (policy == POLICY_LONG_LIFE) {
                ok = LineageSettings.System.putInt(cr,
                                LineageSettings.System.CHARGING_CONTROL_MODE,
                                HealthInterface.MODE_LIMIT)
                        && LineageSettings.System.putInt(cr,
                                LineageSettings.System.CHARGING_CONTROL_LIMIT,
                                CHARGE_LIMIT_PERCENT)
                        && LineageSettings.System.putInt(cr,
                                LineageSettings.System.CHARGING_CONTROL_ENABLED, 1);
            } else {
                // POLICY_DEFAULT, and adaptive (handled by Settings, not here).
                ok = LineageSettings.System.putInt(cr,
                        LineageSettings.System.CHARGING_CONTROL_ENABLED, 0);
            }
            Log.i(TAG, "setChargingPolicy(" + policy + ") -> " + ok);
            return ok;
        } catch (RuntimeException e) {
            Log.e(TAG, "setChargingPolicy(" + policy + ") failed", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
