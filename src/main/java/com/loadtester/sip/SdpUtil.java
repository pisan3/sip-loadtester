package com.loadtester.sip;

import java.net.InetAddress;

/**
 * SDP (Session Description Protocol) utility for building and parsing SDP bodies.
 * <p>
 * Pure string manipulation — no framework dependencies.
 * Produces minimal SDP sufficient for a basic audio call with G.711 PCMU.
 */
public class SdpUtil {

    private SdpUtil() {
    }

    /**
     * Parsed SDP media information.
     */
    public record SdpInfo(
            String connectionAddress,
            int mediaPort,
            int payloadType,
            String codecName
    ) {
    }

    /**
     * Build an SDP offer/answer body for a PCMU audio session.
     *
     * @param localIp   the IP address for the c= line
     * @param rtpPort   the RTP port for the m= line
     * @param sessionId unique session identifier
     * @return SDP body string
     */
    public static String buildSdp(String localIp, int rtpPort, long sessionId) {
        return "v=0\r\n" +
                "o=loadtester " + sessionId + " " + sessionId + " IN IP4 " + localIp + "\r\n" +
                "s=LoadTester Call\r\n" +
                "c=IN IP4 " + localIp + "\r\n" +
                "t=0 0\r\n" +
                "m=audio " + rtpPort + " RTP/AVP 0\r\n" +
                "a=rtpmap:0 PCMU/8000\r\n" +
                "a=ptime:20\r\n" +
                "a=sendrecv\r\n";
    }

    /**
     * Parse an SDP body to extract media connection details.
     *
     * @param sdp the SDP body string
     * @return parsed SDP info
     * @throws IllegalArgumentException if the SDP is malformed or missing required fields
     */
    public static SdpInfo parseSdp(String sdp) {
        if (sdp == null || sdp.isBlank()) {
            throw new IllegalArgumentException("SDP body is empty");
        }

        String connectionAddress = null;
        int mediaPort = -1;
        int payloadType = -1;
        String codecName = null;

        String[] lines = sdp.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();

            if (line.startsWith("c=")) {
                // c=IN IP4 10.0.0.1
                connectionAddress = parseConnectionAddress(line);
            } else if (line.startsWith("m=audio ")) {
                // m=audio 40000 RTP/AVP 0
                String[] parts = line.substring(8).trim().split("\\s+");
                if (parts.length >= 3) {
                    mediaPort = Integer.parseInt(parts[0]);
                    payloadType = Integer.parseInt(parts[2]);
                }
            } else if (line.startsWith("a=rtpmap:") && payloadType >= 0 && codecName == null) {
                // a=rtpmap:0 PCMU/8000
                // Only parse the rtpmap line that matches the first payload type from the m= line
                String mapping = line.substring(9).trim();
                int spaceIdx = mapping.indexOf(' ');
                if (spaceIdx > 0) {
                    int rtpmapPt = Integer.parseInt(mapping.substring(0, spaceIdx));
                    if (rtpmapPt == payloadType) {
                        int slashIdx = mapping.indexOf('/', spaceIdx);
                        if (slashIdx > 0) {
                            codecName = mapping.substring(spaceIdx + 1, slashIdx);
                        } else {
                            codecName = mapping.substring(spaceIdx + 1);
                        }
                    }
                }
            }
        }

        if (connectionAddress == null) {
            throw new IllegalArgumentException("SDP missing connection (c=) line");
        }
        if (mediaPort < 0) {
            throw new IllegalArgumentException("SDP missing or invalid media (m=audio) line");
        }

        return new SdpInfo(connectionAddress, mediaPort, payloadType,
                codecName != null ? codecName : "PCMU");
    }

    /**
     * Parse the IP address from a c= line.
     * Supports: c=IN IP4 x.x.x.x
     */
    static String parseConnectionAddress(String cLine) {
        // c=IN IP4 10.0.0.1
        String[] parts = cLine.substring(2).trim().split("\\s+");
        if (parts.length >= 3) {
            return parts[2];
        }
        throw new IllegalArgumentException("Malformed c= line: " + cLine);
    }

    /**
     * Extract SDP body from a SIP message content as a string.
     * Returns null if content is null or empty.
     */
    public static String extractSdpFromContent(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        return new String(content);
    }
}
