package io.github.jinyiwei2012.luosandbox.transport;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import java.net.URI;
import java.util.Objects;

/** Immutable, local-only outbound transport configuration. Credentials never form a URI or log value. */
public record ControlTransportConfig(boolean enabled, URI endpoint, ControlAuth.Role peerRole,
    String peerInstallId, String hubInstallId, String keyId, String protocol, byte[] modHash,
    byte[] configHash, byte[] bindingHash, byte[] credential) {
  public static final String PROTOCOL = "luosandbox.minecraft.v1";

  public ControlTransportConfig {
    Objects.requireNonNull(endpoint, "endpoint");
    if (peerRole != ControlAuth.Role.SERVER_AUTHORITY
        && peerRole != ControlAuth.Role.CLIENT_CONTROLLER) {
      throw new IllegalArgumentException("peer role must be server or client");
    }
    ControlAuth.requireId(peerInstallId);
    ControlAuth.requireId(hubInstallId);
    ControlAuth.requireId(keyId);
    if (!PROTOCOL.equals(protocol)) {
      throw new IllegalArgumentException("unsupported protocol");
    }
    ControlAuth.requireHash(modHash);
    ControlAuth.requireHash(configHash);
    ControlAuth.requireHash(bindingHash);
    ControlAuth.requireHash(credential);
    validateLocalEndpoint(endpoint);
    modHash = modHash.clone();
    configHash = configHash.clone();
    bindingHash = bindingHash.clone();
    credential = credential.clone();
  }

  @Override public byte[] modHash() { return modHash.clone(); }
  @Override public byte[] configHash() { return configHash.clone(); }
  @Override public byte[] bindingHash() { return bindingHash.clone(); }
  @Override public byte[] credential() { return credential.clone(); }

  /** Creates a disabled config. It cannot start a socket even if its endpoint is otherwise valid. */
  public static ControlTransportConfig disabled(URI endpoint, ControlAuth.Role role,
      String peerInstallId, String hubInstallId, String keyId, byte[] modHash, byte[] configHash,
      byte[] bindingHash, byte[] credential) {
    return new ControlTransportConfig(false, endpoint, role, peerInstallId, hubInstallId, keyId,
        PROTOCOL, modHash, configHash, bindingHash, credential);
  }

  private static void validateLocalEndpoint(URI value) {
    if (!"ws".equals(value.getScheme()) || value.getRawUserInfo() != null
        || value.getRawQuery() != null || value.getRawFragment() != null || value.getPort() < 0
        || value.getHost() == null
        || !(value.getHost().equals("127.0.0.1") || value.getHost().equals("[::1]")
            || value.getHost().equals("::1"))) {
      throw new IllegalArgumentException("endpoint must be ws loopback literal without credentials or tokens");
    }
  }
}
