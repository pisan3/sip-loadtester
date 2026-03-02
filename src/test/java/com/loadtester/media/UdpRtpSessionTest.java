package com.loadtester.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.*;

class UdpRtpSessionTest {

    private UdpRtpSession sessionA;
    private UdpRtpSession sessionB;

    @AfterEach
    void tearDown() {
        if (sessionA != null) sessionA.stop();
        if (sessionB != null) sessionB.stop();
    }

    @Test
    void shouldBindToPort() throws Exception {
        sessionA = new UdpRtpSession();
        sessionA.start(0); // auto-assign port

        assertThat(sessionA.isRunning()).isTrue();
        assertThat(sessionA.getLocalPort()).isGreaterThan(0);
    }

    @Test
    void shouldSendAndReceivePacket() throws Exception {
        sessionA = new UdpRtpSession();
        sessionB = new UdpRtpSession();
        sessionA.start(0);
        sessionB.start(0);

        InetAddress localhost = InetAddress.getByName("127.0.0.1");
        sessionA.setRemote(localhost, sessionB.getLocalPort());

        byte[] payload = {0x01, 0x02, 0x03, 0x04};
        RtpPacket sent = new RtpPacket(RtpPacket.PAYLOAD_TYPE_PCMU, 1, 160, 0x1234L, payload);
        sessionA.sendPacket(sent);

        RtpPacket received = sessionB.receivePacket(2000);
        assertThat(received).isNotNull();
        assertThat(received.getPayloadType()).isEqualTo(RtpPacket.PAYLOAD_TYPE_PCMU);
        assertThat(received.getSequenceNumber()).isEqualTo(1);
        assertThat(received.getPayload()).isEqualTo(payload);
    }

    @Test
    void shouldTrackReceivedPackets() throws Exception {
        sessionA = new UdpRtpSession();
        sessionB = new UdpRtpSession();
        sessionA.start(0);
        sessionB.start(0);

        InetAddress localhost = InetAddress.getByName("127.0.0.1");
        sessionA.setRemote(localhost, sessionB.getLocalPort());

        // Send 3 packets
        for (int i = 0; i < 3; i++) {
            RtpPacket pkt = new RtpPacket(0, i, i * 160, 0x1234L, new byte[]{(byte) i});
            sessionA.sendPacket(pkt);
        }

        // Receive all 3
        for (int i = 0; i < 3; i++) {
            RtpPacket recv = sessionB.receivePacket(2000);
            assertThat(recv).isNotNull();
        }

        assertThat(sessionB.getReceivedPackets()).hasSize(3);
    }

    @Test
    void shouldReturnNullOnTimeout() throws Exception {
        sessionA = new UdpRtpSession();
        sessionA.start(0);

        RtpPacket result = sessionA.receivePacket(100);
        assertThat(result).isNull();
    }

    @Test
    void shouldThrowWhenSendingWithoutRemote() throws Exception {
        sessionA = new UdpRtpSession();
        sessionA.start(0);

        assertThatThrownBy(() ->
                sessionA.sendPacket(new RtpPacket(0, 0, 0, 0, new byte[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Remote");
    }

    @Test
    void shouldThrowWhenNotRunning() {
        sessionA = new UdpRtpSession();

        assertThatThrownBy(() ->
                sessionA.sendPacket(new RtpPacket(0, 0, 0, 0, new byte[0])))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> sessionA.receivePacket(100))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldThrowWhenStartedTwice() throws Exception {
        sessionA = new UdpRtpSession();
        sessionA.start(0);

        assertThatThrownBy(() -> sessionA.start(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");
    }

    @Test
    void stopShouldMarkAsNotRunning() throws Exception {
        sessionA = new UdpRtpSession();
        sessionA.start(0);
        assertThat(sessionA.isRunning()).isTrue();

        sessionA.stop();
        assertThat(sessionA.isRunning()).isFalse();
    }

    @Test
    void shouldSendMultiplePacketsBidirectionally() throws Exception {
        sessionA = new UdpRtpSession();
        sessionB = new UdpRtpSession();
        sessionA.start(0);
        sessionB.start(0);

        InetAddress localhost = InetAddress.getByName("127.0.0.1");
        sessionA.setRemote(localhost, sessionB.getLocalPort());
        sessionB.setRemote(localhost, sessionA.getLocalPort());

        // A sends to B
        RtpPacket pktAtoB = new RtpPacket(0, 1, 160, 0xAAAAL, new byte[]{0x01});
        sessionA.sendPacket(pktAtoB);

        // B sends to A
        RtpPacket pktBtoA = new RtpPacket(0, 1, 160, 0xBBBBL, new byte[]{0x02});
        sessionB.sendPacket(pktBtoA);

        // Receive at both ends
        RtpPacket recvAtB = sessionB.receivePacket(2000);
        RtpPacket recvAtA = sessionA.receivePacket(2000);

        assertThat(recvAtB).isNotNull();
        assertThat(recvAtB.getSsrc()).isEqualTo(0xAAAAL);

        assertThat(recvAtA).isNotNull();
        assertThat(recvAtA.getSsrc()).isEqualTo(0xBBBBL);
    }

    @Test
    void receivedPacketsListShouldBeUnmodifiable() throws Exception {
        sessionA = new UdpRtpSession();
        sessionA.start(0);

        assertThatThrownBy(() ->
                sessionA.getReceivedPackets().add(new RtpPacket()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void localPortShouldBeNegativeWhenNotStarted() {
        sessionA = new UdpRtpSession();
        assertThat(sessionA.getLocalPort()).isEqualTo(-1);
    }
}
