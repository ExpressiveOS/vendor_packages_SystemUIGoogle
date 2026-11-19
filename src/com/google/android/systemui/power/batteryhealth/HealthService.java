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
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import vendor.expressiveos.health.ChargingControlSupportedMode;
import vendor.expressiveos.health.ChargingLimitInfo;
import vendor.expressiveos.health.IChargingControl;

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
 * In ExpressiveOS 2.0 (pure AOSP):
 * - Phones declaring vendor.expressiveos.health.IChargingControl/default (Pixel 7 Pro
 *   and Pixel 8 Pro) apply the 80% charging limit directly via IChargingControl.setChargingLimit.
 * - Phones on Google's stock vendor image (the Pixel 11 series) apply policies and query
 *   battery health telemetry through Google's own battery HAL (see GoogleBatteryHal).
 */
public class HealthService extends Service {
    private static final String TAG = "EosHealthService";

    private static final String DESCRIPTOR =
            "com.google.android.systemui.power.batteryhealth.IHealthService";
    private static final String EOS_HEALTH_INSTANCE =
            IChargingControl.DESCRIPTOR + "/default";
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

    private static final int CHARGE_LIMIT_START_PERCENT = 75;
    private static final int CHARGE_LIMIT_STOP_PERCENT = 80;
    private static final int CHARGE_DEFAULT_START_PERCENT = 0;
    private static final int CHARGE_DEFAULT_STOP_PERCENT = 100;

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
                final boolean ok = applyChargingPolicy(policy);
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

    /** True when the device declares ExpressiveOS's health HAL (vendor.expressiveos.health). */
    static boolean hasEosHealthHal() {
        return ServiceManager.isDeclared(EOS_HEALTH_INSTANCE);
    }

    static boolean applyChargingPolicy(int policy) {
        final int effectivePolicy = (policy == POLICY_LONG_LIFE) ? POLICY_LONG_LIFE : POLICY_DEFAULT;
        if (!hasEosHealthHal()) {
            return GoogleBatteryHal.setChargingPolicy(effectivePolicy);
        }
        final long token = Binder.clearCallingIdentity();
        try {
            final IBinder binder = Binder.allowBlocking(
                    ServiceManager.waitForDeclaredService(EOS_HEALTH_INSTANCE));
            final IChargingControl hal = IChargingControl.Stub.asInterface(binder);
            if (hal == null) {
                Log.w(TAG, "applyChargingPolicy(" + policy + "): " + EOS_HEALTH_INSTANCE + " null");
                return false;
            }
            final int supported = hal.getSupportedMode();
            if ((supported & ChargingControlSupportedMode.LIMIT) == 0) {
                Log.w(TAG, "applyChargingPolicy(" + policy + "): LIMIT mode not supported");
                return false;
            }
            final ChargingLimitInfo limit = new ChargingLimitInfo();
            if (effectivePolicy == POLICY_LONG_LIFE) {
                limit.min = CHARGE_LIMIT_START_PERCENT;
                limit.max = CHARGE_LIMIT_STOP_PERCENT;
            } else {
                limit.min = CHARGE_DEFAULT_START_PERCENT;
                limit.max = CHARGE_DEFAULT_STOP_PERCENT;
            }
            hal.setChargingLimit(limit);
            Log.i(TAG, "applyChargingPolicy(" + policy + ") via EosHealth [" + limit.min + "," + limit.max + "]");
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "applyChargingPolicy(" + policy + ") failed", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
