package com.loadtester.sip;

import javax.sip.*;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import java.util.Properties;

/**
 * Default JAIN-SIP stack factory using the reference implementation.
 */
public class DefaultSipStackFactory implements SipStackFactory {

    @Override
    public SipStack createSipStack(Properties properties) throws PeerUnavailableException {
        SipFactory sipFactory = SipFactory.getInstance();
        sipFactory.setPathName("gov.nist");
        return sipFactory.createSipStack(properties);
    }

    @Override
    public ListeningPoint createListeningPoint(SipStack stack, String host, int port, String transport)
            throws Exception {
        return stack.createListeningPoint(host, port, transport);
    }

    @Override
    public SipProvider createSipProvider(SipStack stack, ListeningPoint listeningPoint) throws Exception {
        return stack.createSipProvider(listeningPoint);
    }

    /**
     * Create JAIN-SIP factories for building messages.
     * These are stateless and can be shared.
     */
    public static AddressFactory createAddressFactory() throws PeerUnavailableException {
        return SipFactory.getInstance().createAddressFactory();
    }

    public static HeaderFactory createHeaderFactory() throws PeerUnavailableException {
        return SipFactory.getInstance().createHeaderFactory();
    }

    public static MessageFactory createMessageFactory() throws PeerUnavailableException {
        return SipFactory.getInstance().createMessageFactory();
    }
}
