package com.loadtester.media;

import java.net.InetAddress;
import java.util.List;

/**
 * Interface for RTP media session — send/receive RTP packets.
 * <p>
 * Abstraction allows testing with real UDP sockets or mock implementations.
 */
public interface RtpSession {

    /**
     * Start the RTP session, binding to a local port.
     *
     * @param localPort the local UDP port to bind to (0 for auto-assign)
     */
    void start(int localPort) throws Exception;

    /**
     * Set the remote endpoint to send RTP to.
     */
    void setRemote(InetAddress remoteAddr, int remotePort);

    /**
     * Send an RTP packet to the remote endpoint.
     */
    void sendPacket(RtpPacket packet) throws Exception;

    /**
     * Receive an RTP packet with timeout.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the received packet, or null if timeout
     */
    RtpPacket receivePacket(long timeoutMs) throws Exception;

    /**
     * Get the local port this session is bound to.
     */
    int getLocalPort();

    /**
     * Get all packets received so far.
     */
    List<RtpPacket> getReceivedPackets();

    /**
     * Stop the RTP session and release resources.
     */
    void stop();

    /**
     * Check if the session is running.
     */
    boolean isRunning();
}
