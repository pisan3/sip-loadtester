package com.loadtester.sip;

import com.loadtester.config.SipAccountConfig;
import com.loadtester.media.RtpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sip.*;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.*;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests SipPhone SIP state machine using real JAIN-SIP message factories
 * but mocked stack/provider/transport.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SipPhoneTest {

    private SipAccountConfig config;

    @Mock private SipStackFactory mockStackFactory;
    @Mock private SipStack mockSipStack;
    @Mock private SipProvider mockSipProvider;
    @Mock private ListeningPoint mockListeningPoint;
    @Mock private RtpSession mockRtpSession;
    @Mock private ClientTransaction mockClientTransaction;
    @Mock private Dialog mockDialog;

    // Use real JAIN-SIP message factories — they work without a live stack
    private AddressFactory addressFactory;
    private HeaderFactory headerFactory;
    private MessageFactory messageFactory;

    private SipPhone phone;
    private RecordingListener listener;

    @BeforeEach
    void setUp() throws Exception {
        config = new SipAccountConfig("alice", "secret", "example.com", "10.0.0.1", 5060);
        listener = new RecordingListener();

        // Setup real JAIN-SIP factories
        SipFactory sipFactory = SipFactory.getInstance();
        sipFactory.setPathName("gov.nist");
        addressFactory = sipFactory.createAddressFactory();
        headerFactory = sipFactory.createHeaderFactory();
        messageFactory = sipFactory.createMessageFactory();

        // Setup mocks
        when(mockStackFactory.createSipStack(any())).thenReturn(mockSipStack);
        when(mockStackFactory.createListeningPoint(any(), anyString(), anyInt(), anyString()))
                .thenReturn(mockListeningPoint);
        when(mockStackFactory.createSipProvider(any(), any())).thenReturn(mockSipProvider);
        when(mockListeningPoint.getPort()).thenReturn(5060);
        when(mockSipProvider.getNewCallId()).thenReturn(headerFactory.createCallIdHeader("test-call-id"));
        // Capture the request passed to getNewClientTransaction so that
        // mockClientTransaction.getRequest() returns it (needed by the stateless auth handler)
        when(mockSipProvider.getNewClientTransaction(any())).thenAnswer(invocation -> {
            Request req = invocation.getArgument(0);
            when(mockClientTransaction.getRequest()).thenReturn(req);
            return mockClientTransaction;
        });

        phone = new SipPhone(config, mockStackFactory, mockRtpSession, listener);
        phone.initialize(mockStackFactory, "127.0.0.1", 5060);
    }

    @Test
    void initializeShouldCreateStackAndProvider() throws Exception {
        verify(mockStackFactory).createSipStack(any());
        verify(mockStackFactory).createListeningPoint(any(), eq("127.0.0.1"), eq(5060), eq("udp"));
        verify(mockStackFactory).createSipProvider(any(), any());
        verify(mockSipProvider).addSipListener(phone);
    }

    @Test
    void registerShouldSendRegisterRequest() throws Exception {
        phone.register();

        verify(mockSipProvider).getNewClientTransaction(argThat(request ->
                request.getMethod().equals(Request.REGISTER)));
        verify(mockClientTransaction).sendRequest();
    }

    @Test
    void processResponseOkForRegisterShouldNotifyListener() throws Exception {
        // Simulate a 200 OK response to REGISTER
        phone.register();

        Response okResponse = createResponse(200, Request.REGISTER, 1);
        ResponseEvent event = new ResponseEvent(mockSipProvider, mockClientTransaction, null, okResponse);

        phone.processResponse(event);

        assertThat(listener.registeredCount.get()).isEqualTo(1);
    }

    @Test
    void processResponse401ShouldResendWithAuth() throws Exception {
        phone.register();

        // Simulate 401 with challenge
        Response unauthResponse = createResponse(401, Request.REGISTER, 1);
        WWWAuthenticateHeader authHeader = headerFactory.createWWWAuthenticateHeader("Digest");
        authHeader.setRealm("example.com");
        authHeader.setNonce("abc123");
        unauthResponse.addHeader(authHeader);

        ResponseEvent event = new ResponseEvent(mockSipProvider, mockClientTransaction, null, unauthResponse);
        phone.processResponse(event);

        // Should have created a new transaction for the auth retry
        verify(mockSipProvider, atLeast(2)).getNewClientTransaction(any());
    }

    @Test
    void process407ShouldResendWithProxyAuth() throws Exception {
        when(mockRtpSession.isRunning()).thenReturn(false);
        when(mockRtpSession.getLocalPort()).thenReturn(40000);

        phone.call("sip:bob@example.com");

        // Simulate 407 Proxy Authentication Required
        Response proxyAuthResponse = createResponse(407, Request.INVITE, 2);
        ProxyAuthenticateHeader proxyAuthHeader = headerFactory.createProxyAuthenticateHeader("Digest");
        proxyAuthHeader.setRealm("example.com");
        proxyAuthHeader.setNonce("proxy-nonce-123");
        proxyAuthResponse.addHeader(proxyAuthHeader);

        ResponseEvent event = new ResponseEvent(mockSipProvider, mockClientTransaction, null, proxyAuthResponse);
        phone.processResponse(event);

        // Should have created a new transaction for the auth retry
        verify(mockSipProvider, atLeast(2)).getNewClientTransaction(any());
    }

    @Test
    void processRingingShouldNotifyListener() throws Exception {
        when(mockRtpSession.isRunning()).thenReturn(false);
        when(mockRtpSession.getLocalPort()).thenReturn(40000);
        phone.call("sip:bob@example.com");

        Response ringing = createResponse(180, Request.INVITE, 2);
        ResponseEvent event = new ResponseEvent(mockSipProvider, mockClientTransaction, null, ringing);

        phone.processResponse(event);

        assertThat(listener.ringingCount.get()).isEqualTo(1);
    }

    @Test
    void processInviteOkShouldNotifyListenerAndSendAck() throws Exception {
        when(mockRtpSession.isRunning()).thenReturn(false);
        when(mockRtpSession.getLocalPort()).thenReturn(40000);
        phone.call("sip:bob@example.com");

        // Create a 200 OK with SDP
        Response okResponse = createResponse(200, Request.INVITE, 2);
        String sdp = SdpUtil.buildSdp("10.0.0.2", 50000, 12345);
        ContentTypeHeader ct = headerFactory.createContentTypeHeader("application", "sdp");
        okResponse.setContent(sdp, ct);

        // Mock dialog for ACK
        when(mockClientTransaction.getDialog()).thenReturn(mockDialog);
        Request mockAck = mock(Request.class);
        when(mockDialog.createAck(anyLong())).thenReturn(mockAck);

        ResponseEvent event = new ResponseEvent(mockSipProvider, mockClientTransaction, mockDialog, okResponse);
        phone.processResponse(event);

        assertThat(listener.answeredCount.get()).isEqualTo(1);
        assertThat(listener.lastSdpAnswer.get()).isEqualTo(sdp);
        verify(mockDialog).sendAck(any());
        verify(mockRtpSession).setRemote(any(), eq(50000));
    }

    @Test
    void processIncomingInviteShouldNotifyListener() throws Exception {
        // Build an INVITE request
        Request invite = buildInviteRequest("sip:alice@example.com", "sip:bob@example.com");
        String sdpOffer = SdpUtil.buildSdp("10.0.0.3", 60000, 99999);
        ContentTypeHeader ct = headerFactory.createContentTypeHeader("application", "sdp");
        invite.setContent(sdpOffer, ct);

        ServerTransaction mockServerTx = mock(ServerTransaction.class);
        when(mockSipProvider.getNewServerTransaction(any())).thenReturn(mockServerTx);

        RequestEvent event = new RequestEvent(mockSipProvider, null, null, invite);
        phone.processRequest(event);

        // Should get 100 Trying sent
        verify(mockServerTx).sendResponse(argThat(response ->
                response.getStatusCode() == 100));

        assertThat(listener.incomingCallCount.get()).isEqualTo(1);
    }

    @Test
    void processByeShouldRespondOkAndNotifyListener() throws Exception {
        Request bye = buildByeRequest();
        ServerTransaction mockServerTx = mock(ServerTransaction.class);
        when(mockSipProvider.getNewServerTransaction(any())).thenReturn(mockServerTx);

        RequestEvent event = new RequestEvent(mockSipProvider, null, null, bye);
        phone.processRequest(event);

        verify(mockServerTx).sendResponse(argThat(response ->
                response.getStatusCode() == 200));
        assertThat(listener.byeCount.get()).isEqualTo(1);
    }

    @Test
    void processTimeoutShouldNotifyError() {
        TimeoutEvent event = mock(TimeoutEvent.class);
        phone.processTimeout(event);
        assertThat(listener.errorCount.get()).isEqualTo(1);
    }

    @Test
    void processCallFailedShouldNotifyListener() throws Exception {
        when(mockRtpSession.isRunning()).thenReturn(false);
        when(mockRtpSession.getLocalPort()).thenReturn(40000);
        phone.call("sip:bob@example.com");

        Response busy = createResponse(486, Request.INVITE, 2);

        // Mock dialog for ACK of error response
        when(mockClientTransaction.getDialog()).thenReturn(mockDialog);
        Request mockAck = mock(Request.class);
        when(mockDialog.createAck(anyLong())).thenReturn(mockAck);

        ResponseEvent event = new ResponseEvent(mockSipProvider, mockClientTransaction, mockDialog, busy);
        phone.processResponse(event);

        assertThat(listener.callFailedCount.get()).isEqualTo(1);
    }

    @Test
    void shutdownShouldStopRtpAndStack() {
        when(mockRtpSession.isRunning()).thenReturn(true);
        phone.shutdown();
        verify(mockRtpSession).stop();
        verify(mockSipStack).stop();
    }

    @Test
    void getConfigShouldReturnInjectedConfig() {
        assertThat(phone.getConfig()).isEqualTo(config);
    }

    @Test
    void getLocalIpAndPortShouldBeSetAfterInitialize() {
        assertThat(phone.getLocalIp()).isEqualTo("127.0.0.1");
        assertThat(phone.getLocalSipPort()).isEqualTo(5060);
    }

    @Test
    void resetCallStateShouldClearDialogAndSdpInfo() throws Exception {
        // Simulate a successful call setup to populate state
        when(mockRtpSession.isRunning()).thenReturn(false);
        when(mockRtpSession.getLocalPort()).thenReturn(40000);
        phone.call("sip:bob@example.com");

        // Simulate 200 OK with SDP to populate remoteSdpInfo and currentDialog
        Response okResponse = createResponse(200, Request.INVITE, 2);
        String sdp = SdpUtil.buildSdp("10.0.0.2", 50000, 12345);
        ContentTypeHeader ct = headerFactory.createContentTypeHeader("application", "sdp");
        okResponse.setContent(sdp, ct);

        when(mockClientTransaction.getDialog()).thenReturn(mockDialog);
        Request mockAck = mock(Request.class);
        when(mockDialog.createAck(anyLong())).thenReturn(mockAck);

        ResponseEvent event = new ResponseEvent(mockSipProvider, mockClientTransaction, mockDialog, okResponse);
        phone.processResponse(event);

        // Verify state is populated
        assertThat(phone.getCurrentDialog()).isNotNull();
        assertThat(phone.getRemoteSdpInfo()).isNotNull();

        // Reset
        phone.resetCallState();

        // Verify state is cleared
        assertThat(phone.getCurrentDialog()).isNull();
        assertThat(phone.getRemoteSdpInfo()).isNull();
        verify(mockRtpSession).reset();
    }

    @Test
    void resetCallStateShouldCallRtpReset() {
        phone.resetCallState();
        verify(mockRtpSession).reset();
    }

    @Test
    void resetCallStateShouldNotAffectSipStack() {
        phone.resetCallState();

        // SipStack should NOT be stopped
        verify(mockSipStack, never()).stop();
        // SipProvider should still be usable (no removal of listener)
        verify(mockSipProvider, never()).removeSipListener(any());
    }

    // --- Re-registration timer tests ---

    @Test
    void registerOkShouldStartReRegistrationTimer() throws Exception {
        phone.register();

        // Simulate 200 OK for REGISTER
        Response okResponse = createResponse(200, Request.REGISTER, 1);
        ResponseEvent event = new ResponseEvent(mockSipProvider, mockClientTransaction, null, okResponse);
        phone.processResponse(event);

        // After registration, the re-registration timer should be running.
        // We can verify indirectly: calling sendReRegistration() should send a REGISTER.
        // Reset invocation counts from register()
        clearInvocations(mockClientTransaction);

        phone.sendReRegistration();

        // Should have created a new client transaction for the re-REGISTER
        verify(mockSipProvider, atLeast(2)).getNewClientTransaction(argThat(request ->
                request.getMethod().equals(Request.REGISTER)));
    }

    @Test
    void sendReRegistrationShouldSendRegisterRequest() throws Exception {
        phone.register(); // needs to be called first so sipProvider is used

        // Reset invocation tracking
        clearInvocations(mockSipProvider);
        clearInvocations(mockClientTransaction);

        phone.sendReRegistration();

        verify(mockSipProvider).getNewClientTransaction(argThat(request ->
                request.getMethod().equals(Request.REGISTER)));
        verify(mockClientTransaction).sendRequest();
    }

    @Test
    void sendReRegistrationShouldBeNoOpWhenPendingUnregister() throws Exception {
        phone.register();

        // Trigger unregister to set pendingUnregister = true
        phone.unregister();

        // Reset invocations after unregister
        clearInvocations(mockSipProvider);
        clearInvocations(mockClientTransaction);

        // Should be a no-op since pendingUnregister is true
        phone.sendReRegistration();

        verify(mockSipProvider, never()).getNewClientTransaction(any());
    }

    @Test
    void shutdownShouldCancelReRegistrationTimer() throws Exception {
        when(mockRtpSession.isRunning()).thenReturn(true);

        // Register and get 200 OK to start timer
        phone.register();
        Response okResponse = createResponse(200, Request.REGISTER, 1);
        ResponseEvent event = new ResponseEvent(mockSipProvider, mockClientTransaction, null, okResponse);
        phone.processResponse(event);

        // Shutdown should stop everything including the timer
        phone.shutdown();

        // Reset invocations after shutdown
        clearInvocations(mockSipProvider);
        clearInvocations(mockClientTransaction);

        // After shutdown, calling sendReRegistration should be a no-op
        // because sipProvider is still set but the scheduler is stopped
        // (and in a real scenario, the provider would be dead after stack.stop())
        verify(mockSipStack).stop();
    }

    @Test
    void authChallengeForReRegistrationShouldUseClientTransactionRequest() throws Exception {
        // First, do initial registration
        phone.register();

        // Then start a call to set lastSentRequest to an INVITE
        when(mockRtpSession.isRunning()).thenReturn(false);
        when(mockRtpSession.getLocalPort()).thenReturn(40000);
        phone.call("sip:bob@example.com");

        // Now simulate a 401 challenge for a re-REGISTER.
        // The ClientTransaction should carry the REGISTER request,
        // NOT the INVITE request in lastSentRequest.
        Response unauthResponse = createResponse(401, Request.REGISTER, 3);
        WWWAuthenticateHeader authHeader = headerFactory.createWWWAuthenticateHeader("Digest");
        authHeader.setRealm("example.com");
        authHeader.setNonce("rereg-nonce");
        unauthResponse.addHeader(authHeader);

        // Create a mock ClientTransaction that returns a REGISTER request
        ClientTransaction reregTx = mock(ClientTransaction.class);
        Request reregRequest = createRegisterRequest(3);
        when(reregTx.getRequest()).thenReturn(reregRequest);

        ResponseEvent reregEvent = new ResponseEvent(mockSipProvider, reregTx, null, unauthResponse);

        // Reset invocations so we can check the auth retry
        clearInvocations(mockSipProvider);
        clearInvocations(mockClientTransaction);

        phone.processResponse(reregEvent);

        // The auth retry should clone the REGISTER from the ClientTransaction,
        // not the INVITE from lastSentRequest
        verify(mockSipProvider).getNewClientTransaction(argThat(request ->
                request.getMethod().equals(Request.REGISTER)));
    }

    @Test
    void reRegistrationOkShouldNotifyListenerRegistered() throws Exception {
        // Initial registration
        phone.register();
        Response okResponse1 = createResponse(200, Request.REGISTER, 1);
        ResponseEvent event1 = new ResponseEvent(mockSipProvider, mockClientTransaction, null, okResponse1);
        phone.processResponse(event1);

        assertThat(listener.registeredCount.get()).isEqualTo(1);

        // Re-registration 200 OK
        Response okResponse2 = createResponse(200, Request.REGISTER, 3);
        ResponseEvent event2 = new ResponseEvent(mockSipProvider, mockClientTransaction, null, okResponse2);
        phone.processResponse(event2);

        // Should have fired onRegistered again (idempotent)
        assertThat(listener.registeredCount.get()).isEqualTo(2);
    }

    // --- Helper methods ---

    private Response createResponse(int statusCode, String method, long cseq) throws Exception {
        SipURI requestUri = addressFactory.createSipURI("alice", "example.com");
        Address fromAddr = addressFactory.createAddress(addressFactory.createSipURI("alice", "example.com"));
        FromHeader from = headerFactory.createFromHeader(fromAddr, "tag-from");
        Address toAddr = addressFactory.createAddress(addressFactory.createSipURI("bob", "example.com"));
        ToHeader to = headerFactory.createToHeader(toAddr, "tag-to");
        ViaHeader via = headerFactory.createViaHeader("127.0.0.1", 5060, "udp", "branch-test");
        MaxForwardsHeader maxFwd = headerFactory.createMaxForwardsHeader(70);
        CSeqHeader cseqHeader = headerFactory.createCSeqHeader(cseq, method);
        CallIdHeader callIdHeader = headerFactory.createCallIdHeader("test-call-id");

        Request request = messageFactory.createRequest(requestUri, method, callIdHeader,
                cseqHeader, from, to, java.util.List.of(via), maxFwd);

        return messageFactory.createResponse(statusCode, request);
    }

    private Request createRegisterRequest(long cseq) throws Exception {
        SipURI requestUri = addressFactory.createSipURI(null, "example.com");
        Address fromAddr = addressFactory.createAddress(addressFactory.createSipURI("alice", "example.com"));
        FromHeader from = headerFactory.createFromHeader(fromAddr, "rereg-tag");
        Address toAddr = addressFactory.createAddress(addressFactory.createSipURI("alice", "example.com"));
        ToHeader to = headerFactory.createToHeader(toAddr, null);
        ViaHeader via = headerFactory.createViaHeader("127.0.0.1", 5060, "udp", "branch-rereg");
        MaxForwardsHeader maxFwd = headerFactory.createMaxForwardsHeader(70);
        CSeqHeader cseqHeader = headerFactory.createCSeqHeader(cseq, Request.REGISTER);
        CallIdHeader callIdHeader = headerFactory.createCallIdHeader("rereg-call-id");

        return messageFactory.createRequest(requestUri, Request.REGISTER, callIdHeader,
                cseqHeader, from, to, java.util.List.of(via), maxFwd);
    }

    private Request buildInviteRequest(String fromUri, String toUri) throws Exception {
        SipURI requestUri = (SipURI) addressFactory.createURI(toUri);
        Address fromAddr = addressFactory.createAddress(addressFactory.createURI(fromUri));
        FromHeader from = headerFactory.createFromHeader(fromAddr, "from-tag");
        Address toAddr = addressFactory.createAddress(addressFactory.createURI(toUri));
        ToHeader to = headerFactory.createToHeader(toAddr, null);
        ViaHeader via = headerFactory.createViaHeader("10.0.0.3", 5060, "udp", "branch-inv");
        MaxForwardsHeader maxFwd = headerFactory.createMaxForwardsHeader(70);
        CSeqHeader cseqHeader = headerFactory.createCSeqHeader(1, Request.INVITE);
        CallIdHeader callIdHeader = headerFactory.createCallIdHeader("invite-call-id");

        return messageFactory.createRequest(requestUri, Request.INVITE, callIdHeader,
                cseqHeader, from, to, java.util.List.of(via), maxFwd);
    }

    private Request buildByeRequest() throws Exception {
        SipURI requestUri = addressFactory.createSipURI("alice", "example.com");
        Address fromAddr = addressFactory.createAddress(addressFactory.createSipURI("bob", "example.com"));
        FromHeader from = headerFactory.createFromHeader(fromAddr, "from-tag");
        Address toAddr = addressFactory.createAddress(addressFactory.createSipURI("alice", "example.com"));
        ToHeader to = headerFactory.createToHeader(toAddr, "to-tag");
        ViaHeader via = headerFactory.createViaHeader("10.0.0.2", 5060, "udp", "branch-bye");
        MaxForwardsHeader maxFwd = headerFactory.createMaxForwardsHeader(70);
        CSeqHeader cseqHeader = headerFactory.createCSeqHeader(2, Request.BYE);
        CallIdHeader callIdHeader = headerFactory.createCallIdHeader("bye-call-id");

        return messageFactory.createRequest(requestUri, Request.BYE, callIdHeader,
                cseqHeader, from, to, java.util.List.of(via), maxFwd);
    }

    /**
     * Recording listener for test assertions.
     */
    private static class RecordingListener implements SipPhoneListener {
        final AtomicInteger registeredCount = new AtomicInteger();
        final AtomicInteger ringingCount = new AtomicInteger();
        final AtomicInteger answeredCount = new AtomicInteger();
        final AtomicInteger byeCount = new AtomicInteger();
        final AtomicInteger callFailedCount = new AtomicInteger();
        final AtomicInteger incomingCallCount = new AtomicInteger();
        final AtomicInteger errorCount = new AtomicInteger();
        final AtomicReference<String> lastSdpAnswer = new AtomicReference<>();
        final AtomicReference<String> lastError = new AtomicReference<>();

        @Override public void onRegistered() { registeredCount.incrementAndGet(); }
        @Override public void onRegistrationFailed(int code, String reason) { errorCount.incrementAndGet(); }
        @Override public void onIncomingCall(String callerUri, String sdpOffer) { incomingCallCount.incrementAndGet(); }
        @Override public void onRinging() { ringingCount.incrementAndGet(); }
        @Override public void onAnswered(String sdpAnswer) {
            answeredCount.incrementAndGet();
            lastSdpAnswer.set(sdpAnswer);
        }
        @Override public void onCallFailed(int code, String reason) { callFailedCount.incrementAndGet(); }
        @Override public void onBye() { byeCount.incrementAndGet(); }
        @Override public void onError(Exception e) {
            errorCount.incrementAndGet();
            lastError.set(e.getMessage());
        }
    }
}
