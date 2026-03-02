package com.loadtester.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * UDP-based RTP session implementation.
 * <p>
 * Uses a DatagramSocket to send and receive RTP packets.
 * Thread-safe for concurrent send/receive operations.
 */
public class UdpRtpSession implements RtpSession {

    private static final Logger log = LoggerFactory.getLogger(UdpRtpSession.class);
    private static final int MAX_PACKET_SIZE = 1500; // MTU

    private DatagramSocket socket;
    private InetAddress remoteAddr;
    private int remotePort;
    private volatile boolean running;
    private volatile boolean accumulatePackets = true;
    private final List<RtpPacket> receivedPackets = new CopyOnWriteArrayList<>();

    @Override
    public void start(int localPort) throws Exception {
        if (running) {
            throw new IllegalStateException("RTP session already running");
        }
        socket = new DatagramSocket(localPort);
        running = true;
        log.info("RTP session started on port {}", socket.getLocalPort());
    }

    @Override
    public void setRemote(InetAddress remoteAddr, int remotePort) {
        this.remoteAddr = remoteAddr;
        this.remotePort = remotePort;
        log.debug("RTP remote set to {}:{}", remoteAddr.getHostAddress(), remotePort);
    }

    @Override
    public void sendPacket(RtpPacket packet) throws Exception {
        if (!running || socket == null) {
            throw new IllegalStateException("RTP session not running");
        }
        if (remoteAddr == null) {
            throw new IllegalStateException("Remote address not set");
        }

        byte[] data = packet.encode();
        DatagramPacket dgram = new DatagramPacket(data, data.length, remoteAddr, remotePort);
        socket.send(dgram);
    }

    @Override
    public RtpPacket receivePacket(long timeoutMs) throws Exception {
        if (!running || socket == null) {
            throw new IllegalStateException("RTP session not running");
        }

        byte[] buffer = new byte[MAX_PACKET_SIZE];
        DatagramPacket dgram = new DatagramPacket(buffer, buffer.length);
        socket.setSoTimeout((int) timeoutMs);

        try {
            socket.receive(dgram);
            RtpPacket pkt = RtpPacket.decode(dgram.getData(), dgram.getLength());
            if (accumulatePackets) {
                receivedPackets.add(pkt);
            }
            return pkt;
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    @Override
    public int getLocalPort() {
        return socket != null ? socket.getLocalPort() : -1;
    }

    @Override
    public List<RtpPacket> getReceivedPackets() {
        return Collections.unmodifiableList(new ArrayList<>(receivedPackets));
    }

    @Override
    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
            log.info("RTP session stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void reset() {
        if (running) {
            stop();
        }
        receivedPackets.clear();
        remoteAddr = null;
        remotePort = 0;
        socket = null;
        log.debug("RTP session reset — ready for reuse");
    }

    @Override
    public void setAccumulatePackets(boolean accumulate) {
        this.accumulatePackets = accumulate;
    }
}
