package com.example.bttestapp;

/**
 * Enum representing the state of an emulated call.
 * 
 * States:
 * - IDLE: No call active
 * - INCOMING_RINGING: Incoming call arriving, waiting for acceptance
 * - ACTIVE: Call is active (either answered or resumed from hold)
 * - HELD: Call is on hold
 * - DISCONNECTED: Call has ended
 */
public enum CallState {
    IDLE,
    INCOMING_RINGING,
    ACTIVE,
    HELD,
    DISCONNECTED
}
