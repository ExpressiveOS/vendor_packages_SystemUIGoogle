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
 * keep it. Battery health telemetry is queried via IGoogleBattery transactions
 * 13-16 and 18. The stock vendor policy lets platform_app (SystemUI) call the HAL.
 *
 * Transaction codes and parcel layout: Google's stock SystemUIGoogle, CP4A.261105.040.D1
 * (ChargeLimitController / HealthManager -> IGoogleBattery$Stub$Proxy & BatteryHealthStats).
 */
final class GoogleBatteryHal {
    private static final String TAG = "EosHealthService";

    private static final String DESCRIPTOR = "vendor.google.google_battery.IGoogleBattery";
    private static final String INSTANCE = DESCRIPTOR + "/default";
    private static final int TRANSACTION_GET_HEALTH_CAPACITY_INDEX = 13;
    private static final int TRANSACTION_GET_HEALTH_IMPEDANCE_INDEX = 14;
    private static final int TRANSACTION_GET_HEALTH_INDEX = 15;
    private static final int TRANSACTION_GET_HEALTH_STATUS = 16;
    private static final int TRANSACTION_GET_HEALTH_STATS = 18;
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

    private static IBinder getService() {
        if (!isDeclared()) {
            return null;
        }
        return Binder.allowBlocking(ServiceManager.waitForDeclaredService(INSTANCE));
    }

    private static int queryInt(IBinder hal, int transactionCode, String name) {
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!hal.transact(transactionCode, data, reply, 0)) {
                Log.w(TAG, "GoogleBattery: " + name + " not implemented");
                return -1;
            }
            reply.readException();
            return reply.readInt();
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "GoogleBattery: " + name + " failed", e);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Queries battery health metrics from IGoogleBattery/default.
     *
     * @return {healthIndex, performanceIndex, capacityIndex, healthStatus}, or null if unavailable.
     */
    static int[] getHealthData() {
        final long token = Binder.clearCallingIdentity();
        try {
            final IBinder hal = getService();
            if (hal == null) {
                Log.w(TAG, "GoogleBattery: " + INSTANCE + " not running");
                return null;
            }
            final int healthIndex = queryInt(
                    hal, TRANSACTION_GET_HEALTH_INDEX, "getHealthIndex");
            final int perfIndex = queryInt(
                    hal, TRANSACTION_GET_HEALTH_IMPEDANCE_INDEX, "getHealthImpedanceIndex");
            final int capacityIndex = queryInt(
                    hal, TRANSACTION_GET_HEALTH_CAPACITY_INDEX, "getHealthCapacityIndex");
            final int healthStatus = queryInt(
                    hal, TRANSACTION_GET_HEALTH_STATUS, "getHealthStatus");
            return new int[] { healthIndex, perfIndex, capacityIndex, healthStatus };
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Queries battery health metrics for a specific algorithm via IGoogleBattery.getHealthStats(int),
     * falling back to getHealthData() if transaction 18 fails.
     *
     * @return {healthIndex, performanceIndex, capacityIndex, healthStatus}, or null if unavailable.
     */
    static int[] getHealthDataWithAlgo(int algo) {
        final long token = Binder.clearCallingIdentity();
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            final IBinder hal = getService();
            if (hal == null) {
                Log.w(TAG, "GoogleBattery: " + INSTANCE + " not running");
                return null;
            }
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(algo);
            if (!hal.transact(TRANSACTION_GET_HEALTH_STATS, data, reply, 0)) {
                Log.w(TAG, "GoogleBattery: getHealthStats not implemented, falling back");
                return getHealthData();
            }
            reply.readException();
            if (reply.readInt() == 0) {
                return null;
            }
            final int parcelableSize = reply.readInt();
            if (parcelableSize < 24) {
                Log.w(TAG, "GoogleBattery: BatteryHealthStats size too small ("
                        + parcelableSize + "), falling back");
                return getHealthData();
            }
            reply.readInt(); // healthAlgo
            final int healthStatus = reply.readInt();
            final int healthIndex = reply.readInt();
            final int healthCapacityIndex = reply.readInt();
            final int healthImpedanceIndex = reply.readInt();
            return new int[] {
                healthIndex,
                healthImpedanceIndex,
                healthCapacityIndex,
                healthStatus
            };
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "GoogleBattery: getHealthStats(" + algo + ") failed, falling back", e);
            return getHealthData();
        } finally {
            reply.recycle();
            data.recycle();
            Binder.restoreCallingIdentity(token);
        }
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
