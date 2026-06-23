package com.brcm.bttestapp;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private AppStateMachine mAppStateMachine;
    private CheckBox mBtAdapterCheckBox;
    private BluetoothMonitorService mBluetoothMonitorService;
    private boolean mIsServiceBound;

        private final BluetoothMonitorService.OnBluetoothStatusChangedListener mStatusListener =
            status -> runOnUiThread(() -> updateBtAdapterSelection(status.name()));

    private final BroadcastReceiver mBtStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }

            if (BluetoothMonitorService.ACTION_BT_STATUS_CHANGED.equals(intent.getAction())) {
                String status = intent.getStringExtra(BluetoothMonitorService.EXTRA_BT_STATUS);
                updateBtAdapterSelection(status);
            }
        }
    };

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothMonitorService.LocalBinder binder = (BluetoothMonitorService.LocalBinder) service;
            mBluetoothMonitorService = binder.getService();
            mIsServiceBound = true;
            mBluetoothMonitorService.setOnBluetoothStatusChangedListener(mStatusListener);
            updateBtAdapterSelection(mBluetoothMonitorService.getCurrentBluetoothStatus().name());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (mBluetoothMonitorService != null) {
                mBluetoothMonitorService.clearOnBluetoothStatusChangedListener(mStatusListener);
            }
            mBluetoothMonitorService = null;
            mIsServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mBtAdapterCheckBox = findViewById(R.id.cb_bt_adapter);
        if (mBtAdapterCheckBox == null) {
            Log.w(TAG, "BT adapter checkbox not found in layout. Building fallback UI.");
            LinearLayout fallbackRoot = new LinearLayout(this);
            fallbackRoot.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
            fallbackRoot.setOrientation(LinearLayout.VERTICAL);
            fallbackRoot.setBackgroundColor(0xFFFFFFFF);
            fallbackRoot.setPadding(24, 72, 24, 24);

            mBtAdapterCheckBox = new CheckBox(this);
            mBtAdapterCheckBox.setText(R.string.bt_adapter_label);
            mBtAdapterCheckBox.setTextColor(0xFF000000);
            mBtAdapterCheckBox.setClickable(false);
            mBtAdapterCheckBox.setFocusable(false);
            fallbackRoot.addView(mBtAdapterCheckBox);

            setContentView(fallbackRoot);
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        AppStateMachine.AppState initialState =
                (adapter != null && adapter.isEnabled())
                        ? AppStateMachine.AppState.Bluetooth_ON
                        : AppStateMachine.AppState.Bluetooth_off;

        mAppStateMachine = new AppStateMachine(initialState);
        Log.i(TAG, "Initial app state: " + mAppStateMachine.getCurrentState());

        updateBtAdapterSelection(BluetoothMonitorService.getLastKnownBluetoothStatus().name());
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(BluetoothMonitorService.ACTION_BT_STATUS_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mBtStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mBtStatusReceiver, filter);
        }

        Intent serviceIntent = new Intent(this, BluetoothMonitorService.class);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();

        unregisterReceiver(mBtStatusReceiver);
        if (mIsServiceBound) {
            if (mBluetoothMonitorService != null) {
                mBluetoothMonitorService.clearOnBluetoothStatusChangedListener(mStatusListener);
            }
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }

    public AppStateMachine.AppState getCurrentAppState() {
        return mAppStateMachine.getCurrentState();
    }

    private void updateBtAdapterSelection(String statusText) {
        if (mBtAdapterCheckBox == null) {
            return;
        }

        boolean isSelected = false;
        if (statusText != null) {
            isSelected = "Bluetooth_ON".equals(statusText);
        }
        mBtAdapterCheckBox.setChecked(isSelected);
    }
}
