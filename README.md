# SIP Load Tester

A Java tool that simulates complete A-calls-B phone call scenarios through a real SIP PBX. Both phone endpoints (caller A and callee B) are fully simulated, including SIP signaling (JAIN-SIP) and RTP media (raw UDP with G.711 PCMU). The tool verifies that calls establish correctly and that a 1000 Hz audio tone is transmitted bidirectionally through the PBX media path.

## Requirements

- Java 21+
- Maven 3.8+
- Network access to the target SIP PBX (UDP)

## Quick Start

### Build

```bash
mvn clean package -DskipTests
```

This produces a fat JAR at `target/sip-loadtester-1.0-SNAPSHOT.jar`.

### Run a single call

```bash
java -jar target/sip-loadtester-1.0-SNAPSHOT.jar \
  --proxy-host $SIP_PROXY_HOST --proxy-port $SIP_PROXY_PORT \
  --domain $SIP_DOMAIN \
  --a-user $SIP_A_USER --a-password $SIP_A_PASSWORD --a-auth-user $SIP_A_AUTH_USER \
  --b-user $SIP_B_USER --b-password $SIP_B_PASSWORD --b-auth-user $SIP_B_AUTH_USER \
  --local-ip $LOCAL_IP \
  --media-duration 3000 --timeout 10
```

### Run 10 concurrent calls

```bash
java -jar target/sip-loadtester-1.0-SNAPSHOT.jar \
  --proxy-host $SIP_PROXY_HOST --proxy-port $SIP_PROXY_PORT \
  --domain $SIP_DOMAIN \
  --a-user $SIP_A_USER --a-password $SIP_A_PASSWORD --a-auth-user $SIP_A_AUTH_USER \
  --b-user $SIP_B_USER --b-password $SIP_B_PASSWORD --b-auth-user $SIP_B_AUTH_USER \
  --local-ip $LOCAL_IP \
  --media-duration 30000 --timeout 30 \
  --concurrent 10
```

### Run sustained load (10 concurrent calls for 5 minutes)

```bash
java -jar target/sip-loadtester-1.0-SNAPSHOT.jar \
  --proxy-host $SIP_PROXY_HOST --proxy-port $SIP_PROXY_PORT \
  --domain $SIP_DOMAIN \
  --a-user $SIP_A_USER --a-password $SIP_A_PASSWORD --a-auth-user $SIP_A_AUTH_USER \
  --b-user $SIP_B_USER --b-password $SIP_B_PASSWORD --b-auth-user $SIP_B_AUTH_USER \
  --local-ip $LOCAL_IP \
  --scenario sustained --concurrent 10 --call-duration 30000 --total-duration 300000
```

> **Note:** Set the environment variables in a `.env` file (see `.env` in the project root).
> You can source it before running: `source .env && java -jar ...`

### CLI Options

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `--proxy-host` | Yes | | PBX/proxy hostname or IP |
| `--proxy-port` | No | 5060 | PBX/proxy SIP port |
| `--domain` | Yes | | SIP domain (used in From/To URIs) |
| `--a-user` | Yes | | Phone A username (dial number) |
| `--a-password` | Yes | | Phone A password |
| `--a-auth-user` | No | same as `--a-user` | Phone A digest auth username (if different from dial number) |
| `--b-user` | Yes | | Phone B username (dial number) |
| `--b-password` | Yes | | Phone B password |
| `--b-auth-user` | No | same as `--b-user` | Phone B digest auth username |
| `--local-ip` | No | auto-detected | Local IP to bind SIP/RTP sockets |
| `--media-duration` | No | 3000 | RTP tone duration in milliseconds |
| `--timeout` | No | 10 | SIP signaling timeout in seconds |
| `--concurrent` | No | 1 | Number of concurrent calls |
| `--scenario` | No | single | Scenario type: `single`, `concurrent`, or `sustained` |
| `--call-duration` | No | 30000 | Duration of each call in sustained mode (ms) |
| `--total-duration` | No | 60000 | Total test duration in sustained mode (ms) |

The process exits with code 0 on success, 1 on test failure, 2 on fatal error.

## What It Tests

### Single Call Scenario

1. Phone B registers with the PBX (digest auth)
2. Phone A registers with the PBX (digest auth)
3. Phone A sends INVITE to B (with SDP offer)
4. Phone B answers: 100 Trying, 180 Ringing, 200 OK (with SDP answer)
5. Phone A sends ACK -- call is established
6. Both sides exchange RTP: 1000 Hz tone encoded as G.711 PCMU
7. Goertzel-based tone detection verifies the tone arrived at each end
8. Phone A sends BYE, B responds 200 OK
9. Both phones de-register (REGISTER with Expires: 0)

### Concurrent Call Scenario

