package com.loadtester.sip;

import com.loadtester.media.RtpSession;

import javax.sip.Dialog;
import javax.sip.ServerTransaction;

/**
 * Per-call state for a SipPhone handling multiple concurrent calls (B-side).
 * <p>
 * Each incoming INVITE creates a new CallLeg identified by the SIP Call-ID.
 * The CallLeg holds the server transaction, dialog, remote SDP info, and
 * a dedicated RTP session for that call.
 */
public class CallLeg {

    private final String callId;
    private final ServerTransaction serverTransaction;
    private final SdpUtil.SdpInfo remoteSdpInfo;
    private final RtpSession rtpSession;
    private volatile Dialog dialog;

    public CallLeg(String callId, ServerTransaction serverTransaction,
                   SdpUtil.SdpInfo remoteSdpInfo, RtpSession rtpSession) {
        this.callId = callId;
        this.serverTransaction = serverTransaction;
        this.remoteSdpInfo = remoteSdpInfo;
        this.rtpSession = rtpSession;
    }

    public String getCallId() {
        return callId;
    }

    public ServerTransaction getServerTransaction() {
        return serverTransaction;
    }

    public SdpUtil.SdpInfo getRemoteSdpInfo() {
        return remoteSdpInfo;
    }

    public RtpSession getRtpSession() {
        return rtpSession;
    }

    public Dialog getDialog() {
        return dialog;
    }

    public void setDialog(Dialog dialog) {
        this.dialog = dialog;
    }

    @Override
    public String toString() {
        return "CallLeg{callId='" + callId + "'}";
    }
}
