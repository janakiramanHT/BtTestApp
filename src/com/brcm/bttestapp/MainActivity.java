package com.brcm.bttestapp;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_BLUETOOTH_CONNECT_PERMISSION = 1001;

    private AppStateMachine mAppStateMachine;
    private CheckBox mBtAdapterCheckBox;
    private Button mRefreshBondedButton;
    private ListView mBondedDevicesListView;
    private ArrayAdapter<String> mBondedDevicesAdapter;
    private final List<String> mBondedDevicesData = new ArrayList<>();
    private BluetoothMonitorService mBluetoothMonitorService;
    private boolean mIsServiceBound;

    // Emulated call service binding
    private EmulatedCallService mEmulatedCallService;
    private boolean mIsCallServiceBound;

    // Call control UI
    private EditText mCallerNumberInput;
    private Button mTriggerCallButton;
    private Button mAcceptCallButton;
    private Button mHoldCallButton;
    private Button mUnholdCallButton;
    private Button mDisconnectCallButton;
    private TextView mCallStateDisplay;

        private final BluetoothMonitorService.OnBluetoothStatusChangedListener mStatusListener =
            status -> runOnUiThread(() -> updateBtAdapterSelection(status.name()));

    private final OnCallStateChangedListener mCallStateListener =
            newState -> runOnUiThread(() -> updateCallStateDisplay(newState));

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
            refreshBondedDevices();
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

    private final ServiceConnection mCallServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            EmulatedCallService.LocalBinder binder = (EmulatedCallService.LocalBinder) service;
            mEmulatedCallService = binder.getService();
            mIsCallServiceBound = true;
            mEmulatedCallService.setOnCallStateChangedListener(mCallStateListener);
            updateCallStateDisplay(mEmulatedCallService.getCurrentState());
            Log.d(TAG, "EmulatedCallService connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (mEmulatedCallService != null) {
                mEmulatedCallService.clearOnCallStateChangedListener();
            }
            mEmulatedCallService = null;
            mIsCallServiceBound = false;
            Log.d(TAG, "EmulatedCallService disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mBtAdapterCheckBox = findViewById(R.id.cb_bt_adapter);
        mRefreshBondedButton = findViewById(R.id.btn_refresh_bonded);
        mBondedDevicesListView = findViewById(R.id.lv_bonded_devices);
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

        if (mBondedDevicesListView != null) {
            mBondedDevicesAdapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_list_item_1,
                    mBondedDevicesData);
            mBondedDevicesListView.setAdapter(mBondedDevicesAdapter);
        }

        if (mRefreshBondedButton != null) {
            mRefreshBondedButton.setOnClickListener(v -> refreshBondedDevices());
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        AppStateMachine.AppState initialState =
                (adapter != null && adapter.isEnabled())
                        ? AppStateMachine.AppState.Bluetooth_ON
                        : AppStateMachine.AppState.Bluetooth_off;

        mAppStateMachine = new AppStateMachine(initialState);
        Log.i(TAG, "Initial app state: " + mAppStateMachine.getCurrentState());

        updateBtAdapterSelection(BluetoothMonitorService.getLastKnownBluetoothStatus().name());

        // Initialize emulated call control UI
        initializeCallControlUI();
    }

    private void initializeCallControlUI() {
        // Get the root LinearLayout from the inflated layout
        LinearLayout mainLayout = findViewById(R.id.root_layout);
        if (mainLayout == null) {
            Log.w(TAG, "Cannot initialize call control UI: root_layout not found");
            return;
        }

        // Create call control section
        LinearLayout callSection = new LinearLayout(this);
        callSection.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        callSection.setOrientation(LinearLayout.VERTICAL);
        callSection.setPadding(16, 16, 16, 16);

        // Call state display
        mCallStateDisplay = new TextView(this);
        mCallStateDisplay.setText("Call State: IDLE");
        mCallStateDisplay.setTextSize(14);
        mCallStateDisplay.setTextColor(0xFF000000);
        callSection.addView(mCallStateDisplay);

        // Caller number input
        mCallerNumberInput = new EditText(this);
        mCallerNumberInput.setHint("Enter caller number");
        mCallerNumberInput.setText("555-1234");
        mCallerNumberInput.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        callSection.addView(mCallerNumberInput);

        // Trigger incoming call button
        mTriggerCallButton = new Button(this);
        mTriggerCallButton.setText("Trigger Incoming Call");
        mTriggerCallButton.setOnClickListener(v -> triggerIncomingCall());
        callSection.addView(mTriggerCallButton);

        // Accept call button
        mAcceptCallButton = new Button(this);
        mAcceptCallButton.setText("Accept Call");
        mAcceptCallButton.setOnClickListener(v -> acceptCall());
        callSection.addView(mAcceptCallButton);

        // Hold call button
        mHoldCallButton = new Button(this);
        mHoldCallButton.setText("Hold Call");
        mHoldCallButton.setOnClickListener(v -> holdCall());
        callSection.addView(mHoldCallButton);

        // Unhold call button
        mUnholdCallButton = new Button(this);
        mUnholdCallButton.setText("Unhold Call");
        mUnholdCallButton.setOnClickListener(v -> unholdCall());
        callSection.addView(mUnholdCallButton);

        // Disconnect call button
        mDisconnectCallButton = new Button(this);
        mDisconnectCallButton.setText("Disconnect Call");
        mDisconnectCallButton.setOnClickListener(v -> disconnectCall());
        callSection.addView(mDisconnectCallButton);

        // Add call section to main layout
        mainLayout.addView(callSection);
    }

    private void triggerIncomingCall() {
        if (mEmulatedCallService == null) {
            Log.w(TAG, "Call service not connected");
            return;
        }

        String callerNumber = mCallerNumberInput.getText().toString().trim();
        if (callerNumber.isEmpty()) {
            callerNumber = "Unknown";
        }

        mEmulatedCallService.triggerIncomingCall(callerNumber, "Mock Caller");
    }

    private void acceptCall() {
        if (mEmulatedCallService == null) {
            Log.w(TAG, "Call service not connected");
            return;
        }
        mEmulatedCallService.acceptCall();
    }

    private void holdCall() {
        if (mEmulatedCallService == null) {
            Log.w(TAG, "Call service not connected");
            return;
        }
        mEmulatedCallService.holdCall();
    }

    private void unholdCall() {
        if (mEmulatedCallService == null) {
            Log.w(TAG, "Call service not connected");
            return;
        }
        mEmulatedCallService.unholdCall();
    }

    private void disconnectCall() {
        if (mEmulatedCallService == null) {
            Log.w(TAG, "Call service not connected");
            return;
        }
        mEmulatedCallService.disconnectCall();
    }

    private void updateCallStateDisplay(CallState state) {
        if (mCallStateDisplay != null) {
            mCallStateDisplay.setText("Call State: " + state.name());
        }
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

        // Bind to EmulatedCallService
        Intent callServiceIntent = new Intent(this, EmulatedCallService.class);
        bindService(callServiceIntent, mCallServiceConnection, Context.BIND_AUTO_CREATE);
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

        // Unbind from EmulatedCallService
        if (mIsCallServiceBound) {
            if (mEmulatedCallService != null) {
                mEmulatedCallService.clearOnCallStateChangedListener();
            }
            unbindService(mCallServiceConnection);
            mIsCallServiceBound = false;
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

    private void refreshBondedDevices() {
        if (mBondedDevicesAdapter == null) {
            return;
        }

        mBondedDevicesData.clear();

        if (!ensureBluetoothConnectPermission()) {
            mBondedDevicesData.add("Bluetooth permission not granted");
            mBondedDevicesAdapter.notifyDataSetChanged();
            return;
        }

        if (!mIsServiceBound || mBluetoothMonitorService == null) {
            mBondedDevicesData.add("Service not connected");
            mBondedDevicesAdapter.notifyDataSetChanged();
            return;
        }

        List<BluetoothDevice> bondedDevices = mBluetoothMonitorService.getBondedDevices();
        if (bondedDevices.isEmpty()) {
            mBondedDevicesData.add("No bonded devices");
            mBondedDevicesAdapter.notifyDataSetChanged();
            return;
        }

        for (BluetoothDevice device : bondedDevices) {
            if (device == null) {
                continue;
            }

            String name = "Unknown";
            String address = "";
            try {
                if (device.getName() != null && !device.getName().isEmpty()) {
                    name = device.getName();
                }
                if (device.getAddress() != null) {
                    address = device.getAddress();
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Missing permission while reading bonded device fields", e);
            }

            if (address.isEmpty()) {
                mBondedDevicesData.add(name);
            } else {
                mBondedDevicesData.add(name + " (" + address + ")");
            }
        }

        if (mBondedDevicesData.isEmpty()) {
            mBondedDevicesData.add("No bonded devices");
        }
        mBondedDevicesAdapter.notifyDataSetChanged();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_BLUETOOTH_CONNECT_PERMISSION) {
            return;
        }

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refreshBondedDevices();
            return;
        }

        if (mBondedDevicesAdapter != null) {
            mBondedDevicesData.clear();
            mBondedDevicesData.add("Bluetooth permission denied");
            mBondedDevicesAdapter.notifyDataSetChanged();
        }
    }

    private boolean ensureBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        requestPermissions(
                new String[] { Manifest.permission.BLUETOOTH_CONNECT },
                REQUEST_BLUETOOTH_CONNECT_PERMISSION);
        return false;
    }
}
