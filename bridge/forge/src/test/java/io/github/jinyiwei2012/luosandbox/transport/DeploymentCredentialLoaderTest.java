package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class DeploymentCredentialLoaderTest {
  static byte[] digest(byte[] value) {
    try { return MessageDigest.getInstance("SHA-256").digest(value); }
    catch (Exception error) { throw new AssertionError(error); }
  }
  static byte[] manifest() {
    String hash = HexFormat.of().formatHex(digest(new byte[] {1}));
    return ("{\"schema\":\"luosandbox.minecraft-control-deployment.v2\",\"security\":\"luosandbox.control-auth.v1\","
        + "\"peerRole\":\"SERVER_AUTHORITY\",\"peerInstallId\":\"peer\",\"hubInstallId\":\"hub\","
        + "\"protocol\":\"LUO_CONTROL_POSTAUTH_V2\",\"postAuthProfile\":\"LUO_CONTROL_POSTAUTH_V2\","
        + "\"sessionKdfDomain\":\"LUO_CONTROL_SESSION_KEY_V2\",\"frameMacDomain\":\"LUO_CONTROL_FRAME_V2\","
        + "\"frameMaxUtf8Bytes\":4096,\"payloadMaxRawBytes\":2048,\"asciiIdMaxBytes\":128,\"outboundQueueFrames\":8,"
        + "\"openTimeoutMs\":5000,\"heartbeatIntervalMs\":500,\"heartbeatTimeoutMs\":2000,\"routeCapacity\":2,"
        + "\"routeTtlMs\":5000,\"bindingPendingCapacity\":8,\"storeOutstandingCapacity\":2,"
        + "\"modManifestSha256\":\"" + hash + "\",\"policyConfigSha256\":\"" + hash
        + "\",\"bindingManifestSha256\":\"" + hash + "\"}\n").getBytes(StandardCharsets.US_ASCII);
  }
  static DeploymentCredentialLoader.TrustedDeploymentPolicy policy() {
    byte[] sid = {1, 1, 0, 0, 0, 0, 0, 5, 18, 0, 0, 0};
    byte[][] set = {sid};
    return new DeploymentCredentialLoader.TrustedDeploymentPolicy("C:\\root",
        new String[] {"C:\\root\\manifest", "C:\\root\\mod", "C:\\root\\policy", "C:\\root\\binding", "C:\\root\\key"},
        ControlAuth.Role.SERVER_AUTHORITY, "peer", "hub", "key", digest(manifest()), 1, 2,
        sid, new byte[][] {sid, sid, sid, sid, sid}, set, set, set, set);
  }
  static final class Port implements DeploymentCredentialLoader.NativePort, DeploymentCredentialLoader.Custody {
    int calls, closes, checks;
    int credentialLength = 32;
    boolean unknown;
    boolean noProgress;
    byte[] ownedReadBuffer;
    public DeploymentCredentialLoader.Custody acquire(DeploymentCredentialLoader.TrustedDeploymentPolicy policy,
        DeploymentCredentialLoader.CustodyAttemptReceipt receipt) {
      calls++;
      var api = new WindowsDeploymentCustodyTest.Api() {
        public void release(DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind kind, Object value) {
          closes++; if (unknown) throw new AssertionError("close");
        }
      };
      Object token = receipt.beginOperation();
      var record = receipt.reserve(token, DeploymentCredentialLoader.CustodyAttemptReceipt.NativeRecord.Kind.HANDLE, api);
      receipt.bindReturned(token, record, new Object()); receipt.endOperation(token); return this;
    }
    public void validate() { checks++; }
    public DeploymentCredentialLoader.HeldFile file(DeploymentCredentialLoader.Purpose purpose) {
      byte[] value = purpose == DeploymentCredentialLoader.Purpose.MANIFEST ? manifest()
          : purpose == DeploymentCredentialLoader.Purpose.CREDENTIAL ? new byte[credentialLength] : new byte[] {1};
      return new DeploymentCredentialLoader.HeldFile() {
        int offset;
        public int read(byte[] buffer, int start, int length) {
          if (purpose == DeploymentCredentialLoader.Purpose.CREDENTIAL) ownedReadBuffer = buffer;
          if (noProgress) return 0;
          if (offset == value.length) return -1;
          int count = Math.min(3, Math.min(length, value.length - offset));
          System.arraycopy(value, offset, buffer, start, count); offset += count; return count;
        }
      };
    }
  }
  @Test void actualManifestAndShortReadsPublishOnlyAfterCleanup() {
    Port port = new Port(); var loader = new DeploymentCredentialLoader(port);
    assertEquals(0, port.calls);
    var receipt = new DeploymentCredentialLoader.CustodyAttemptReceipt();
    var slot = new DeploymentCredentialLoader.HandoffSlot(); loader.load(policy(), receipt, slot);
    assertEquals("RELEASED", receipt.snapshot().state()); assertEquals(1, port.closes);
    var provisioning = slot.claim(); assertEquals(32, provisioning.credential().copy().length);
    assertArrayEquals(new byte[33], port.ownedReadBuffer);
    slot.acknowledgeConstruction(); slot.close(); assertEquals(32, provisioning.credential().copy().length);
    provisioning.credential().close();
  }
  @Test void failedConstructionCloseAndClosedOwnerPreventAcknowledgement() {
    for (boolean ownerFirst : new boolean[] {false, true}) {
      var slot = new DeploymentCredentialLoader.HandoffSlot();
      new DeploymentCredentialLoader(new Port()).load(policy(), new DeploymentCredentialLoader.CustodyAttemptReceipt(), slot);
      var provisioning = slot.claim();
      if (ownerFirst) provisioning.credential().close(); else slot.close();
      assertThrows(RuntimeException.class, slot::acknowledgeConstruction);
      assertThrows(RuntimeException.class, () -> provisioning.credential().copy()); slot.close();
    }
  }
  @Test void secretLengthsAndNoProgressRejectWithoutPublication() {
    for (int length : new int[] {31, 33, 0}) {
      Port port = new Port(); port.credentialLength = length; port.noProgress = length == 0;
      var slot = new DeploymentCredentialLoader.HandoffSlot();
      assertThrows(RuntimeException.class, () -> new DeploymentCredentialLoader(port).load(policy(),
          new DeploymentCredentialLoader.CustodyAttemptReceipt(), slot));
      assertEquals("EMPTY", slot.state()); assertEquals(1, port.closes);
    }
  }
  @Test void cleanupFatalRetainsReceiptAndWipesBufferNoPublication() {
    Port port = new Port(); port.unknown = true;
    var receipt = new DeploymentCredentialLoader.CustodyAttemptReceipt(); var slot = new DeploymentCredentialLoader.HandoffSlot();
    assertThrows(AssertionError.class, () -> new DeploymentCredentialLoader(port).load(policy(), receipt, slot));
    assertTrue(receipt.snapshot().unknown()); assertEquals(1, receipt.snapshot().retained());
    assertEquals("EMPTY", slot.state()); assertTrue(Arrays.equals(new byte[33], port.ownedReadBuffer));
  }
  @Test void rawAliasesRejectBeforeAnyPort() {
    for (String value : new String[] {"C:root", "C:\\root\\..\\x", "C:\\root\\key:stream", "\\\\host\\root", "C:\\NUL"}) {
      assertThrows(RuntimeException.class, () -> DeploymentCredentialLoader.TrustedDeploymentPolicy.lexical(value));
    }
    assertThrows(RuntimeException.class, () -> DeploymentCredentialLoader.TrustedDeploymentPolicy.lexical("C:\\root\\\uD800"));
  }
  @Test void publicationToClosedSlotWipesOwnerAndPolicyCopiesAreDefensive() {
    var policy = policy(); byte[] hash = policy.configHash(); hash[0] ^= 1;
    assertArrayEquals(digest(manifest()), policy.configHash());
    var slot = new DeploymentCredentialLoader.HandoffSlot(); slot.close();
    Port port = new Port();
    assertThrows(RuntimeException.class, () -> new DeploymentCredentialLoader(port).load(policy,
        new DeploymentCredentialLoader.CustodyAttemptReceipt(), slot));
    assertArrayEquals(new byte[33], port.ownedReadBuffer); assertEquals(1, port.closes);
  }
}