- 1 shared Phone B instance handles all incoming calls (each with its own RTP session)
- N independent Phone A instances, each with its own SIP stack and port
- Calls are staggered by 500ms to allow B to answer before the next INVITE arrives
- B auto-answers each INVITE immediately in the SIP callback thread
- All N calls run RTP in parallel for the configured duration
- All calls are torn down and verified independently

### Sustained Load Scenario

Maintains a constant number of concurrent calls over a configurable time period. When a call completes, the phone is reset and immediately starts a new call.

- 1 shared Phone B auto-answers all incoming calls
- N A-phones are registered once and reused across calls via `resetCallState()`
- Each call: INVITE -> 200 OK -> RTP for `--call-duration` ms -> BYE -> reset -> next call
- ~10% of calls are sampled for tone detection (Goertzel algorithm)
- Live stats printed every 5 seconds during the run
- After `--total-duration` expires, in-flight calls finish and final summary is printed
- Success criteria: >= 95% call pass rate

#### Live Stats

```
[00:15] Active: 10 | Completed: 28 | Failed: 0 | Rate: 1.9 calls/sec | Avg setup: 245ms | Tone: 3/3 passed
```

#### Final Report

```
====================================
 SCENARIO: Sustained Load (10 concurrent, 60s)
====================================
  Total calls attempted:  120
  Total calls completed:  118
  Total calls failed:       2
  Calls/sec (avg):        1.97
  Setup latency (avg):    248ms
  Setup latency (p95):    412ms
  Setup latency (p99):    523ms
  Tone tests passed:      12/12
  Duration:               60023ms
  Result: SUCCESS (98.3% pass rate)
====================================
```

### Report Output

```
====================================
 SCENARIO: Concurrent 10 calls
====================================

  [PASS] Phone B Registration (131ms)
  [PASS] A-phones Registration (10) (117ms)
  [PASS] Call Setup (10 calls) (4898ms)
  [PASS] RTP A->B (10/10)
  [PASS] RTP B->A (10/10)
  [PASS] Tone A->B (10/10)
  [PASS] Tone B->A (10/10)
  [PASS] BYE/200 OK (10) (75ms)

------------------------------------
  Total: 8 checks, 8 passed, 0 failed
  Duration: 49573ms
  Result: SUCCESS
====================================
```

## Project Structure

```
src/main/java/com/loadtester/
├── Main.java                          CLI entry point, arg parsing
├── config/
│   └── SipAccountConfig.java          Immutable record: credentials + connection details
├── sip/
│   ├── SipPhone.java                  Simulated SIP phone (SipListener impl)
│   ├── SipPhoneListener.java          Callback interface for SIP events
│   ├── CallLeg.java                   Per-call state for multi-call B-side
│   ├── SdpUtil.java                   SDP build/parse (G.711 PCMU)
│   ├── DigestAuthHelper.java          RFC 2617 digest auth computation
│   ├── SipStackFactory.java           Factory interface (for DI/testing)
│   └── DefaultSipStackFactory.java    Production JAIN-SIP factory
├── media/
│   ├── RtpPacket.java                 RTP packet encode/decode (RFC 3550)
│   ├── RtpSession.java                RTP session interface
│   ├── UdpRtpSession.java             UDP DatagramSocket implementation
│   ├── ToneGenerator.java             Sine wave -> G.711 PCMU encoder
│   └── ToneDetector.java              Goertzel algorithm tone detector
├── scenario/
│   ├── CallScenario.java              Single A-calls-B orchestrator
│   ├── ConcurrentCallScenario.java    N concurrent calls orchestrator
│   └── SustainedLoadScenario.java     Sustained load (N calls for T duration)
└── report/
    └── TestReport.java                Pass/fail check collection + summary

src/test/java/com/loadtester/
├── config/SipAccountConfigTest.java          14 tests
├── sip/
│   ├── SipPhoneTest.java                     17 tests (mocked JAIN-SIP stack)
│   ├── CallLegTest.java                       4 tests
│   ├── SdpUtilTest.java                      17 tests
│   └── DigestAuthHelperTest.java             16 tests
├── media/
│   ├── RtpPacketTest.java                    17 tests
│   ├── UdpRtpSessionTest.java               16 tests (loopback UDP)
│   ├── ToneGeneratorTest.java                12 tests
│   ├── ToneDetectorTest.java                 19 tests (incl. Goertzel)
│   └── ToneRoundTripTest.java                 3 tests (end-to-end media)
└── scenario/
    ├── TestReportTest.java                    9 tests
    ├── SustainedLoadScenarioTest.java         8 tests
    ├── CallScenarioIntegrationTest.java       @Disabled (real PBX)
    └── ConcurrentCallScenarioIntegrationTest.java  @Disabled (real PBX)
```

