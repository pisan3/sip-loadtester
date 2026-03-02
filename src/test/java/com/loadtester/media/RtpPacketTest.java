package com.loadtester.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RtpPacketTest {

    @Test
    void encodeDecodeShouldRoundTrip() {
        byte[] payload = {0x01, 0x02, 0x03, 0x04, 0x05};
        RtpPacket original = new RtpPacket(RtpPacket.PAYLOAD_TYPE_PCMU, 42, 12345L, 0xDEADBEEFL, payload);
        original.setMarker(true);

        byte[] encoded = original.encode();
        RtpPacket decoded = RtpPacket.decode(encoded);

        assertThat(decoded.getVersion()).isEqualTo(2);
        assertThat(decoded.getPayloadType()).isEqualTo(RtpPacket.PAYLOAD_TYPE_PCMU);
        assertThat(decoded.getSequenceNumber()).isEqualTo(42);
        assertThat(decoded.getTimestamp()).isEqualTo(12345L);
        assertThat(decoded.getSsrc()).isEqualTo(0xDEADBEEFL);
        assertThat(decoded.isMarker()).isTrue();
        assertThat(decoded.getPayload()).isEqualTo(payload);
    }

    @Test
    void headerShouldBe12Bytes() {
        byte[] payload = new byte[0];
        RtpPacket pkt = new RtpPacket(0, 0, 0, 0, payload);
        byte[] encoded = pkt.encode();
        assertThat(encoded).hasSize(RtpPacket.HEADER_SIZE);
    }

    @Test
    void totalLengthShouldBeHeaderPlusPayload() {
        byte[] payload = new byte[160]; // 20ms PCMU
        RtpPacket pkt = new RtpPacket(0, 0, 0, 0, payload);
        byte[] encoded = pkt.encode();
        assertThat(encoded).hasSize(RtpPacket.HEADER_SIZE + 160);
    }

    @Test
    void versionBitsShouldBeCorrect() {
        RtpPacket pkt = new RtpPacket(0, 0, 0, 0, new byte[0]);
        byte[] encoded = pkt.encode();
        // First 2 bits should be 10 (version 2)
        int version = (encoded[0] >> 6) & 0x03;
        assertThat(version).isEqualTo(2);
    }

    @Test
    void payloadTypeShouldBeInByte1() {
        RtpPacket pkt = new RtpPacket(8, 0, 0, 0, new byte[0]); // PT=8 (PCMA)
        byte[] encoded = pkt.encode();
        int pt = encoded[1] & 0x7F;
        assertThat(pt).isEqualTo(8);
    }

    @Test
    void markerBitShouldBeInByte1() {
        RtpPacket pkt = new RtpPacket(0, 0, 0, 0, new byte[0]);
        pkt.setMarker(true);
        byte[] encoded = pkt.encode();
        boolean marker = (encoded[1] & 0x80) != 0;
        assertThat(marker).isTrue();

        pkt.setMarker(false);
        encoded = pkt.encode();
        marker = (encoded[1] & 0x80) != 0;
        assertThat(marker).isFalse();
    }

    @Test
    void sequenceNumberShouldWrapAt16Bits() {
        RtpPacket pkt = new RtpPacket(0, 65535, 0, 0, new byte[0]);
        byte[] encoded = pkt.encode();
        RtpPacket decoded = RtpPacket.decode(encoded);
        assertThat(decoded.getSequenceNumber()).isEqualTo(65535);
    }

    @Test
    void timestampShouldHandleLargeValues() {
        long ts = 0xFFFFFFFFL; // max 32-bit unsigned
        RtpPacket pkt = new RtpPacket(0, 0, ts, 0, new byte[0]);
        byte[] encoded = pkt.encode();
        RtpPacket decoded = RtpPacket.decode(encoded);
        assertThat(decoded.getTimestamp()).isEqualTo(ts);
    }

    @Test
    void ssrcShouldHandleLargeValues() {
        long ssrc = 0xDEADBEEFL;
        RtpPacket pkt = new RtpPacket(0, 0, 0, ssrc, new byte[0]);
        byte[] encoded = pkt.encode();
        RtpPacket decoded = RtpPacket.decode(encoded);
        assertThat(decoded.getSsrc()).isEqualTo(ssrc);
    }

    @Test
    void decodeTooShortShouldThrow() {
        assertThatThrownBy(() -> RtpPacket.decode(new byte[5]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void decodeNullShouldThrow() {
        assertThatThrownBy(() -> RtpPacket.decode(null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeWrongVersionShouldThrow() {
        byte[] data = new byte[12];
        data[0] = 0x00; // version 0
        assertThatThrownBy(() -> RtpPacket.decode(data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void payloadShouldBeDefensivelyCopied() {
        byte[] payload = {0x01, 0x02, 0x03};
        RtpPacket pkt = new RtpPacket(0, 0, 0, 0, payload);

        // Modify original — should not affect packet
        payload[0] = 0x7F;
        assertThat(pkt.getPayload()[0]).isEqualTo((byte) 0x01);

        // Modify returned payload — should not affect packet
        byte[] retrieved = pkt.getPayload();
        retrieved[0] = 0x7F;
        assertThat(pkt.getPayload()[0]).isEqualTo((byte) 0x01);
    }

    @Test
    void nullPayloadShouldBeHandled() {
        RtpPacket pkt = new RtpPacket(0, 0, 0, 0, null);
        assertThat(pkt.getPayload()).isEmpty();
        assertThat(pkt.getPayloadLength()).isEqualTo(0);
    }

    @Test
    void toStringShouldContainAllFields() {
        RtpPacket pkt = new RtpPacket(0, 42, 12345, 0xBEEF, new byte[160]);
        String str = pkt.toString();
        assertThat(str).contains("PT=0", "SEQ=42", "TS=12345", "160 bytes");
    }

    @Test
    void encodingWithTypical20msPacketShouldWorkCorrectly() {
        // Simulate a real 20ms PCMU packet
        byte[] pcmuPayload = ToneGenerator.generatePcmuPacket(1000, 0.8, 0);
        assertThat(pcmuPayload).hasSize(160);

        RtpPacket pkt = new RtpPacket(RtpPacket.PAYLOAD_TYPE_PCMU, 1, 0, 0x12345678L, pcmuPayload);
        pkt.setMarker(true); // First packet of stream

        byte[] encoded = pkt.encode();
        assertThat(encoded).hasSize(172); // 12 header + 160 payload

        RtpPacket decoded = RtpPacket.decode(encoded);
        assertThat(decoded.getPayload()).isEqualTo(pcmuPayload);
    }

    @Test
    void decodeWithLengthParameterShouldRespectLength() {
        byte[] data = new byte[200];
        // Set version 2 in first byte
        data[0] = (byte) 0x80; // V=2, no padding, no extension, CC=0
        // Length parameter says only 20 bytes valid (12 header + 8 payload)
        RtpPacket pkt = RtpPacket.decode(data, 20);
        assertThat(pkt.getPayloadLength()).isEqualTo(8);
    }
}
