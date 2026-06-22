package com.brcm.bttestapp;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class BluetoothMonitorService extends Service {

    private static final String TAG = "BluetoothMonitorService";

    public enum BluetoothStatus {
        UNKNOWN,
        Bluetooth_OFF,
        Bluetooth_ON
    }

    private static volatile BluetoothStatus sLastKnownBluetoothStatus = BluetoothStatus.UNKNOWN;

    private final IBinder mBinder = new LocalBinder();
    private volatile BluetoothStatus mCurrentBluetoothStatus = BluetoothStatus.UNKNOWN;
    private BluetoothAdapter mBluetoothAdapter;

    private final BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                BluetoothStatus newStatus = toBluetoothStatus(state);
                updateStatus(newStatus);
                Log.i(TAG, "Bluetooth adapter state changed: " + newStatus);
            }
        }
    };

    public class LocalBinder extends Binder {
        public BluetoothMonitorService getService() {
            return BluetoothMonitorService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(mBluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        refreshCurrentBluetoothStatus();
        Log.i(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        refreshCurrentBluetoothStatus();
        Log.i(TAG, "Service started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(mBluetoothReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Bluetooth receiver was already unregistered", e);
        }
        Log.i(TAG, "Service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public BluetoothStatus getCurrentBluetoothStatus() {
        return mCurrentBluetoothStatus;
    }

    public static BluetoothStatus getLastKnownBluetoothStatus() {
        return sLastKnownBluetoothStatus;
    }

    private void refreshCurrentBluetoothStatus() {
        BluetoothStatus status = BluetoothStatus.UNKNOWN;
        try {
            if (mBluetoothAdapter == null) {
                mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            }
            if (mBluetoothAdapter == null) {
                status = BluetoothStatus.Bluetooth_OFF;
            } else {
                status = toBluetoothStatus(mBluetoothAdapter.getState());
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot read Bluetooth adapter status due to missing permission", e);
        }
        updateStatus(status);
        Log.i(TAG, "Current Bluetooth status: " + status);
    }

    private BluetoothStatus toBluetoothStatus(int adapterState) {
        if (adapterState == BluetoothAdapter.STATE_ON) {
            return BluetoothStatus.Bluetooth_ON;
        }
        if (adapterState == BluetoothAdapter.STATE_OFF) {
            return BluetoothStatus.Bluetooth_OFF;
        }
        return BluetoothStatus.UNKNOWN;
    }

    private void updateStatus(BluetoothStatus status) {
        mCurrentBluetoothStatus = status;
        sLastKnownBluetoothStatus = status;
    }
}
