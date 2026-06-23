package com.brcm.bttestapp;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import java.util.List;

/**
 * Service that emulates incoming calls via the Telecom framework.
 * 
 * Responsibilities:
 * - Manage emulated call state machine (IDLE, INCOMING_RINGING, ACTIVE, HELD, DISCONNECTED)
 * - Provide API for MainActivity (triggerIncomingCall, acceptCall, holdCall, unholdCall, disconnectCall)
 * - Register PhoneAccount for self-managed call capabilities
 * - Bridge call state to Telecom framework via Connection object
 * - Notify listeners of state changes
 * 
 * Service lifecycle:
 * - Sticky: auto-restart if system kills it
 * - Started + Bindable: MainActivity binds to call control methods
 */
public class EmulatedCallService extends Service {

    private static final String TAG = "EmulatedCallService";
    private static final String PHONE_ACCOUNT_ID = "MockCallAccountId";
    private static final String PHONE_ACCOUNT_LABEL = "Mock Call Provider";

    private boolean mTelecomFeatureSupported;

    // Binder for activity binding
    private final IBinder mBinder = new LocalBinder();

    // Call state machine
    private volatile CallState mCurrentCallState = CallState.IDLE;

    // Connection object for Telecom framework
    private volatile Connection mCurrentConnection = null;

    // Caller info for this emulated call
    private volatile String mCallerNumber = null;
    private volatile String mCallerName = null;

    // Listener for state change notifications
    private volatile OnCallStateChangedListener mCallStateListener = null;

    // PhoneAccount handle (registered once at service start)
    private PhoneAccountHandle mPhoneAccountHandle = null;
    private TelecomManager mTelecomManager = null;

    /**
     * Local binder for activity binding.
     */
    public class LocalBinder extends Binder {
        EmulatedCallService getService() {
            return EmulatedCallService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "EmulatedCallService created");
        MockConnectionService.setOnConnectionEventCallback(new MockConnectionService.OnConnectionEventCallback() {
            @Override
            public void onAnswered() {
                onFrameworkCallAnswered();
            }

            @Override
            public void onRejected() {
                onFrameworkCallEnded("rejected");
            }

            @Override
            public void onHeld() {
                onFrameworkCallHeld();
            }

            @Override
            public void onUnheld() {
                onFrameworkCallUnheld();
            }

            @Override
            public void onDisconnected() {
                onFrameworkCallEnded("disconnected");
            }
        });

        boolean hasManageOwnCalls = checkSelfPermission(android.Manifest.permission.MANAGE_OWN_CALLS)
                == PackageManager.PERMISSION_GRANTED;
        Log.d(TAG, "MANAGE_OWN_CALLS granted=" + hasManageOwnCalls);

        mTelecomFeatureSupported = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELECOM);
        Log.d(TAG, "FEATURE_TELECOM supported=" + mTelecomFeatureSupported);

