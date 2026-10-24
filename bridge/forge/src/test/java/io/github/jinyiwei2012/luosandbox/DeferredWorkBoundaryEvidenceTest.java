package io.github.jinyiwei2012.luosandbox;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Bounded original-byte inspection only; never loads Minecraft classes or applies transformations. */
class DeferredWorkBoundaryEvidenceTest {
  private static final String LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
  private static final String DEFENSE = "io/github/jinyiwei2012/luosandbox/server/ForgeServerDefense";
  private static final String LOOP = "net/minecraft/util/thread/BlockableEventLoop";
  private static final String REENTRANT = "net/minecraft/util/thread/ReentrantBlockableEventLoop";
  private static final String SERVER = "net/minecraft/server/MinecraftServer";
  private static final Map<String, String> HELPERS = Map.of(
      "broadcastChatMessage", "(Lnet/minecraft/network/chat/PlayerChatMessage;)V",
      "performChatCommand", "(Lnet/minecraft/network/protocol/game/ServerboundChatCommandPacket;Lnet/minecraft/network/chat/LastSeenMessages;)V",
      "updateBookContents", "(Ljava/util/List;I)V",
      "signBook", "(Lnet/minecraft/server/network/FilteredText;Ljava/util/List;I)V",
      "updateSignText", "(Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;Ljava/util/List;)V");
  private static final Set<String> LISTENER_SEEDS = Set.of(
      "handleChat", "handleChatCommand", "handleEditBook", "handleSignUpdate", "filterTextPacket",
      "chat", "tryHandleChat", "handleMessageDecodeFailure");
  private static final Set<String> LOOP_METHODS = Set.of(
      "execute", "executeIfPossible", "scheduleExecutables", "isSameThread", "tell", "submit",
      "submitAsync", "wrapRunnable", "shouldRun", "doRunTask", "pollTask", "runAllTasks",
      "runningTask", "getRunningThread");

  record Artifact(String resource, String artifact, String sha256, int byteLength,
                  String superName, List<String> interfaces) {}
  record Call(int instruction, int opcode, String owner, String name, String descriptor,
              String edgeKind) {}
  record Region(int start, int end, int handler, String exceptionType) {}
  record Field(int instruction, int opcode, String owner, String name, String descriptor) {}
  record MethodEvidence(String owner, String name, String descriptor, int access,
                        int instructionCount, List<Map<String, Integer>> returns,
                        List<Integer> athrows, List<Region> exceptionRegions,
                        List<Call> calls, List<Map<String, Integer>> jumps, List<Field> fields) {}

