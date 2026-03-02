package com.loadtester.sip;

import com.loadtester.config.SipAccountConfig;
import com.loadtester.media.RtpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sip.*;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.*;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Simulated SIP phone endpoint.
 * <p>
 * Handles REGISTER, INVITE (outgoing and incoming), ACK, and BYE.
 * Supports digest authentication for 401/407 challenges.
 * All JAIN-SIP interactions go through injected factories for testability.
 */
public class SipPhone implements SipListener {

    private static final Logger log = LoggerFactory.getLogger(SipPhone.class);

    private final SipAccountConfig config;
    private final SipPhoneListener listener;
    private final RtpSession rtpSession;
    private final Supplier<RtpSession> rtpSessionFactory;

    // JAIN-SIP components
    private SipStack sipStack;
    private SipProvider sipProvider;
    private ListeningPoint listeningPoint;
    private AddressFactory addressFactory;
    private HeaderFactory headerFactory;
    private MessageFactory messageFactory;

    // State
    private final AtomicLong cseqCounter = new AtomicLong(1);
    private String localIp;
    private int localSipPort;
    private String callId;
    private Dialog currentDialog;
    private ClientTransaction currentClientTransaction;
    private ServerTransaction currentServerTransaction;
    private String localTag;
    private String remoteTag;
    private SdpUtil.SdpInfo remoteSdpInfo;

    // Multi-call support: maps Call-ID -> CallLeg for incoming calls
    private final ConcurrentHashMap<String, CallLeg> callLegs = new ConcurrentHashMap<>();

    // For re-sending requests with auth
    private Request lastSentRequest;
    private String lastMethod;
    private volatile boolean pendingUnregister;
    private final CountDownLatch unregisterLatch = new CountDownLatch(1);

    public SipPhone(SipAccountConfig config, SipStackFactory stackFactory,
                    RtpSession rtpSession, SipPhoneListener listener) {
        this(config, stackFactory, rtpSession, listener, null);
    }

    /**
     * Constructor with an RTP session factory for multi-call B-side support.
     * When rtpSessionFactory is provided, each incoming call gets its own RTP session.
     * When null, the single rtpSession is used (single-call mode).
     */
    public SipPhone(SipAccountConfig config, SipStackFactory stackFactory,
                    RtpSession rtpSession, SipPhoneListener listener,
                    Supplier<RtpSession> rtpSessionFactory) {
        this.config = Objects.requireNonNull(config, "config");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.rtpSession = Objects.requireNonNull(rtpSession, "rtpSession");
        this.rtpSessionFactory = rtpSessionFactory;

        this.localTag = Long.toHexString(new Random().nextLong());
    }

    /**
     * Initialize the SIP stack and start listening.
     *
     * @param stackFactory factory for creating SIP components
     * @param localIp      local IP address to bind to
     * @param localSipPort local SIP port (0 for auto)
     */
    public void initialize(SipStackFactory stackFactory, String localIp, int localSipPort) throws Exception {
        this.localIp = localIp;
        this.localSipPort = localSipPort;

        Properties props = new Properties();
        props.setProperty("javax.sip.STACK_NAME", "loadtester-" + config.username() + "-" + System.nanoTime());
        props.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "32");
        props.setProperty("gov.nist.javax.sip.DEBUG_LOG", "sip-debug-" + config.username() + ".log");
        props.setProperty("gov.nist.javax.sip.SERVER_LOG", "sip-server-" + config.username() + ".log");
        // Set outbound proxy so all requests are sent to the configured proxy
        props.setProperty("javax.sip.OUTBOUND_PROXY", config.proxyHost() + ":" + config.proxyPort() + "/udp");

        sipStack = stackFactory.createSipStack(props);
        listeningPoint = stackFactory.createListeningPoint(sipStack, localIp, localSipPort, "udp");
        sipProvider = stackFactory.createSipProvider(sipStack, listeningPoint);
        sipProvider.addSipListener(this);

        // Update actual port if auto-assigned
        this.localSipPort = listeningPoint.getPort();