**154 unit tests + 2 integration tests (disabled by default)**

## Running Tests

### Unit tests only (no PBX needed)

```bash
mvn test
```

### Integration test against real PBX

```bash
# Single call
mvn test -Dtest="CallScenarioIntegrationTest#fullCallScenarioAgainstRealPbx" \
  -Dsurefire.failIfNoSpecifiedTests=false

# 10 concurrent calls
mvn test -Dtest="ConcurrentCallScenarioIntegrationTest#tenConcurrentCallsAgainstRealPbx" \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Integration tests read credentials from environment variables. Set them in your `.env` file before running.

## Architecture Notes

### SIP Signaling

- **JAIN-SIP** RI version `1.3.0-91` with API `1.2.1`
- UDP transport only
- Each `SipPhone` gets its own `SipStack` with a unique name (JAIN-SIP requires one `SipListener` per stack)
- All requests are routed through the PBX via `javax.sip.OUTBOUND_PROXY`
- `rport` is set on all Via headers for NAT traversal (Kamailio handles `received`/`rport`)
- Digest auth handles both 401 (WWW-Authenticate) and 407 (Proxy-Authenticate)
- Auth retry clones the original request with a new Via branch to avoid "Transaction already exists"
- From/To/Contact headers use `effectiveAuthUsername()` (the `u`-prefixed form, e.g. `u<number>`)
- Registration expiry is 60 seconds; shutdown sends REGISTER Expires:0 to de-register

### RTP Media

- Raw UDP via `DatagramSocket` (no external RTP library)
- G.711 PCMU (mu-law) codec, 8000 Hz sample rate, 20ms packets (160 samples each)
- 1000 Hz sine wave tone at 0.8 amplitude
- Tone detection uses the **Goertzel algorithm** -- more reliable than zero-crossing through a PBX media path
- Each call leg gets its own `UdpRtpSession` bound to an ephemeral port

### Concurrent Calls Design

The concurrent scenario uses a shared B-phone that must handle multiple simultaneous calls:

```
Phone A[0]  ──INVITE──>  PBX  ──INVITE──>  Phone B (1 instance)
Phone A[1]  ──INVITE──>  PBX  ──INVITE──>     │  manages N CallLegs
   ...                                         │  each with own RTP
Phone A[9]  ──INVITE──>  PBX  ──INVITE──>     │
```

Key design decisions:
- **Single B registration** -- the PBX limits registrations per credential
- **Auto-answer in SIP callback** -- B answers each INVITE immediately on the JAIN-SIP EventScanner thread to prevent the PBX from returning 486 Busy Here for the next call
- **500ms stagger** between INVITEs to give B time to answer before the next arrives
- **`ConcurrentHashMap<String, CallLeg>`** on `SipPhone` maps Call-ID to per-call state
- **`Supplier<RtpSession>`** factory injected into B's `SipPhone` creates a fresh `UdpRtpSession` per incoming call

### Testability

- `SipStackFactory` interface wraps JAIN-SIP creation -- tests inject mocks
- `RtpSession` interface allows mock media in SIP tests
- `SipPhoneListener` decouples scenario logic from the SIP state machine
- `DigestAuthHelper`, `SdpUtil`, `ToneGenerator`, `ToneDetector` are pure functions with no I/O
- `UdpRtpSessionTest` and `ToneRoundTripTest` use loopback UDP
- Mockito with `Strictness.LENIENT` for SipPhone tests (JAIN-SIP stubbing complexity)

## Known Gotchas

| Issue | Details |
|-------|---------|
| JAIN-SIP RI version | Only `1.3.0-91` works. Version `1.2.348` doesn't exist in Maven Central. |
| JAIN-SIP port 0 | `createListeningPoint` rejects port 0. Use `new DatagramSocket(0)` to find a free port first. |
| Auth header creation | `HeaderFactory.createHeader("Authorization", ...)` fails. Must use `createAuthorizationHeader("Digest")` / `createProxyAuthorizationHeader("Digest")` and set fields individually. |
| One listener per stack | JAIN-SIP allows only one `SipListener` per `SipStack`. Each phone needs a unique stack name. |
| PBX 486 Busy Here | Some PBX subscriber accounts reject concurrent calls. The B-side account must have call-waiting / multi-call enabled. |
| PBX 413 on INVITE | Some SIP domains return 413 for INVITE. Use the outbound proxy hostname directly instead. |
| Mu-law bias constant | The correct bias for G.711 mu-law is `0x84` (132), not 33. |
| Mu-law silence | Silence is `0xFF` in mu-law encoding, not `0x00`. |
| SDP rtpmap parsing | Earlier versions had a bug where parsing rtpmap overwrote the codec name. Fixed in `SdpUtil`. |
