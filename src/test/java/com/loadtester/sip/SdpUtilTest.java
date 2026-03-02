package com.loadtester.sip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SdpUtilTest {

    @Test
    void buildSdpShouldContainRequiredLines() {
        String sdp = SdpUtil.buildSdp("10.0.0.1", 40000, 12345);

        assertThat(sdp).contains("v=0");
        assertThat(sdp).contains("o=loadtester 12345 12345 IN IP4 10.0.0.1");
        assertThat(sdp).contains("s=LoadTester Call");
        assertThat(sdp).contains("c=IN IP4 10.0.0.1");
        assertThat(sdp).contains("t=0 0");
        assertThat(sdp).contains("m=audio 40000 RTP/AVP 0");
        assertThat(sdp).contains("a=rtpmap:0 PCMU/8000");
        assertThat(sdp).contains("a=ptime:20");
        assertThat(sdp).contains("a=sendrecv");
    }

    @Test
    void buildSdpShouldUseCrLfLineEndings() {
        String sdp = SdpUtil.buildSdp("10.0.0.1", 40000, 12345);
        // All lines should end with \r\n
        String[] lines = sdp.split("\r\n");
        assertThat(lines.length).isGreaterThanOrEqualTo(7);
    }

    @Test
    void parseSdpShouldExtractConnectionAddress() {
        String sdp = "v=0\r\n" +
                "o=- 1234 1234 IN IP4 192.168.1.100\r\n" +
                "s=Session\r\n" +
                "c=IN IP4 192.168.1.100\r\n" +
                "t=0 0\r\n" +
                "m=audio 30000 RTP/AVP 0\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n";

        SdpUtil.SdpInfo info = SdpUtil.parseSdp(sdp);
        assertThat(info.connectionAddress()).isEqualTo("192.168.1.100");
        assertThat(info.mediaPort()).isEqualTo(30000);
        assertThat(info.payloadType()).isEqualTo(0);
        assertThat(info.codecName()).isEqualTo("PCMU");
    }

    @Test
    void parseSdpShouldHandlePcmaCodec() {
        String sdp = "v=0\r\n" +
                "c=IN IP4 10.0.0.5\r\n" +
                "m=audio 20000 RTP/AVP 8\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n";

        SdpUtil.SdpInfo info = SdpUtil.parseSdp(sdp);
        assertThat(info.payloadType()).isEqualTo(8);
        assertThat(info.codecName()).isEqualTo("PCMA");
    }

    @Test
    void buildAndParseShouldRoundTrip() {
        String sdp = SdpUtil.buildSdp("172.16.0.50", 45678, 9999);
        SdpUtil.SdpInfo info = SdpUtil.parseSdp(sdp);

        assertThat(info.connectionAddress()).isEqualTo("172.16.0.50");
        assertThat(info.mediaPort()).isEqualTo(45678);
        assertThat(info.payloadType()).isEqualTo(0);
        assertThat(info.codecName()).isEqualTo("PCMU");
    }

    @Test
    void parseSdpShouldHandleLfLineEndings() {
        String sdp = "v=0\n" +
                "c=IN IP4 10.0.0.1\n" +
                "m=audio 5000 RTP/AVP 0\n" +
                "a=rtpmap:0 PCMU/8000\n";

        SdpUtil.SdpInfo info = SdpUtil.parseSdp(sdp);
        assertThat(info.connectionAddress()).isEqualTo("10.0.0.1");
        assertThat(info.mediaPort()).isEqualTo(5000);
    }

    @Test
    void parseSdpWithoutConnectionShouldThrow() {
        String sdp = "v=0\r\n" +
                "m=audio 5000 RTP/AVP 0\r\n";

        assertThatThrownBy(() -> SdpUtil.parseSdp(sdp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection");
    }

    @Test
    void parseSdpWithoutMediaShouldThrow() {
        String sdp = "v=0\r\n" +
                "c=IN IP4 10.0.0.1\r\n";

        assertThatThrownBy(() -> SdpUtil.parseSdp(sdp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("media");
    }

    @Test
    void parseSdpNullShouldThrow() {
        assertThatThrownBy(() -> SdpUtil.parseSdp(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseSdpBlankShouldThrow() {
        assertThatThrownBy(() -> SdpUtil.parseSdp(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseSdpWithoutRtpmapShouldDefaultToPcmu() {
        String sdp = "v=0\r\n" +
                "c=IN IP4 10.0.0.1\r\n" +
                "m=audio 5000 RTP/AVP 0\r\n";

        SdpUtil.SdpInfo info = SdpUtil.parseSdp(sdp);
        assertThat(info.codecName()).isEqualTo("PCMU");
    }

    @Test
    void extractSdpFromContentShouldHandleNull() {
        assertThat(SdpUtil.extractSdpFromContent(null)).isNull();
    }

    @Test
    void extractSdpFromContentShouldHandleEmpty() {
        assertThat(SdpUtil.extractSdpFromContent(new byte[0])).isNull();
    }

    @Test
    void extractSdpFromContentShouldReturnString() {
        String sdp = "v=0\r\nc=IN IP4 10.0.0.1\r\n";
        byte[] content = sdp.getBytes();
        String result = SdpUtil.extractSdpFromContent(content);
        assertThat(result).isEqualTo(sdp);
    }

    @Test
    void parseSdpFromRealWorldAsteriskShouldWork() {
        // Typical Asterisk SDP
        String sdp = "v=0\r\n" +
                "o=root 31589 31589 IN IP4 192.168.1.1\r\n" +
                "s=session\r\n" +
                "c=IN IP4 192.168.1.1\r\n" +
                "t=0 0\r\n" +
                "m=audio 17000 RTP/AVP 0 8 101\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=rtpmap:8 PCMA/8000\r\n" +
                "a=rtpmap:101 telephone-event/8000\r\n" +
                "a=fmtp:101 0-16\r\n" +
                "a=ptime:20\r\n" +
                "a=sendrecv\r\n";

        SdpUtil.SdpInfo info = SdpUtil.parseSdp(sdp);
        assertThat(info.connectionAddress()).isEqualTo("192.168.1.1");
        assertThat(info.mediaPort()).isEqualTo(17000);
        // First payload type in m= line
        assertThat(info.payloadType()).isEqualTo(0);
        assertThat(info.codecName()).isEqualTo("PCMU");
    }

    @Test
    void parseConnectionAddressShouldExtractIp() {
        assertThat(SdpUtil.parseConnectionAddress("c=IN IP4 10.0.0.1")).isEqualTo("10.0.0.1");
        assertThat(SdpUtil.parseConnectionAddress("c=IN IP4 192.168.100.200")).isEqualTo("192.168.100.200");
    }

    @Test
    void parseMalformedConnectionShouldThrow() {
        assertThatThrownBy(() -> SdpUtil.parseConnectionAddress("c=broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed");
    }
}