        if (mTelecomFeatureSupported) {
            registerPhoneAccount();
        } else {
            Log.w(TAG, "Telecom feature not supported on this image. Using local emulation mode.");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "EmulatedCallService started");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "EmulatedCallService destroyed");
        MockConnectionService.clearOnConnectionEventCallback();
        // Clean up: if a call is active, disconnect it
        if (mCurrentCallState != CallState.IDLE && mCurrentCallState != CallState.DISCONNECTED) {
            disconnectCall();
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Trigger an incoming call with the given phone number and name.
     * Only valid if no call is currently active (state == IDLE).
     * 
     * @param number the incoming caller's number (e.g., "555-1234")
     * @param name the incoming caller's display name (e.g., "Mock Tester")
     */
    public void triggerIncomingCall(String number, String name) {
        if (mCurrentCallState != CallState.IDLE) {
            Log.w(TAG, "triggerIncomingCall: already have active call in state " + mCurrentCallState);
            return;
        }

        mCallerNumber = number;
        mCallerName = name;

        if (!mTelecomFeatureSupported) {
            Log.i(TAG, "triggerIncomingCall: local mode incoming call");
            transitionToState(CallState.INCOMING_RINGING);
            return;
        }

        // Publish to Telecom first. Only transition state if framework accepted it.
        if (createAndPublishConnection()) {
            transitionToState(CallState.INCOMING_RINGING);
        } else {
            Log.e(TAG, "triggerIncomingCall: Telecom rejected incoming call publish");
            mCallerNumber = null;
            mCallerName = null;
            transitionToState(CallState.IDLE);
        }
    }

    /**
     * Accept the incoming call and transition to ACTIVE state.
     * Only valid if state == INCOMING_RINGING.
     */
    public void acceptCall() {
        if (mCurrentCallState != CallState.INCOMING_RINGING) {
            Log.w(TAG, "acceptCall: not in INCOMING_RINGING state, current: " + mCurrentCallState);
            return;
        }

        if (mTelecomFeatureSupported && mCurrentConnection == null) {
            Log.e(TAG, "acceptCall: Telecom Connection is null; framework never created call connection");
            return;
        }

        if (mCurrentConnection != null) {
            mCurrentConnection.setActive();
        }

        transitionToState(CallState.ACTIVE);
    }

    /**
     * Hold the call (only valid from ACTIVE state).
     */
    public void holdCall() {
        if (mCurrentCallState != CallState.ACTIVE) {
            Log.w(TAG, "holdCall: not in ACTIVE state, current: " + mCurrentCallState);
            return;
        }

        if (mTelecomFeatureSupported && mCurrentConnection == null) {
            Log.e(TAG, "holdCall: Telecom Connection is null");
            return;
        }

        if (mCurrentConnection != null) {
            mCurrentConnection.setOnHold();
        }

        transitionToState(CallState.HELD);
    }

    /**
     * Resume call from hold (only valid from HELD state).
     */
    public void unholdCall() {
        if (mCurrentCallState != CallState.HELD) {
            Log.w(TAG, "unholdCall: not in HELD state, current: " + mCurrentCallState);
            return;
        }

        if (mTelecomFeatureSupported && mCurrentConnection == null) {
            Log.e(TAG, "unholdCall: Telecom Connection is null");
            return;
        }

        if (mCurrentConnection != null) {
            mCurrentConnection.setActive();
        }

        transitionToState(CallState.ACTIVE);
    }

    /**
     * Disconnect (hangup) the call.
     * Valid from INCOMING_RINGING, ACTIVE, or HELD states.
     */
    public void disconnectCall() {
        if (mCurrentCallState == CallState.IDLE || mCurrentCallState == CallState.DISCONNECTED) {
            Log.w(TAG, "disconnectCall: already in " + mCurrentCallState);
            return;
        }

        if (mCurrentConnection != null) {
            mCurrentConnection.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            mCurrentConnection.destroy();
            mCurrentConnection = null;
        }

        transitionToState(CallState.DISCONNECTED);

        // Brief delay, then return to IDLE for next call
        mCurrentCallState = CallState.IDLE;
        mCallerNumber = null;
        mCallerName = null;
    }

    /**
     * Get the current call state.
     */
    public CallState getCurrentState() {
        return mCurrentCallState;
    }

    /**
     * Register a listener for call state changes.
     * Only one listener supported in v1.
     */
    public void setOnCallStateChangedListener(OnCallStateChangedListener listener) {
        mCallStateListener = listener;
    }

    /**
     * Clear the call state listener.
     */
    public void clearOnCallStateChangedListener() {
        mCallStateListener = null;
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Transition call state and notify listeners.
     */
    private void transitionToState(CallState newState) {
        if (mCurrentCallState == newState) {
            return;
        }

        mCurrentCallState = newState;
        Log.d(TAG, "Call state transitioned to: " + newState);

        // Notify listener on UI thread
        if (mCallStateListener != null) {
            runOnMainThread(() -> {
                if (mCallStateListener != null) {
                    mCallStateListener.onCallStateChanged(newState);
                }
            });
        }
    }

    /**
     * Register the PhoneAccount with Telecom framework (once at startup).
     */
    private void registerPhoneAccount() {
        if (!mTelecomFeatureSupported) {
            Log.w(TAG, "registerPhoneAccount skipped: FEATURE_TELECOM not supported");
            return;
        }

        mTelecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        if (mTelecomManager == null) {
            Log.e(TAG, "TelecomManager not available");
            return;
        }

        ComponentName componentName = new ComponentName(this, MockConnectionService.class);
        mPhoneAccountHandle = new PhoneAccountHandle(componentName, PHONE_ACCOUNT_ID);

        PhoneAccount phoneAccount = new PhoneAccount.Builder(mPhoneAccountHandle, PHONE_ACCOUNT_LABEL)
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build();

        try {
            mTelecomManager.registerPhoneAccount(phoneAccount);
            List<PhoneAccountHandle> ownAccounts = mTelecomManager.getOwnSelfManagedPhoneAccounts();
            boolean registered = ownAccounts.contains(mPhoneAccountHandle);
            Log.d(TAG, "PhoneAccount register attempted, handle=" + mPhoneAccountHandle
                    + ", registeredInOwnAccounts=" + registered
                    + ", ownAccountCount=" + ownAccounts.size());
        } catch (UnsupportedOperationException e) {
            Log.e(TAG, "registerPhoneAccount unsupported on this image", e);
            mTelecomFeatureSupported = false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register PhoneAccount", e);
        }
    }

    /**
     * Ensure PhoneAccount exists and is visible to Telecom for the current user.
     */
    private boolean ensurePhoneAccountRegistered() {
        if (!mTelecomFeatureSupported) {
            Log.w(TAG, "ensurePhoneAccountRegistered: telecom feature not supported");
            return false;
        }

        if (mTelecomManager == null || mPhoneAccountHandle == null) {
            registerPhoneAccount();
        }

        if (mTelecomManager == null || mPhoneAccountHandle == null) {
            Log.e(TAG, "ensurePhoneAccountRegistered: telecom manager or handle is null");
            return false;
        }

        try {
            List<PhoneAccountHandle> ownAccounts = mTelecomManager.getOwnSelfManagedPhoneAccounts();
            if (ownAccounts.contains(mPhoneAccountHandle)) {
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query self-managed phone accounts", e);
            return false;
        }

        Log.w(TAG, "PhoneAccount not found in Telecom, attempting re-register");
        registerPhoneAccount();

        if (mTelecomManager == null || mPhoneAccountHandle == null) {
            return false;
        }

        boolean registered;
        try {
            List<PhoneAccountHandle> ownAccounts = mTelecomManager.getOwnSelfManagedPhoneAccounts();
            registered = ownAccounts.contains(mPhoneAccountHandle);
        } catch (Exception e) {
            Log.e(TAG, "Failed to query self-managed phone accounts after re-register", e);
            return false;
        }
        if (!registered) {
            Log.e(TAG, "PhoneAccount still not registered for current user after re-register");
        }
        return registered;
    }

    /**
     * Create a Connection and publish it to Telecom framework via addNewIncomingCall().
     * Telecom will call MockConnectionService.onCreateIncomingConnection() which creates
     * the Connection and hands it back here via the static callback.
     */
    private boolean createAndPublishConnection() {
        if (!ensurePhoneAccountRegistered()) {
            Log.e(TAG, "Cannot create connection: PhoneAccount not registered for current user");
            return false;
        }

        try {
            // Register callback so MockConnectionService hands the Connection back to us
            MockConnectionService.setOnConnectionCreatedCallback(connection -> {
                mCurrentConnection = connection;
                Log.d(TAG, "Connection received from MockConnectionService");
                MockConnectionService.clearOnConnectionCreatedCallback();
            });

            // Tell Telecom about the incoming call — this triggers onCreateIncomingConnection()
            Bundle extras = new Bundle();
            extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                    Uri.parse("tel:" + mCallerNumber));
            extras.putString("mock_caller_name", mCallerName);
            mTelecomManager.addNewIncomingCall(mPhoneAccountHandle, extras);

            Log.d(TAG, "addNewIncomingCall() called for number: " + mCallerNumber);

            // If framework rejects the call setup, this will remain null.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (mCurrentConnection == null) {
                    Log.e(TAG, "No connection created after addNewIncomingCall. "
                            + "Check MockConnectionService.onCreateIncomingConnectionFailed logs.");
                }
            }, 800);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to publish incoming call", e);
            MockConnectionService.clearOnConnectionCreatedCallback();
            return false;
        }
    }

    private void onFrameworkCallAnswered() {
        Log.d(TAG, "Framework callback: call answered");
        if (mCurrentCallState == CallState.INCOMING_RINGING) {
            transitionToState(CallState.ACTIVE);
        }
    }

    private void onFrameworkCallHeld() {
        Log.d(TAG, "Framework callback: call held");
        if (mCurrentCallState == CallState.ACTIVE) {
            transitionToState(CallState.HELD);
        }
    }

    private void onFrameworkCallUnheld() {
        Log.d(TAG, "Framework callback: call unheld");
        if (mCurrentCallState == CallState.HELD) {
            transitionToState(CallState.ACTIVE);
        }
    }

    private void onFrameworkCallEnded(String reason) {
        Log.d(TAG, "Framework callback: call ended, reason=" + reason);
        if (mCurrentCallState == CallState.IDLE) {
            return;
        }

        transitionToState(CallState.DISCONNECTED);
        mCurrentConnection = null;
        mCurrentCallState = CallState.IDLE;
        mCallerNumber = null;
        mCallerName = null;
    }

    /**
     * Helper to run code on the main thread.
     */
    private void runOnMainThread(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(r);
        }
    }
}
