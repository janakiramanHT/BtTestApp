package com.brcm.bttestapp;

import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * ConnectionService that bridges emulated calls to the Telecom framework.
 *
 * Flow:
 * 1. EmulatedCallService calls TelecomManager.addNewIncomingCall()
 * 2. Telecom framework calls onCreateIncomingConnection() here
 * 3. We create the Connection, configure it, and hand it back via sConnectionCallback
 * 4. EmulatedCallService stores it and uses it for all future state transitions
 */
public class MockConnectionService extends ConnectionService {

    private static final String TAG = "MockConnectionService";

    /** Callback interface to deliver the created Connection to EmulatedCallService. */
    public interface OnConnectionCreatedCallback {
        void onConnectionCreated(Connection connection);
    }

    /** Callback interface for framework-driven connection events. */
    public interface OnConnectionEventCallback {
        void onAnswered();
        void onRejected();
        void onHeld();
        void onUnheld();
        void onDisconnected();
    }

    // Static callback: set by EmulatedCallService before calling addNewIncomingCall()
    private static volatile OnConnectionCreatedCallback sConnectionCallback;
    private static volatile OnConnectionEventCallback sConnectionEventCallback;

    public static void setOnConnectionCreatedCallback(OnConnectionCreatedCallback cb) {
        sConnectionCallback = cb;
    }

    public static void clearOnConnectionCreatedCallback() {
        sConnectionCallback = null;
    }

    public static void setOnConnectionEventCallback(OnConnectionEventCallback cb) {
        sConnectionEventCallback = cb;
    }

    public static void clearOnConnectionEventCallback() {
        sConnectionEventCallback = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MockConnectionService created");
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "MockConnectionService destroyed");
        super.onDestroy();
    }

    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request
    ) {
        Log.d(TAG, "onCreateIncomingConnection called, request=" + request);

        Connection connection = new Connection() {
            @Override
            public void onAnswer() {
                Log.d(TAG, "Connection.onAnswer() called by framework");
                setActive();
                OnConnectionEventCallback cb = sConnectionEventCallback;
                if (cb != null) {
                    cb.onAnswered();
                }
            }

            @Override
            public void onReject() {
                Log.d(TAG, "Connection.onReject() called by framework");
                setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
                destroy();
                OnConnectionEventCallback cb = sConnectionEventCallback;
                if (cb != null) {
                    cb.onRejected();
                }
            }

            @Override
            public void onHold() {
                Log.d(TAG, "Connection.onHold() called by framework");
                setOnHold();
                OnConnectionEventCallback cb = sConnectionEventCallback;
                if (cb != null) {
                    cb.onHeld();
                }
            }

            @Override
            public void onUnhold() {
                Log.d(TAG, "Connection.onUnhold() called by framework");
                setActive();
                OnConnectionEventCallback cb = sConnectionEventCallback;
                if (cb != null) {
                    cb.onUnheld();
                }
            }

            @Override
            public void onDisconnect() {
                Log.d(TAG, "Connection.onDisconnect() called by framework");
                setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
                destroy();
                OnConnectionEventCallback cb = sConnectionEventCallback;
                if (cb != null) {
                    cb.onDisconnected();
                }
            }
        };

        // Set caller address from request if available
        if (request != null && request.getAddress() != null) {
            connection.setAddress(request.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
        }

        // Extract display name from extras if set
        if (request != null && request.getExtras() != null) {
            String name = request.getExtras().getString("mock_caller_name");
            if (name != null) {
                connection.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED);
            }
        }

        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        // Explicitly advertise hold support so Telecom/HFP surfaces HOLD/UNHOLD actions.
        connection.setConnectionCapabilities(
            Connection.CAPABILITY_HOLD | Connection.CAPABILITY_SUPPORT_HOLD);
        connection.setRinging();

        Log.d(TAG, "Incoming connection created, handing back to EmulatedCallService");

        // Deliver the connection to EmulatedCallService
        OnConnectionCreatedCallback cb = sConnectionCallback;
        if (cb != null) {
            cb.onConnectionCreated(connection);
        } else {
            Log.w(TAG, "No connection callback registered — EmulatedCallService won't track this connection");
        }

        return connection;
    }

    @Override
    public void onCreateIncomingConnectionFailed(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed called, request=" + request);
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request);
    }

    @Override
    public void onCreateOutgoingConnectionFailed(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed called, request=" + request);
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request);
    }
}