        // Create message factories
        SipFactory factory = SipFactory.getInstance();
        factory.setPathName("gov.nist");
        addressFactory = factory.createAddressFactory();
        headerFactory = factory.createHeaderFactory();
        messageFactory = factory.createMessageFactory();

        log.info("[{}] SIP phone initialized on {}:{}", config.username(), localIp, this.localSipPort);
    }

    /**
     * Send a REGISTER request to the proxy.
     */
    public void register() throws Exception {
        String sipUser = config.effectiveAuthUsername();
        SipURI requestUri = addressFactory.createSipURI(null, config.domain());

        SipURI fromUri = addressFactory.createSipURI(sipUser, config.domain());
        Address fromAddress = addressFactory.createAddress(fromUri);
        fromAddress.setDisplayName(config.displayName());
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, localTag);

        Address toAddress = addressFactory.createAddress(addressFactory.createSipURI(sipUser, config.domain()));
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

        List<ViaHeader> viaHeaders = createViaHeaders();
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);
        CallIdHeader callIdHeader = sipProvider.getNewCallId();
        CSeqHeader cseqHeader = headerFactory.createCSeqHeader(cseqCounter.getAndIncrement(), Request.REGISTER);

        Request request = messageFactory.createRequest(requestUri, Request.REGISTER,
                callIdHeader, cseqHeader, fromHeader, toHeader, viaHeaders, maxForwards);

        // Contact header
        SipURI contactUri = addressFactory.createSipURI(sipUser, localIp);
        contactUri.setPort(localSipPort);
        contactUri.setTransportParam("udp");
        Address contactAddress = addressFactory.createAddress(contactUri);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
        request.addHeader(contactHeader);

        // Expires
        ExpiresHeader expiresHeader = headerFactory.createExpiresHeader(60);
        request.addHeader(expiresHeader);

        lastSentRequest = request;
        lastMethod = Request.REGISTER;

        ClientTransaction ct = sipProvider.getNewClientTransaction(request);
        ct.sendRequest();
        log.info("[{}] REGISTER sent to {}", config.username(), config.proxyAddress());
    }

    /**
     * Send an INVITE to the target SIP URI.
     *
     * @param targetUri the SIP URI to call (e.g. "sip:bob@example.com")
     */
    public void call(String targetUri) throws Exception {
        String sipUser = config.effectiveAuthUsername();
        // Start RTP session to get a local port for SDP
        if (!rtpSession.isRunning()) {
            rtpSession.start(0);
        }

        SipURI requestUri = (SipURI) addressFactory.createURI(targetUri);

        SipURI fromUri = addressFactory.createSipURI(sipUser, config.domain());
        Address fromAddress = addressFactory.createAddress(fromUri);
        fromAddress.setDisplayName(config.displayName());
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, localTag);

        Address toAddress = addressFactory.createAddress(addressFactory.createURI(targetUri));
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

        List<ViaHeader> viaHeaders = createViaHeaders();
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);
        CallIdHeader callIdHeader = sipProvider.getNewCallId();
        this.callId = callIdHeader.getCallId();
        CSeqHeader cseqHeader = headerFactory.createCSeqHeader(cseqCounter.getAndIncrement(), Request.INVITE);

        Request request = messageFactory.createRequest(requestUri, Request.INVITE,
                callIdHeader, cseqHeader, fromHeader, toHeader, viaHeaders, maxForwards);

        // Contact header
        SipURI contactUri = addressFactory.createSipURI(sipUser, localIp);
        contactUri.setPort(localSipPort);
        contactUri.setTransportParam("udp");
        Address contactAddress = addressFactory.createAddress(contactUri);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
        request.addHeader(contactHeader);

        // SDP body
        String sdp = SdpUtil.buildSdp(localIp, rtpSession.getLocalPort(), System.currentTimeMillis());
        ContentTypeHeader contentType = headerFactory.createContentTypeHeader("application", "sdp");
        request.setContent(sdp, contentType);

        lastSentRequest = request;
        lastMethod = Request.INVITE;

        ClientTransaction ct = sipProvider.getNewClientTransaction(request);
        currentClientTransaction = ct;
        ct.sendRequest();
        log.info("[{}] INVITE sent to {}", config.username(), targetUri);
    }

    /**
     * Answer an incoming call with 200 OK.
     */
    public void answer() throws Exception {
        if (currentServerTransaction == null) {
            throw new IllegalStateException("No incoming call to answer");
        }

        // Start RTP session for receiving
        if (!rtpSession.isRunning()) {
            rtpSession.start(0);
        }

        Request request = currentServerTransaction.getRequest();
        Response response = messageFactory.createResponse(Response.OK, request);

        // To header with tag
        ToHeader toHeader = (ToHeader) response.getHeader(ToHeader.NAME);
        toHeader.setTag(localTag);

        // Contact header
        SipURI contactUri = addressFactory.createSipURI(config.effectiveAuthUsername(), localIp);
        contactUri.setPort(localSipPort);
        contactUri.setTransportParam("udp");
        Address contactAddress = addressFactory.createAddress(contactUri);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
        response.addHeader(contactHeader);

        // SDP answer
        String sdp = SdpUtil.buildSdp(localIp, rtpSession.getLocalPort(), System.currentTimeMillis());
        ContentTypeHeader contentType = headerFactory.createContentTypeHeader("application", "sdp");
        response.setContent(sdp, contentType);

        currentServerTransaction.sendResponse(response);
        currentDialog = currentServerTransaction.getDialog();
        log.info("[{}] 200 OK sent (call answered)", config.username());
    }

    /**
     * Send BYE to end the current call.
     */
    public void hangup() throws Exception {
        if (currentDialog == null) {
            throw new IllegalStateException("No active call to hang up");
        }

        Request byeRequest = currentDialog.createRequest(Request.BYE);
        ClientTransaction ct = sipProvider.getNewClientTransaction(byeRequest);
        currentDialog.sendRequest(ct);
        log.info("[{}] BYE sent", config.username());
    }

    /**
     * Send 180 Ringing provisional response.
     */
    public void sendRinging() throws Exception {
        if (currentServerTransaction == null) {
            throw new IllegalStateException("No incoming call to ring");
        }
        Request request = currentServerTransaction.getRequest();
        Response ringing = messageFactory.createResponse(Response.RINGING, request);

        ToHeader toHeader = (ToHeader) ringing.getHeader(ToHeader.NAME);
        toHeader.setTag(localTag);

        currentServerTransaction.sendResponse(ringing);
        log.info("[{}] 180 Ringing sent", config.username());
    }

    /**
     * Send 180 Ringing for a specific call (multi-call B-side).
     */
    public void sendRinging(String callId) throws Exception {
        CallLeg leg = callLegs.get(callId);
        if (leg == null) {
            throw new IllegalStateException("No call leg found for Call-ID: " + callId);
        }
        Request request = leg.getServerTransaction().getRequest();
        Response ringing = messageFactory.createResponse(Response.RINGING, request);

        ToHeader toHeader = (ToHeader) ringing.getHeader(ToHeader.NAME);
        toHeader.setTag(localTag);

        leg.getServerTransaction().sendResponse(ringing);
        log.info("[{}] 180 Ringing sent for call {}", config.username(), callId);
    }

    /**
     * Answer a specific incoming call with 200 OK (multi-call B-side).
     */
    public void answer(String callId) throws Exception {
        CallLeg leg = callLegs.get(callId);
        if (leg == null) {
            throw new IllegalStateException("No call leg found for Call-ID: " + callId);
        }

        RtpSession legRtp = leg.getRtpSession();
        if (!legRtp.isRunning()) {
            legRtp.start(0);
        }

        Request request = leg.getServerTransaction().getRequest();
        Response response = messageFactory.createResponse(Response.OK, request);

        // To header with tag
        ToHeader toHeader = (ToHeader) response.getHeader(ToHeader.NAME);
        toHeader.setTag(localTag);

        // Contact header
        SipURI contactUri = addressFactory.createSipURI(config.effectiveAuthUsername(), localIp);
        contactUri.setPort(localSipPort);
        contactUri.setTransportParam("udp");
        Address contactAddress = addressFactory.createAddress(contactUri);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
        response.addHeader(contactHeader);

        // SDP answer
        String sdp = SdpUtil.buildSdp(localIp, legRtp.getLocalPort(), System.currentTimeMillis());
        ContentTypeHeader contentType = headerFactory.createContentTypeHeader("application", "sdp");
        response.setContent(sdp, contentType);

        leg.getServerTransaction().sendResponse(response);
        leg.setDialog(leg.getServerTransaction().getDialog());
        log.info("[{}] 200 OK sent for call {} (RTP port {})", config.username(), callId, legRtp.getLocalPort());
    }

    /**
     * Get a CallLeg by Call-ID.
     */
    public CallLeg getCallLeg(String callId) {
        return callLegs.get(callId);
    }

    /**
     * Get all active call legs (for multi-call support).
     */
    public Collection<CallLeg> getCallLegs() {
        return Collections.unmodifiableCollection(callLegs.values());
    }

    /**
     * Send a REGISTER with Expires: 0 to de-register from the proxy.
     * This is fire-and-forget — we don't wait for the response.
     */
    public void unregister() throws Exception {
        if (sipProvider == null) return;

        String sipUser = config.effectiveAuthUsername();
        SipURI requestUri = addressFactory.createSipURI(null, config.domain());

        SipURI fromUri = addressFactory.createSipURI(sipUser, config.domain());
        Address fromAddress = addressFactory.createAddress(fromUri);
        fromAddress.setDisplayName(config.displayName());
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, localTag);

        Address toAddress = addressFactory.createAddress(addressFactory.createSipURI(sipUser, config.domain()));
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

        List<ViaHeader> viaHeaders = createViaHeaders();
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);
        CallIdHeader callIdHeader = sipProvider.getNewCallId();
        CSeqHeader cseqHeader = headerFactory.createCSeqHeader(cseqCounter.getAndIncrement(), Request.REGISTER);

        Request request = messageFactory.createRequest(requestUri, Request.REGISTER,
                callIdHeader, cseqHeader, fromHeader, toHeader, viaHeaders, maxForwards);

        // Contact header with wildcard ("*") to de-register all bindings
        ContactHeader contactHeader = headerFactory.createContactHeader();
        request.addHeader(contactHeader);

        // Expires: 0
        ExpiresHeader expiresHeader = headerFactory.createExpiresHeader(0);
        request.addHeader(expiresHeader);

        lastSentRequest = request;
        lastMethod = Request.REGISTER;
        pendingUnregister = true;

        ClientTransaction ct = sipProvider.getNewClientTransaction(request);
        ct.sendRequest();
        log.info("[{}] UNREGISTER (Expires: 0) sent to {}", config.username(), config.proxyAddress());
    }

    /**
     * Shutdown the SIP stack and release resources.
     */
    public void shutdown() {
        // Stop all call leg RTP sessions
        for (CallLeg leg : callLegs.values()) {
            RtpSession legRtp = leg.getRtpSession();
            if (legRtp != null && legRtp.isRunning()) {
                try { legRtp.stop(); } catch (Exception e) { /* ignore */ }
            }
        }
        callLegs.clear();

        if (rtpSession != null && rtpSession.isRunning()) {
            rtpSession.stop();
        }
        if (sipProvider != null) {
            try {
                unregister();
                // Wait for the de-registration to complete (401 challenge + 200 OK)
                if (!unregisterLatch.await(2, TimeUnit.SECONDS)) {
                    log.warn("[{}] De-registration timed out during shutdown", config.username());
                }
            } catch (Exception e) {
                log.debug("[{}] Unregister during shutdown failed: {}", config.username(), e.getMessage());
            }
        }
        if (sipStack != null) {
            sipStack.stop();
            log.info("[{}] SIP phone shutdown", config.username());
        }
    }

    // --- SipListener implementation ---

    @Override
    public void processRequest(RequestEvent requestEvent) {
        Request request = requestEvent.getRequest();
        String method = request.getMethod();
        log.debug("[{}] Received request: {}", config.username(), method);

        try {
            switch (method) {
                case Request.INVITE -> handleIncomingInvite(requestEvent);
                case Request.ACK -> handleAck(requestEvent);
                case Request.BYE -> handleBye(requestEvent);
                case Request.CANCEL -> handleCancel(requestEvent);
                default -> log.warn("[{}] Unhandled request method: {}", config.username(), method);
            }
        } catch (Exception e) {
            log.error("[{}] Error processing request {}: {}", config.username(), method, e.getMessage(), e);
            listener.onError(e);
        }
    }

    @Override
    public void processResponse(ResponseEvent responseEvent) {
        Response response = responseEvent.getResponse();
        int statusCode = response.getStatusCode();
        CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        String method = cseq != null ? cseq.getMethod() : "UNKNOWN";
        log.debug("[{}] Received response: {} {}", config.username(), statusCode, method);

        try {
            switch (statusCode) {
                case Response.UNAUTHORIZED, Response.PROXY_AUTHENTICATION_REQUIRED ->
                        handleAuthChallenge(responseEvent, statusCode);
                case Response.OK -> handleOk(responseEvent, method);
                case Response.RINGING -> {
                    log.info("[{}] 180 Ringing received", config.username());
                    listener.onRinging();
                }
                case Response.TRYING -> log.debug("[{}] 100 Trying received", config.username());
                case Response.SESSION_PROGRESS -> {
                    log.info("[{}] 183 Session Progress received", config.username());
                }
                default -> {
                    if (statusCode >= 400) {
                        log.warn("[{}] Error response: {} {} for {}", config.username(),
                                statusCode, response.getReasonPhrase(), method);
                        if (Request.REGISTER.equals(method)) {
                            listener.onRegistrationFailed(statusCode, response.getReasonPhrase());
                        } else if (Request.INVITE.equals(method)) {
                            // For non-2xx final responses to INVITE, the ACK is sent
                            // automatically by the JAIN-SIP transaction layer.
                            // We only need to notify the listener.
                            listener.onCallFailed(statusCode, response.getReasonPhrase());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[{}] Error processing response: {}", config.username(), e.getMessage(), e);
            listener.onError(e);
        }
    }

    @Override
    public void processTimeout(TimeoutEvent timeoutEvent) {
        log.warn("[{}] SIP timeout event", config.username());
        listener.onError(new Exception("SIP transaction timeout"));
    }

    @Override
    public void processIOException(IOExceptionEvent ioExceptionEvent) {
        log.error("[{}] SIP I/O exception: {}", config.username(), ioExceptionEvent.getHost());
        listener.onError(new Exception("SIP I/O exception to " + ioExceptionEvent.getHost()));
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent event) {
        log.debug("[{}] Transaction terminated", config.username());
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent event) {
        log.debug("[{}] Dialog terminated", config.username());
    }

    // --- Internal handlers ---

    private void handleIncomingInvite(RequestEvent event) throws Exception {
        Request request = event.getRequest();
        ServerTransaction st = event.getServerTransaction();
        if (st == null) {
            st = sipProvider.getNewServerTransaction(request);
        }
        currentServerTransaction = st;

        // Send 100 Trying
        Response trying = messageFactory.createResponse(Response.TRYING, request);
        st.sendResponse(trying);

        // Extract SDP from INVITE
        byte[] content = request.getRawContent();
        String sdpBody = SdpUtil.extractSdpFromContent(content);
        SdpUtil.SdpInfo sdpInfo = null;
        if (sdpBody != null) {
            sdpInfo = SdpUtil.parseSdp(sdpBody);
            remoteSdpInfo = sdpInfo;
            log.info("[{}] Incoming call SDP: {}:{}", config.username(),
                    sdpInfo.connectionAddress(), sdpInfo.mediaPort());
        }

        // Extract Call-ID for multi-call support
        CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
        String incomingCallId = callIdHeader.getCallId();

        // Create CallLeg with a dedicated RTP session (or shared one for single-call)
        RtpSession legRtp = (rtpSessionFactory != null) ? rtpSessionFactory.get() : rtpSession;
        CallLeg leg = new CallLeg(incomingCallId, st, sdpInfo, legRtp);
        callLegs.put(incomingCallId, leg);

        FromHeader fromHeader = (FromHeader) request.getHeader(FromHeader.NAME);
        String callerUri = fromHeader.getAddress().getURI().toString();

        // Call 3-arg listener (which defaults to 2-arg for backward compat)
        listener.onIncomingCall(incomingCallId, callerUri, sdpBody);
    }

    private void handleAck(RequestEvent event) {
        log.info("[{}] ACK received — call established", config.username());
    }

    private void handleBye(RequestEvent event) throws Exception {
        Request request = event.getRequest();
        ServerTransaction st = event.getServerTransaction();
        if (st == null) {
            st = sipProvider.getNewServerTransaction(request);
        }

        Response ok = messageFactory.createResponse(Response.OK, request);
        st.sendResponse(ok);

        // Extract Call-ID and clean up the call leg
        CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
        String byeCallId = callIdHeader.getCallId();
        CallLeg leg = callLegs.remove(byeCallId);
        if (leg != null) {
            RtpSession legRtp = leg.getRtpSession();
            if (legRtp != null && legRtp.isRunning()) {
                legRtp.stop();
            }
        }

        log.info("[{}] BYE received, 200 OK sent (callId={})", config.username(), byeCallId);
        listener.onBye(byeCallId);
    }

    private void handleCancel(RequestEvent event) throws Exception {
        Request request = event.getRequest();
        ServerTransaction st = event.getServerTransaction();
        if (st == null) {
            st = sipProvider.getNewServerTransaction(request);
        }
        Response ok = messageFactory.createResponse(Response.OK, request);
        st.sendResponse(ok);

        // Look up the call leg for the cancelled INVITE
        CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
        String cancelCallId = callIdHeader.getCallId();
        CallLeg leg = callLegs.remove(cancelCallId);

        // Also send 487 Request Terminated for the original INVITE
        ServerTransaction inviteSt = (leg != null) ? leg.getServerTransaction() : currentServerTransaction;
        if (inviteSt != null) {
            Response terminated = messageFactory.createResponse(
                    Response.REQUEST_TERMINATED, inviteSt.getRequest());
            inviteSt.sendResponse(terminated);
        }
        log.info("[{}] CANCEL received, call cancelled (callId={})", config.username(), cancelCallId);
    }

    private void handleAuthChallenge(ResponseEvent event, int statusCode) throws Exception {
        Response response = event.getResponse();
        String challengeHeaderName = (statusCode == Response.UNAUTHORIZED)
                ? "WWW-Authenticate" : "Proxy-Authenticate";

        Header challengeHeader = response.getHeader(challengeHeaderName);
        if (challengeHeader == null) {
            log.error("[{}] {} response without {} header", config.username(), statusCode, challengeHeaderName);
            return;
        }

        DigestAuthHelper.Challenge challenge = DigestAuthHelper.parseChallenge(challengeHeader.toString().split(":", 2)[1].trim());

        CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        String method = cseq.getMethod();
        String uri = lastSentRequest.getRequestURI().toString();

        String nc = "00000001";
        String cnonce = DigestAuthHelper.generateCNonce();
        String digestResponse = DigestAuthHelper.computeResponse(
                config.effectiveAuthUsername(), config.password(), challenge.realm(),
                challenge.nonce(), method, uri, challenge.qop(), nc, cnonce);

        // Clone the original request with new CSeq, Via branch, and auth header
        Request newRequest = (Request) lastSentRequest.clone();
        CSeqHeader newCSeq = headerFactory.createCSeqHeader(cseqCounter.getAndIncrement(), method);
        newRequest.setHeader(newCSeq);

        // Update Via branch to create a new transaction
        ViaHeader topVia = (ViaHeader) newRequest.getHeader(ViaHeader.NAME);
        topVia.setBranch("z9hG4bK-" + Long.toHexString(System.nanoTime()));

        // Build typed Authorization/Proxy-Authorization header using JAIN-SIP API
        AuthorizationHeader authHeader;
        if (statusCode == Response.UNAUTHORIZED) {
            authHeader = headerFactory.createAuthorizationHeader("Digest");
        } else {
            authHeader = headerFactory.createProxyAuthorizationHeader("Digest");
        }
        authHeader.setUsername(config.effectiveAuthUsername());
        authHeader.setRealm(challenge.realm());
        authHeader.setNonce(challenge.nonce());
        authHeader.setURI(addressFactory.createURI(uri));
        authHeader.setResponse(digestResponse);
        authHeader.setAlgorithm(challenge.algorithm());
        if (challenge.qop() != null) {
            authHeader.setQop(challenge.qop());
            authHeader.setNonceCount(Integer.parseInt(nc, 16));
            authHeader.setCNonce(cnonce);
        }
        if (challenge.opaque() != null) {
            authHeader.setOpaque(challenge.opaque());
        }

        // Remove any existing auth header and add new one
        if (statusCode == Response.UNAUTHORIZED) {
            newRequest.removeHeader(AuthorizationHeader.NAME);
        } else {
            newRequest.removeHeader(ProxyAuthorizationHeader.NAME);
        }
        newRequest.addHeader(authHeader);

        ClientTransaction ct = sipProvider.getNewClientTransaction(newRequest);
        if (Request.INVITE.equals(method)) {
            currentClientTransaction = ct;
        }
        ct.sendRequest();
        log.info("[{}] Re-sent {} with {} credentials", config.username(), method, challengeHeaderName);
    }

    private void handleOk(ResponseEvent event, String method) throws Exception {
        Response response = event.getResponse();

        if (Request.REGISTER.equals(method)) {
            if (pendingUnregister) {
                pendingUnregister = false;
                log.info("[{}] De-registration successful", config.username());
                unregisterLatch.countDown();
                listener.onUnregistered();
            } else {
                log.info("[{}] Registration successful", config.username());
                listener.onRegistered();
            }
        } else if (Request.INVITE.equals(method)) {
            // Extract SDP from 200 OK
            byte[] content = response.getRawContent();
            String sdpBody = SdpUtil.extractSdpFromContent(content);
            if (sdpBody != null) {
                remoteSdpInfo = SdpUtil.parseSdp(sdpBody);
                // Set RTP remote endpoint
                rtpSession.setRemote(
                        InetAddress.getByName(remoteSdpInfo.connectionAddress()),
                        remoteSdpInfo.mediaPort());
            }

            // Send ACK
            ClientTransaction ct = event.getClientTransaction();
            if (ct != null) {
                Dialog dialog = ct.getDialog();
                if (dialog != null) {
                    currentDialog = dialog;
                    CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
                    Request ackRequest = dialog.createAck(cseq.getSeqNumber());
                    dialog.sendAck(ackRequest);
                    log.info("[{}] ACK sent — call established", config.username());
                }
            }

            listener.onAnswered(sdpBody);
        } else if (Request.BYE.equals(method)) {
            log.info("[{}] BYE acknowledged", config.username());
            listener.onBye();
        }
    }

    private List<ViaHeader> createViaHeaders() throws ParseException, InvalidArgumentException {
        ViaHeader via = headerFactory.createViaHeader(localIp, localSipPort, "udp",
                "z9hG4bK-" + Long.toHexString(System.nanoTime()));
        via.setRPort();
        return Collections.singletonList(via);
    }

    // --- Getters ---

    public SipAccountConfig getConfig() { return config; }
    public String getLocalIp() { return localIp; }
    public int getLocalSipPort() { return localSipPort; }
    public SdpUtil.SdpInfo getRemoteSdpInfo() { return remoteSdpInfo; }
    public RtpSession getRtpSession() { return rtpSession; }
    public Dialog getCurrentDialog() { return currentDialog; }
    public SipProvider getSipProvider() { return sipProvider; }
}
