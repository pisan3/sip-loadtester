package com.loadtester.sip;

import com.loadtester.media.RtpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sip.Dialog;
import javax.sip.ServerTransaction;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for CallLeg — per-call state holder.
 */
@ExtendWith(MockitoExtension.class)
class CallLegTest {

    @Mock private ServerTransaction mockServerTransaction;
    @Mock private RtpSession mockRtpSession;
    @Mock private Dialog mockDialog;

    @Test
    void constructorShouldStoreAllFields() {
        SdpUtil.SdpInfo sdpInfo = new SdpUtil.SdpInfo("10.0.0.1", 40000, 0, "PCMU");
        CallLeg leg = new CallLeg("call-123", mockServerTransaction, sdpInfo, mockRtpSession);

        assertThat(leg.getCallId()).isEqualTo("call-123");
        assertThat(leg.getServerTransaction()).isSameAs(mockServerTransaction);
        assertThat(leg.getRemoteSdpInfo()).isSameAs(sdpInfo);
        assertThat(leg.getRtpSession()).isSameAs(mockRtpSession);
        assertThat(leg.getDialog()).isNull();
    }

    @Test
    void setDialogShouldUpdate() {
        CallLeg leg = new CallLeg("call-456", mockServerTransaction, null, mockRtpSession);
        assertThat(leg.getDialog()).isNull();

        leg.setDialog(mockDialog);
        assertThat(leg.getDialog()).isSameAs(mockDialog);
    }

    @Test
    void nullSdpInfoShouldBeAllowed() {
        CallLeg leg = new CallLeg("call-789", mockServerTransaction, null, mockRtpSession);
        assertThat(leg.getRemoteSdpInfo()).isNull();
    }

    @Test
    void toStringShouldContainCallId() {
        CallLeg leg = new CallLeg("abc-def", mockServerTransaction, null, mockRtpSession);
        assertThat(leg.toString()).contains("abc-def");
    }
}