  @Test
  void enumerateOriginalDeferredAndTaskBoundariesWithoutClaimingDrain() throws Exception {
    Map<String, ClassNode> classes = new LinkedHashMap<>();
    List<Artifact> artifacts = new ArrayList<>();
    // Fixed class allowlist: no recursive loading of arbitrary callees.
    for (String name : List.of(LISTENER, DEFENSE, SERVER, LOOP, REENTRANT,
        "net/minecraft/server/TickTask")) {
      classes.put(name, read(name, artifacts));
    }
    assertEquals("7ad8af7bb6f18f3b1a1d99959b4826e5f475e424c177031f5ca0279332226e79",
        artifacts.get(0).sha256());
    ClassNode listener = classes.get(LISTENER);
    String mixinSource = Files.readString(Path.of(
        "src/main/java/io/github/jinyiwei2012/luosandbox/mixin/server/ServerGamePacketListenerImplMixin.java"));
    Set<String> selected = new LinkedHashSet<>();
    for (Map.Entry<String, String> helper : HELPERS.entrySet()) {
      exact(listener, helper.getKey(), helper.getValue());
      assertTrue(mixinSource.contains("method = \"" + helper.getKey() + helper.getValue() + "\""),
          "helper descriptor must also match the existing exact mixin selector");
      selected.add(helper.getKey() + helper.getValue());
    }
    // Reverse reachability is restricted to the single listener class and at most 8 rounds.
    // Both direct calls and invokedynamic implementation handles are enumerated, not executed.
    for (MethodNode method : listener.methods) {
      if (LISTENER_SEEDS.contains(method.name)) selected.add(method.name + method.desc);
    }
    boolean changed = true;
    int rounds = 0;
    while (changed && rounds++ < 8) {
      changed = false;
      for (MethodNode method : listener.methods) {
        for (Call call : evidence(LISTENER, method).calls()) {
          if (LISTENER.equals(call.owner()) && selected.contains(call.name() + call.descriptor())) {
            changed |= selected.add(method.name + method.desc);
          }
          if (selected.contains(method.name + method.desc) && LISTENER.equals(call.owner())
              && call.name().startsWith("lambda$")) {
            exact(listener, call.name(), call.descriptor());
            changed |= selected.add(call.name() + call.descriptor());
          }
        }
      }
      assertTrue(selected.size() <= 96, "listener method bound");
    }
    assertFalse(changed, "bounded class-local reverse closure must converge");
    List<MethodEvidence> methods = new ArrayList<>();
    for (MethodNode method : listener.methods) {
      if (selected.contains(method.name + method.desc)) methods.add(evidence(LISTENER, method));
    }
    MethodNode container = exact(classes.get(DEFENSE), "onContainerOpen",
        "(Lnet/minecraftforge/event/entity/player/PlayerContainerEvent$Open;)V");
    MethodEvidence containerEvidence = evidence(DEFENSE, container);
    methods.add(containerEvidence);
    Call fence = uniqueCall(containerEvidence, "io/github/jinyiwei2012/luosandbox/server/ControlledIdentityRegistry", "fence");
    Call execute = uniqueCall(containerEvidence, SERVER, "execute");
    assertEquals("(Ljava/lang/Runnable;)V", execute.descriptor());
    assertTrue(fence.instruction() < execute.instruction(), "lexical fence invocation before execute");
    Call close = uniqueCall(containerEvidence, "net/minecraft/server/level/ServerPlayer", "closeContainer");
    assertEquals("()V", close.descriptor());
    assertEquals("INVOKEDYNAMIC_IMPLEMENTATION_HANDLE", close.edgeKind());
    assertTrue(fence.instruction() < close.instruction() && close.instruction() < execute.instruction());
    assertEquals(REENTRANT, classes.get(SERVER).superName);
    assertEquals(LOOP, classes.get(REENTRANT).superName);
    assertTrue(classes.get(SERVER).methods.stream().noneMatch(m ->
        m.name.equals("execute") && m.desc.equals("(Ljava/lang/Runnable;)V")),
        "exact local server must inherit Runnable execute");
    assertTrue(classes.get(REENTRANT).methods.stream().noneMatch(m ->
        m.name.equals("execute") && m.desc.equals("(Ljava/lang/Runnable;)V")));
    MethodEvidence loopExecute = evidence(LOOP, exact(classes.get(LOOP), "execute", "(Ljava/lang/Runnable;)V"));
    uniqueCall(loopExecute, LOOP, "scheduleExecutables");
    uniqueCall(loopExecute, LOOP, "wrapRunnable");
    uniqueCall(loopExecute, LOOP, "tell");
    uniqueCall(loopExecute, "java/lang/Runnable", "run");
    assertTrue(loopExecute.jumps().stream().anyMatch(j -> j.get("opcode") == Opcodes.IFEQ));
    assertTrue(loopExecute.exceptionRegions().isEmpty());
    for (String owner : List.of(SERVER, LOOP, REENTRANT, "net/minecraft/server/TickTask")) {
      for (MethodNode method : classes.get(owner).methods) {
        if (LOOP_METHODS.contains(method.name) || owner.endsWith("/TickTask")) {
          methods.add(evidence(owner, method));
        }
      }
    }
    assertTrue(methods.size() <= 160);
    Path source = Path.of("src/test/java/io/github/jinyiwei2012/luosandbox/DeferredWorkBoundaryEvidenceTest.java");
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("label", "OFFLINE_ORIGINAL_TARGET_STRUCTURE_ONLY");
    report.put("generatorSource", source.toString().replace('\\', '/'));
    report.put("generatorSourceSha256", hash(Files.readAllBytes(source)));
    report.put("artifactProfile", "Forge 1.20.1 / 47.4.28 local mapped build artifacts");
    report.put("artifacts", artifacts);
    report.put("helperDescriptors", new TreeMap<>(HELPERS));
    report.put("listenerSeeds", new TreeSet<>(LISTENER_SEEDS));
    report.put("eventLoopMethodAllowlist", new TreeSet<>(LOOP_METHODS));
    report.put("methods", methods);
    List<Map<String, Object>> helperCallSites = new ArrayList<>();
    for (MethodNode method : listener.methods) {
      for (Call call : evidence(LISTENER, method).calls()) {
        if (LISTENER.equals(call.owner()) && Objects.equals(HELPERS.get(call.name()), call.descriptor())) {
          helperCallSites.add(Map.of("callerName", method.name, "callerDescriptor", method.desc, "edge", call));
        }
      }
    }
    report.put("allClassLocalHelperCallSites", helperCallSites);
    report.put("producerAdmissionEstablished", false);
    report.put("limits", Map.of("classBytes", 2000000, "classes", 6, "methodsPerClass", 512,
        "instructionsPerMethod", 20000, "listenerMethods", 96, "reverseRounds", 8, "reportedMethods", 160));
    report.put("unresolvedEdges", List.of(
        "Cross-class CompletableFuture completion, cancellation and callback scheduling",
        "Chat chain admission and dynamically dispatched filter/chat implementations",
        "External executor, Forge hooks and upstream vanilla producers outside the explicit allowlist",
        "Runtime event-loop overrides, shutdown races, accepted enqueue then submission throw",
        "Actual Runnable completion, closeContainer exception effects and body identity at submission",
        "Whole vanilla/Forge deferred scope closure and any unenumerated producers"));
    report.put("scopeStatus", Map.of("CHAT_DEFERRED", "UNAVAILABLE", "SCHEDULED_SERVER_TASKS", "UNAVAILABLE",
        "PERMIT_QUEUE_WATCHDOG", "UNAVAILABLE", "CLIENT_PERMIT_CONSUMPTION", "UNAVAILABLE"));
    report.put("productionComplete", false);
    Path output = Path.of(Objects.requireNonNull(System.getenv("GRADLE_USER_HOME")),
        "interop/evidence/deferred-work-boundary-evidence.json");
    Files.createDirectories(output.getParent());
    Files.writeString(output, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report));
    System.out.println("OFFLINE_ORIGINAL_TARGET_STRUCTURE_ONLY classes=" + artifacts.size()
        + " methods=" + methods.size() + " reportSha256=" + hash(Files.readAllBytes(output)));
  }

  private static ClassNode read(String owner, List<Artifact> artifacts) throws Exception {
    String resource = owner + ".class";
    URL url = DeferredWorkBoundaryEvidenceTest.class.getClassLoader().getResource(resource);
    assertNotNull(url, "required local artifact: " + resource);
    byte[] bytes;
    try (InputStream in = url.openStream()) {
      bytes = in.readNBytes(2000001);
    }
    assertTrue(bytes.length <= 2000000);
    byte[] independentlyRead;
    String artifact;
    if (url.openConnection() instanceof JarURLConnection connection) {
      Path jar = Path.of(connection.getJarFileURL().toURI());
      artifact = jar.toString();
      try (JarFile file = new JarFile(jar.toFile()); InputStream in = file.getInputStream(file.getJarEntry(resource))) {
        independentlyRead = in.readNBytes(2000001);
      }
    } else {
      assertEquals("file", url.getProtocol());
      Path file = Path.of(url.toURI());
      artifact = file.toString();
      independentlyRead = Files.readAllBytes(file);
    }
    assertArrayEquals(bytes, independentlyRead, "direct local artifact bytes must match resource bytes");
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    assertEquals(owner, node.name);
    assertTrue(node.methods.size() <= 512);
    artifacts.add(new Artifact(resource, artifact, hash(bytes), bytes.length, node.superName, node.interfaces));
    return node;
  }

  private static MethodNode exact(ClassNode node, String name, String desc) {
    List<MethodNode> matches = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).toList();
    assertEquals(1, matches.size(), node.name + "." + name + desc);
    return matches.get(0);
  }

  private static Call uniqueCall(MethodEvidence method, String owner, String name) {
    List<Call> calls = method.calls().stream().filter(c -> c.owner().equals(owner) && c.name().equals(name)).toList();
    assertEquals(1, calls.size(), owner + "." + name);
    return calls.get(0);
  }

  private static MethodEvidence evidence(String owner, MethodNode method) {
    assertTrue(method.instructions.size() <= 20000);
    List<Call> calls = new ArrayList<>();
    List<Map<String, Integer>> returns = new ArrayList<>();
    List<Integer> athrows = new ArrayList<>();
    List<Map<String, Integer>> jumps = new ArrayList<>();
    List<Field> fields = new ArrayList<>();
    int index = 0;
    for (AbstractInsnNode insn : method.instructions) {
      int op = insn.getOpcode();
      if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) returns.add(Map.of("instruction", index, "opcode", op));
      if (op == Opcodes.ATHROW) athrows.add(index);
      if (insn instanceof MethodInsnNode call) calls.add(new Call(index, op, call.owner, call.name, call.desc, "DIRECT_INVOKE"));
      if (insn instanceof InvokeDynamicInsnNode dynamic) {
        calls.add(new Call(index, op, dynamic.bsm.getOwner(), dynamic.name, dynamic.desc, "INVOKEDYNAMIC_BOOTSTRAP_SITE"));
        for (Object argument : dynamic.bsmArgs) {
          if (argument instanceof Handle handle) calls.add(new Call(index, handle.getTag(), handle.getOwner(),
              handle.getName(), handle.getDesc(), "INVOKEDYNAMIC_IMPLEMENTATION_HANDLE"));
        }
      }
      if (insn instanceof JumpInsnNode jump) jumps.add(Map.of("instruction", index, "opcode", op,
          "target", method.instructions.indexOf(jump.label)));
      if (insn instanceof FieldInsnNode field) fields.add(new Field(index, op, field.owner, field.name, field.desc));
      index++;
    }
    List<Region> regions = method.tryCatchBlocks.stream().map(b -> new Region(
        method.instructions.indexOf(b.start), method.instructions.indexOf(b.end),
        method.instructions.indexOf(b.handler), b.type == null ? "<finally>" : b.type)).toList();
    return new MethodEvidence(owner, method.name, method.desc, method.access, index, returns, athrows, regions, calls, jumps, fields);
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
