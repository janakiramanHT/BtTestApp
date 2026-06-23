package com.example.bttestapp;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import androidx.annotation.Nullable;

import java.util.UUID;

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
        registerPhoneAccount();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
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
            android.util.Log.w(TAG, "triggerIncomingCall: already have active call in state " + mCurrentCallState);
            return;
        }

        mCallerNumber = number;
        mCallerName = name;

        transitionToState(CallState.INCOMING_RINGING);

        // Create connection and notify Telecom framework
        createAndPublishConnection();
    }

    /**
     * Accept the incoming call and transition to ACTIVE state.
     * Only valid if state == INCOMING_RINGING.
     */
    public void acceptCall() {
        if (mCurrentCallState != CallState.INCOMING_RINGING) {
            android.util.Log.w(TAG, "acceptCall: not in INCOMING_RINGING state, current: " + mCurrentCallState);
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
            android.util.Log.w(TAG, "holdCall: not in ACTIVE state, current: " + mCurrentCallState);
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
            android.util.Log.w(TAG, "unholdCall: not in HELD state, current: " + mCurrentCallState);
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
            android.util.Log.w(TAG, "disconnectCall: already in " + mCurrentCallState);
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
        android.util.Log.d(TAG, "Call state transitioned to: " + newState);

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
        mTelecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        if (mTelecomManager == null) {
            android.util.Log.e(TAG, "TelecomManager not available");
            return;
        }

        ComponentName componentName = new ComponentName(this, MockConnectionService.class);
        String accountId = "MockCallAccount_" + UUID.randomUUID();
        mPhoneAccountHandle = new PhoneAccountHandle(componentName, accountId);

        PhoneAccount phoneAccount = new PhoneAccount.Builder(mPhoneAccountHandle, "Mock Call Provider")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build();

        try {
            mTelecomManager.registerPhoneAccount(phoneAccount);
            android.util.Log.d(TAG, "PhoneAccount registered successfully");
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to register PhoneAccount: " + e.getMessage());
        }
    }

    /**
     * Create a Connection and publish it to Telecom framework.
     * This triggers the incoming-call UI in the system.
     */
    private void createAndPublishConnection() {
        if (mPhoneAccountHandle == null || mTelecomManager == null) {
            android.util.Log.e(TAG, "Cannot create connection: PhoneAccount not registered");
            return;
        }

        try {
            // Create a mock connection
            Connection connection = new Connection() {
                @Override
                public void onAnswer() {
                    acceptCall();
                }

                @Override
                public void onReject() {
                    disconnectCall();
                }

                @Override
                public void onHold() {
                    holdCall();
                }

                @Override
                public void onUnhold() {
                    unholdCall();
                }

                @Override
                public void onDisconnect() {
                    disconnectCall();
                }
            };

            // Set caller metadata
            connection.setAddress(Uri.parse("tel:" + mCallerNumber), TelecomManager.PRESENTATION_ALLOWED);
            connection.setCallerDisplayName(mCallerName, TelecomManager.PRESENTATION_ALLOWED);
            connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
            connection.setRinging();

            mCurrentConnection = connection;

            android.util.Log.d(TAG, "Connection created and set to ringing state");
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to create connection: " + e.getMessage());
        }
    }

    /**
     * Helper to run code on the main thread.
     */
    private void runOnMainThread(Runnable r) {
        if (Thread.currentThread().equals(Thread.currentThread())) {
            // Already on main thread (unlikely for service, but be safe)
            r.run();
        } else {
            // Post to main thread handler
            android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            mainHandler.post(r);
        }
    }
}
