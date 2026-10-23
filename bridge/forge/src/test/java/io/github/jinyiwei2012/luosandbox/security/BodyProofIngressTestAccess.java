package io.github.jinyiwei2012.luosandbox.security;

import java.util.function.Supplier;

/** Test-only package bridge; production acceptance remains package-private. */
public final class BodyProofIngressTestAccess {
  private BodyProofIngressTestAccess() {}

  public static <C> boolean accept(
      BodyProofIngress<C> ingress,
      PendingBodyProof.VerifiedGameConnectionProof<C> proof,
      Supplier<C> connection) {
    return ingress.acceptVerified(proof, connection);
  }
}
