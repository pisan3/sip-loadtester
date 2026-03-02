package com.loadtester.media;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Represents an RTP packet with encode/decode support.
 * <p>
 * RTP header format (RFC 3550):
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           timestamp                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           synchronization source (SSRC) identifier            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 */
public class RtpPacket {

    public static final int HEADER_SIZE = 12;
    public static final int RTP_VERSION = 2;
    public static final int PAYLOAD_TYPE_PCMU = 0;

    private int version = RTP_VERSION;
    private boolean padding;
    private boolean extension;
    private int csrcCount;
    private boolean marker;
    private int payloadType;
    private int sequenceNumber;
    private long timestamp;
    private long ssrc;
    private byte[] payload;

    public RtpPacket() {
    }

    public RtpPacket(int payloadType, int sequenceNumber, long timestamp, long ssrc, byte[] payload) {
        this.version = RTP_VERSION;
        this.payloadType = payloadType;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.ssrc = ssrc;
        this.payload = payload != null ? Arrays.copyOf(payload, payload.length) : new byte[0];
    }

    /**
     * Encode this RTP packet to a byte array for transmission.
     */
    public byte[] encode() {
        byte[] data = new byte[HEADER_SIZE + payload.length];
        ByteBuffer buf = ByteBuffer.wrap(data);

        // Byte 0: V=2, P, X, CC
        int byte0 = (version & 0x03) << 6;
        if (padding) byte0 |= 0x20;
        if (extension) byte0 |= 0x10;
        byte0 |= (csrcCount & 0x0F);
        buf.put((byte) byte0);

        // Byte 1: M, PT
        int byte1 = (payloadType & 0x7F);
        if (marker) byte1 |= 0x80;
        buf.put((byte) byte1);

        // Bytes 2-3: Sequence number
        buf.putShort((short) (sequenceNumber & 0xFFFF));

        // Bytes 4-7: Timestamp
        buf.putInt((int) (timestamp & 0xFFFFFFFFL));

        // Bytes 8-11: SSRC
        buf.putInt((int) (ssrc & 0xFFFFFFFFL));

        // Payload
        buf.put(payload);

        return data;
    }

    /**
     * Decode an RTP packet from raw bytes.
     *
     * @param data   the raw bytes
     * @param length number of valid bytes in the array
     * @return decoded RtpPacket
     * @throws IllegalArgumentException if data is too short or invalid
     */
    public static RtpPacket decode(byte[] data, int length) {
        if (data == null || length < HEADER_SIZE) {
            throw new IllegalArgumentException("RTP packet too short: need at least " + HEADER_SIZE + " bytes");
        }

        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        RtpPacket pkt = new RtpPacket();

        int byte0 = buf.get() & 0xFF;
        pkt.version = (byte0 >> 6) & 0x03;
        pkt.padding = (byte0 & 0x20) != 0;
        pkt.extension = (byte0 & 0x10) != 0;
        pkt.csrcCount = byte0 & 0x0F;

        if (pkt.version != RTP_VERSION) {
            throw new IllegalArgumentException("Unsupported RTP version: " + pkt.version);
        }

        int byte1 = buf.get() & 0xFF;
        pkt.marker = (byte1 & 0x80) != 0;
        pkt.payloadType = byte1 & 0x7F;

        pkt.sequenceNumber = buf.getShort() & 0xFFFF;
        pkt.timestamp = buf.getInt() & 0xFFFFFFFFL;
        pkt.ssrc = buf.getInt() & 0xFFFFFFFFL;

        int payloadLength = length - HEADER_SIZE;
        pkt.payload = new byte[payloadLength];
        buf.get(pkt.payload);

        return pkt;
    }

    /**
     * Convenience method: decode full array.
     */
    public static RtpPacket decode(byte[] data) {
        return decode(data, data.length);
    }

    // --- Getters and setters ---

    public int getVersion() { return version; }
    public boolean isPadding() { return padding; }
    public boolean isExtension() { return extension; }
    public int getCsrcCount() { return csrcCount; }
    public boolean isMarker() { return marker; }
    public void setMarker(boolean marker) { this.marker = marker; }
    public int getPayloadType() { return payloadType; }
    public void setPayloadType(int payloadType) { this.payloadType = payloadType; }
    public int getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(int sequenceNumber) { this.sequenceNumber = sequenceNumber; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public long getSsrc() { return ssrc; }
    public void setSsrc(long ssrc) { this.ssrc = ssrc; }
    public byte[] getPayload() { return payload != null ? Arrays.copyOf(payload, payload.length) : new byte[0]; }
    public void setPayload(byte[] payload) { this.payload = payload != null ? Arrays.copyOf(payload, payload.length) : new byte[0]; }
    public int getPayloadLength() { return payload != null ? payload.length : 0; }

    @Override
    public String toString() {
        return String.format("RTP[V=%d PT=%d SEQ=%d TS=%d SSRC=%d payload=%d bytes]",
                version, payloadType, sequenceNumber, timestamp, ssrc, getPayloadLength());
    }
}
