package com.example.bttestapp;

import android.net.Uri;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import androidx.annotation.Nullable;

/**
 * ConnectionService that bridges emulated calls to the Telecom framework.
 * 
 * This service handles incoming call requests from EmulatedCallService and
 * creates Connection objects that represent the call state to the system.
 */
public class MockConnectionService extends ConnectionService {

    private static final String TAG = "MockConnectionService";

    /**
     * Called when the Telecom framework initiates an incoming call.
     * 
     * We create a Connection object that will forward state changes
     * from EmulatedCallService to the Telecom framework.
     */
    @Override
    public Connection onCreateIncomingConnection(
            @Nullable PhoneAccountHandle connectionManagerPhoneAccount,
            @Nullable ConnectionRequest request
    ) {
        android.util.Log.d(TAG, "onCreateIncomingConnection called");

        Connection connection = new Connection() {
            @Override
            public void onAnswer() {
                android.util.Log.d(TAG, "Connection.onAnswer() called");
                setActive();
            }

            @Override
            public void onReject() {
                android.util.Log.d(TAG, "Connection.onReject() called");
                setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
                destroy();
            }

            @Override
            public void onHold() {
                android.util.Log.d(TAG, "Connection.onHold() called");
                setOnHold();
            }

            @Override
            public void onUnhold() {
                android.util.Log.d(TAG, "Connection.onUnhold() called");
                setActive();
            }

            @Override
            public void onDisconnect() {
                android.util.Log.d(TAG, "Connection.onDisconnect() called");
                setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
                destroy();
            }
        };

        // Extract caller info from request if available
        if (request != null && request.getAddress() != null) {
            connection.setAddress(request.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
        } else {
            connection.setAddress(Uri.parse("tel:5551234"), TelecomManager.PRESENTATION_ALLOWED);
        }

        // Set connection properties for self-managed call
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        connection.setRinging();

        android.util.Log.d(TAG, "Incoming connection created and set to ringing");
        return connection;
    }
}
