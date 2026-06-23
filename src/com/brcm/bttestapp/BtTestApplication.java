package com.brcm.bttestapp;

import android.app.Application;
import android.content.Intent;

public class BtTestApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        startService(new Intent(this, BluetoothMonitorService.class));
        startService(new Intent(this, EmulatedCallService.class));
    }

    @Override
    public void onTerminate() {
        stopService(new Intent(this, BluetoothMonitorService.class));
        stopService(new Intent(this, EmulatedCallService.class));
        super.onTerminate();
    }
}
