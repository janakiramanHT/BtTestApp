

Goal:

The app shall emulate calls and set appropriate Telecom API. so that the system shall behave as if there is an active call going on.
The app shall not make any real calls or connect to the telecom network.

Implementation Style:

the app shall have a dedicated Telephony service. The service shall be started with the application and shall be running as long as the application runs.
the service shall have API, so that the main activity can connect to the service and get status and forward commands.

the version1 of the ap shall be able to handle a single call. The call status should be forwarded to the Telecom/telephony framework. 
the service should be able to get the commands and callbacks from the framework and can transition to appropriiate states.

the service shall have a listener api, to which other classes can subsribe and get status updates on the calls and theiir status.

The call should be able to go through these states:

Idle
Incoming ringing
Active
Held
Disconnected

APP:

the app shall be a system app, so that it can make necessary API calls.
the device shall be using the default AOSP signature. and hence no specific sigining required. default should do.
The app shall use self managed connection service. 

Telecom integration:
The app shall use Phone account to register as a self managed capable app
The app shall use relevant connect service API

Version 1:
* We can trigger an incoming call
*  we will be able to switch between hold and unhold states
* we will be able to disconnect /hang the call 

The call service shall be sticky. it shall be a started service and allow binding so that the main activity can make API calls

The service may provide these API 
void triggerIncomingCall(String number)
void acceptCall()
void holdCall()
void unholdCall()
void disconnectCall()
CallState getCurrentState()
void addCallStateListener(OnCallStateChangedListener)

Forbidden APIs:

avoid making real PSTN calls
- Intent.ACTION_CALL / ACTION_DIAL
- TelecomManager.placeCall()
- TelephonyManager callbacks

Example registraton:

2. Register the PhoneAccount
Run this code once (e.g., on app launch) to introduce your mock dialer to the Android system.

Kotlin
val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
val componentName = ComponentName(this, MockConnectionService::class.java)

val phoneAccountHandle = PhoneAccountHandle(componentName, "MockCallAccountId")

val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "Mock Call Provider")
    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED) // Crucial for no-network mocking
    .build()

telecomManager.registerPhoneAccount(phoneAccount)
3. Handle Calls in your ConnectionService
Your service will create custom Connection instances when a call is mocked. You can pass explicit metadata like caller names and numbers, which will cleanly display on Bluetooth accessories.

Kotlin
class MockConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val connection = object : Connection() {
            override fun onAnswer() {
                setActive() // Moves state to active, signaling BT to open audio channel
            }
            override fun onDisconnect() {
                setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                destroy()
            }
        }
        
        // This metadata gets piped straight to your Bluetooth system
        connection.setAddress(Uri.parse("tel:5551234"), TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName("Mock Tester", TelecomManager.PRESENTATION_ALLOWED)
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
        connection.setRinging() // Tells the system (and BT devices) a call is incoming
        
        return connection
    }
}