package com.brcm.bttestapp;

/**
 * Listener interface for call state changes.
 * Classes that need to react to emulated call state transitions should implement this.
 */
public interface OnCallStateChangedListener {
    /**
     * Called when the call state changes.
     * 
     * @param newState the new CallState
     */
    void onCallStateChanged(CallState newState);
}
