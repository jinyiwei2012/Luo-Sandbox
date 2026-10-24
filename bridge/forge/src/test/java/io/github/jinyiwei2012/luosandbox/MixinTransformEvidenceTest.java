package io.github.jinyiwei2012.luosandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Offline bytecode evidence for the exact mapped {@code ServerGamePacketListenerImpl} class used by
 * this build. It inspects original target bytes only; it does not apply Mixin and proves nothing
 * about runtime injection.
 */
class MixinTransformEvidenceTest {
  private static final String TARGET_RESOURCE =
      "net/minecraft/server/network/ServerGamePacketListenerImpl.class";
  private static final String METHOD = "handleMovePlayer";
  private static final String DESCRIPTOR =
      "(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V";
  private static final String HANDOFF_OWNER = "net/minecraft/network/protocol/PacketUtils";
  private static final String HANDOFF_NAME = "ensureRunningOnSameThread";
  private static final String HANDOFF_DESC =
      "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
          + "Lnet/minecraft/server/level/ServerLevel;)V";

  record Counts(
      int normalReturns,
      int handoffInvocations,
      int athrows,
      int tryCatchRegions,
      int distinctHandlers,
      int instructionCount) {}

  @Test
  void originalMappedHandleMovePlayerCardinalityIsPinned() throws Exception {
    byte[] bytes = targetBytes();
    String sha256 = sha256(bytes);
    MethodNode method = findMethod(bytes);
    Counts counts = count(method);
    String json = json(sha256, bytes.length, counts);
    System.out.println("MIXIN_TARGET_EVIDENCE " + json);
    writeEvidence(json);

    assertEquals(PINNED_SHA256, sha256);
    assertEquals(PINNED_RETURNS, counts.normalReturns());
    assertEquals(1, counts.handoffInvocations());
    assertEquals(PINNED_ATHROWS, counts.athrows());
    assertEquals(PINNED_TRY_CATCH, counts.tryCatchRegions());
    assertEquals(PINNED_HANDLERS, counts.distinctHandlers());
  }

  private static final String PINNED_SHA256 =
      "7ad8af7bb6f18f3b1a1d99959b4826e5f475e424c177031f5ca0279332226e79";
  private static final int PINNED_RETURNS = 2;
  private static final int PINNED_ATHROWS = 0;
  private static final int PINNED_TRY_CATCH = 0;
  private static final int PINNED_HANDLERS = 0;

  static byte[] targetBytes() throws Exception {
    ClassLoader loader = MixinTransformEvidenceTest.class.getClassLoader();
    try (InputStream in = loader.getResourceAsStream(TARGET_RESOURCE)) {
      assertNotNull(in, "mapped target class must be on the test classpath");
      return in.readAllBytes();
    }
  }

  static MethodNode findMethod(byte[] bytes) {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    List<MethodNode> matches = new ArrayList<>();
    for (MethodNode method : node.methods) {
      if (METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) matches.add(method);
    }
    assertEquals(1, matches.size(), "exactly one mapped handleMovePlayer(ServerboundMovePlayerPacket)");
    return matches.get(0);
  }

  static Counts count(MethodNode method) {
    int returns = 0;
    int handoffs = 0;
    int athrows = 0;
    int instructions = 0;
    for (AbstractInsnNode insn : method.instructions) {
      int opcode = insn.getOpcode();
      if (opcode < 0) continue;
      instructions++;
      if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) returns++;
      if (opcode == Opcodes.ATHROW) athrows++;
      if (insn instanceof MethodInsnNode call
          && opcode == Opcodes.INVOKESTATIC
          && HANDOFF_OWNER.equals(call.owner)
          && HANDOFF_NAME.equals(call.name)
          && HANDOFF_DESC.equals(call.desc)) {
        handoffs++;
      }
    }
    long handlers =
        method.tryCatchBlocks.stream().map((TryCatchBlockNode block) -> block.handler).distinct().count();
    return new Counts(returns, handoffs, athrows, method.tryCatchBlocks.size(), (int) handlers, instructions);
  }

  static String json(String sha256, int length, Counts counts) {
    return "{\"target\":\"" + TARGET_RESOURCE + "\",\"method\":\"" + METHOD + DESCRIPTOR
        + "\",\"targetSha256\":\"" + sha256 + "\",\"targetLength\":" + length
        + ",\"normalReturns\":" + counts.normalReturns()
        + ",\"ensureRunningOnSameThreadInvocations\":" + counts.handoffInvocations()
        + ",\"athrows\":" + counts.athrows()
        + ",\"tryCatchRegions\":" + counts.tryCatchRegions()
        + ",\"distinctExceptionHandlers\":" + counts.distinctHandlers()
        + ",\"instructions\":" + counts.instructionCount() + "}";
  }

  static void writeEvidence(String json) throws Exception {
    String home = System.getenv("GRADLE_USER_HOME");
    if (home == null || home.isBlank()) return;
    Path dir = Path.of(home, "interop", "evidence");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("movement-mixin-target-evidence.json"), json, StandardCharsets.UTF_8);
  }

  static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
