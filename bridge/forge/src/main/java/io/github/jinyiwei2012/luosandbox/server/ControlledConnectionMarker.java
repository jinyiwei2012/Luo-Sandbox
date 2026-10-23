package io.github.jinyiwei2012.luosandbox.server;

/** Monotonic marker carried by the exact game packet listener instance. */
public interface ControlledConnectionMarker {
  boolean luosandbox$everControlled();

  void luosandbox$markEverControlled();
}
