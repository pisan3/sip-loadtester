package com.loadtester.sip;

import javax.sip.*;
import java.util.Properties;

/**
 * Factory interface for creating JAIN-SIP stack components.
 * <p>
 * Abstraction layer that allows mocking the JAIN-SIP stack in tests.
 */
public interface SipStackFactory {

    /**
     * Create a new SipStack instance.
     *
     * @param properties stack configuration properties
     * @return the created SipStack
     */
    SipStack createSipStack(Properties properties) throws PeerUnavailableException;

    /**
     * Create a ListeningPoint on the stack.
     *
     * @param stack     the SIP stack
     * @param host      local IP address
     * @param port      local port
     * @param transport transport protocol (udp, tcp, tls)
     * @return the listening point
     */
    ListeningPoint createListeningPoint(SipStack stack, String host, int port, String transport)
            throws Exception;

    /**
     * Create a SipProvider bound to a ListeningPoint.
     *
     * @param stack          the SIP stack
     * @param listeningPoint the listening point
     * @return the SIP provider
     */
    SipProvider createSipProvider(SipStack stack, ListeningPoint listeningPoint) throws Exception;
}
