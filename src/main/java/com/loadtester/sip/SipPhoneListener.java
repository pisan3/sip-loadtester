package com.loadtester.sip;

/**
 * Callback interface for SIP phone events.
 * <p>
 * Allows the scenario orchestrator to react to SIP state changes
 * without coupling to JAIN-SIP internals.
 */
public interface SipPhoneListener {

    /** Called when REGISTER succeeds (200 OK). */
    void onRegistered();

    /** Called when de-registration succeeds (REGISTER Expires:0 gets 200 OK). */
    default void onUnregistered() {}

    /** Called when REGISTER fails. */
    void onRegistrationFailed(int statusCode, String reason);

    /** Called when an incoming INVITE is received. */
    void onIncomingCall(String callerUri, String sdpOffer);

    /**
     * Called when an incoming INVITE is received, with Call-ID for multi-call support.
     * Default delegates to the 2-arg version for backward compatibility.
     */
    default void onIncomingCall(String callId, String callerUri, String sdpOffer) {
        onIncomingCall(callerUri, sdpOffer);
    }

    /** Called when 180 Ringing is received for an outgoing INVITE. */
    void onRinging();

    /** Called when 200 OK is received for an outgoing INVITE (call answered). */
    void onAnswered(String sdpAnswer);

    /** Called when an outgoing INVITE fails. */
    void onCallFailed(int statusCode, String reason);

    /** Called when BYE is received or acknowledged. */
    void onBye();

    /**
     * Called when BYE is received or acknowledged, with Call-ID for multi-call support.
     * Default delegates to the no-arg version for backward compatibility.
     */
    default void onBye(String callId) {
        onBye();
    }

    /** Called on unexpected errors. */
    void onError(Exception e);
}
