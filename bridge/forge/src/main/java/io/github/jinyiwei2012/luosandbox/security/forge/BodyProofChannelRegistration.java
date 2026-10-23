package io.github.jinyiwei2012.luosandbox.security.forge;

import io.github.jinyiwei2012.luosandbox.security.BodyProofIngress;
import io.github.jinyiwei2012.luosandbox.security.BodyProofClientOwner;
import java.util.function.Function;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Explicit opt-in BODY channel owner. Class loading does not create/register a channel.
 *
 * <p>The network phase reads only an immutable, volatile route snapshot and connection/direction
 * metadata. A queued task retains its original generation, never resolves a replacement ingress.
 * Unknown outer discriminators never reach this consumer: they have no authentication or one-use
 * consumption guarantee here and pending state remains bounded by its fixed five-second expiry.
 */
public final class BodyProofChannelRegistration<S, C> {
  public static final String CHANNEL = "luosandbox:body_proof_v1";
  public static final String VERSION = "1";
  public static final int MESSAGE_ID = 0;
  public static final int CHALLENGE_ID = 1;

  public interface ClientContext<C> {
    C connection();
    boolean playToClient();
    CompletableFuture<Void> enqueue(Runnable work);
    void handled();
  }

  @FunctionalInterface public interface FrameSender<C> {
    void send(C connection, byte[] raw) throws Exception;
  }

  public static final class ClientHandle<C> {
    private final C connection;
    private ClientHandle(C connection) { this.connection = connection; }
    @Override public String toString() { return "BodyClientRoute[redacted]"; }
  }

  private record ClientRoute<C>(ClientHandle<C> handle, BodyProofClientOwner<C> owner) {}

  /** Narrow testable transport port; never supplies player/world identity. */
  public interface NetworkContext<C> {
    C connection();
    BodyProofChannelHandler.Direction direction();
    CompletableFuture<Void> enqueue(Runnable work);
    void handled();
  }

  @FunctionalInterface
  public interface Registrar<C> {
    Object register(BiConsumer<BodyProofNetworkFrame, NetworkContext<C>> consumer);
  }

  private record Route<S, C>(S server,
      BodyProofChannelHandler.OwnerHandle<S, C> handle, BodyProofChannelHandler<S, C> handler) {}

  private final BodyProofIngress.TrustedThreadGate thread;
  private final BodyProofChannelHandler.ConnectionCloser<C> closer;
  private final BodyProofChannelHandler<S, C> handler;
  private volatile Route<S, C> route;
  private Registrar<C> registrar;
  private Object channel;
  private boolean registrationAttempted;
  private volatile ClientRoute<C> clientRoute;
  private ClientHandle<C> clientReservation;
  private volatile BodyProofClientOwner.TrustedThreadGate clientThread;
  private FrameSender<C> proofSender;
  private FrameSender<C> challengeSender;

  public BodyProofChannelRegistration(BodyProofIngress.TrustedThreadGate thread,
      BodyProofChannelHandler.ConnectionCloser<C> closer) {
    this.thread = Objects.requireNonNull(thread);
    this.closer = Objects.requireNonNull(closer);
    handler = new BodyProofChannelHandler<>(thread, closer);
  }

  public static boolean acceptsVersion(String version) { return VERSION.equals(version); }

  /** The only real Forge registration factory; calling it is an explicit registration operation. */
  public static BodyProofChannelRegistration<MinecraftServer, Connection> registerForge(
      BodyProofIngress.TrustedThreadGate thread) {
    var owner = new BodyProofChannelRegistration<MinecraftServer, Connection>(thread,
        connection -> connection.disconnect(Component.literal("BODY proof rejected")));
    owner.register(consumer -> {
      SimpleChannel channel = NetworkRegistry.newSimpleChannel(ResourceLocation.parse(CHANNEL),
          () -> VERSION, BodyProofChannelRegistration::acceptsVersion,
          BodyProofChannelRegistration::acceptsVersion);
      channel.messageBuilder(BodyProofNetworkFrame.class, MESSAGE_ID, NetworkDirection.PLAY_TO_SERVER)
          .encoder(BodyProofNetworkFrame::encode)
          .decoder(BodyProofNetworkFrame::decode)
          .consumerNetworkThread((BodyProofNetworkFrame frame,
              java.util.function.Supplier<NetworkEvent.Context> supplied) -> {
            NetworkEvent.Context context = supplied.get();
            consumer.accept(frame, new NetworkContext<Connection>() {
              @Override public Connection connection() { return context.getNetworkManager(); }
              @Override public BodyProofChannelHandler.Direction direction() {
                return context.getDirection() == NetworkDirection.PLAY_TO_SERVER
                    ? BodyProofChannelHandler.Direction.PLAY_TO_SERVER
                    : BodyProofChannelHandler.Direction.OTHER;
              }
              @Override public CompletableFuture<Void> enqueue(Runnable work) {
                return context.enqueueWork(work);
              }
              @Override public void handled() { context.setPacketHandled(true); }
            });
          }).add();
      channel.messageBuilder(BodyChallengeNetworkFrame.class, CHALLENGE_ID, NetworkDirection.PLAY_TO_CLIENT)
          .encoder(BodyChallengeNetworkFrame::encode)
          .decoder(BodyChallengeNetworkFrame::decode)
          .consumerNetworkThread((BodyChallengeNetworkFrame frame,
              java.util.function.Supplier<NetworkEvent.Context> supplied) -> {
            NetworkEvent.Context context = supplied.get();
            owner.receiveChallenge(frame, new ClientContext<Connection>() {
              @Override public Connection connection() { return context.getNetworkManager(); }
              @Override public boolean playToClient() { return context.getDirection() == NetworkDirection.PLAY_TO_CLIENT; }
              @Override public CompletableFuture<Void> enqueue(Runnable work) { return context.enqueueWork(work); }
              @Override public void handled() { context.setPacketHandled(true); }
            });
          }).add();
      owner.bindSenders(
          (connection, raw) -> channel.sendTo(BodyProofNetworkFrame.raw(raw), connection, NetworkDirection.PLAY_TO_SERVER),
          (connection, raw) -> channel.sendTo(BodyChallengeNetworkFrame.raw(raw), connection, NetworkDirection.PLAY_TO_CLIENT));
      return channel;
    });
    return owner;
  }

  /** Repeated registration requires the exact same registrar reference. Failure is not retryable. */
  public synchronized Object register(Registrar<C> requested) {
    Objects.requireNonNull(requested);
    if (registrationAttempted) {
      if (registrar != requested || channel == null) {
        throw new IllegalStateException("BODY channel registration already attempted");
      }
      return channel;
    }
    registrationAttempted = true;
    registrar = requested;
    channel = Objects.requireNonNull(requested.register(this::receive));
    return channel;
  }

  public Optional<BodyProofChannelHandler.OwnerHandle<S, C>> install(
      S server, BodyProofIngress<C> ingress, BodyProofChannelHandler.Observer<C> observer) {
    if (!onThread()) return Optional.empty();
    synchronized (this) {
      var installed = handler.install(server, ingress, observer);
      if (installed.isPresent() && route == null) {
        route = new Route<>(server, installed.get(), handler);
      }
      return installed;
    }
  }

  public boolean uninstall(S server, BodyProofChannelHandler.OwnerHandle<S, C> captured) {
    if (!onThread()) return false;
    synchronized (this) {
      if (!handler.uninstall(server, captured)) return false;
      route = null; // Withdraw only after owner-thread ingress close succeeded.
      return true;
    }
  }

  public boolean disconnect(S server, BodyProofChannelHandler.OwnerHandle<S, C> captured,
      java.util.function.Supplier<C> connection) {
    if (!onThread()) return false;
    return handler.disconnect(server, captured, connection);
  }

  /** Explicit injectable transport ports; once bound they cannot be replaced. No channel is created. */
  public synchronized void bindSenders(FrameSender<C> proof, FrameSender<C> challenge) {
    if (proofSender != null || challengeSender != null) throw new IllegalStateException("BODY senders already bound");
    proofSender = Objects.requireNonNull(proof);
    challengeSender = Objects.requireNonNull(challenge);
  }

  /** Factory resolves constructor circularity: the owner receives a sender bound to this reservation. */
  public Optional<ClientHandle<C>> installClient(C exactConnection,
      BodyProofClientOwner.TrustedThreadGate thread,
      Function<BodyProofClientOwner.ProofSender<C>, BodyProofClientOwner<C>> factory) {
    try { if (!thread.isOwnerThread()) return Optional.empty(); }
    catch (RuntimeException error) { return Optional.empty(); }
    ClientHandle<C> reservation;
    synchronized (this) {
      if (clientReservation != null || proofSender == null) return Optional.empty();
      reservation = new ClientHandle<>(Objects.requireNonNull(exactConnection));
      clientReservation = reservation;
      clientThread = thread;
    }
    BodyProofClientOwner<C> owner;
    try {
      owner = Objects.requireNonNull(factory.apply((connection, raw) -> {
        if (!thread.isOwnerThread()) throw new IllegalStateException("Wrong BODY client thread");
        ClientRoute<C> current = clientRoute;
        if (current == null || current.handle() != reservation || connection != reservation.connection) {
          throw new IllegalStateException("Stale BODY client sender");
        }
        proofSender.send(connection, raw);
      }));
    } catch (RuntimeException error) {
      synchronized (this) { if (clientReservation == reservation) clientReservation = null; }
      return Optional.empty();
    }
    synchronized (this) {
      if (clientReservation != reservation || !onClientThread()) {
        // Keep the reservation if safe owner-thread cleanup cannot be confirmed.
        if (owner.close()) clientReservation = null;
        return Optional.empty();
      }
      clientRoute = new ClientRoute<>(reservation, owner);
      return Optional.of(reservation);
    }
  }

  public boolean uninstallClient(ClientHandle<C> captured) {
    if (!onClientThread()) return false;
    synchronized (this) {
      ClientRoute<C> current = clientRoute;
      if (current == null || current.handle() != captured) return false;
      if (!current.owner().close()) return false;
      clientRoute = null;
      clientReservation = null;
      return true;
    }
  }

  public BodyProofChallengeIssuer.ChallengeSender<C> challengeSender(
      S server, BodyProofChannelHandler.OwnerHandle<S, C> captured, C exactConnection) {
    return (connection, raw) -> {
      if (!onThread()) throw new IllegalStateException("Wrong BODY server thread");
      Route<S, C> current = route;
      if (current == null || current.server() != server || current.handle() != captured
          || connection != exactConnection || challengeSender == null) {
        throw new IllegalStateException("Stale BODY challenge sender");
      }
      challengeSender.send(connection, raw);
    };
  }

  // Package-private receive seam: the production consumer above supplies only transport metadata.
  void receiveChallenge(BodyChallengeNetworkFrame frame, ClientContext<C> context) {
    ClientRoute<C> captured = clientRoute;
    C connection = null;
    try {
      connection = Objects.requireNonNull(context.connection());
      C exact = connection;
      boolean direction = context.playToClient();
      if (captured == null || captured.handle().connection != exact || !direction) {
        closeExact(exact); return;
      }
      Objects.requireNonNull(context.enqueue(() -> {
        if (!onClientThread() || clientRoute != captured) { closeExact(exact); return; }
        try {
          var result = captured.owner().handleChallenge(() -> exact,
              frame == null || frame.malformed() ? null : frame.raw());
          if (result == BodyProofClientOwner.Result.WRONG_THREAD) closeExact(exact);
        } catch (RuntimeException error) { closeExact(exact); }
      })).whenComplete((ignored, error) -> { if (error != null) closeExact(exact); });
    } catch (RuntimeException error) {
      if (connection != null) closeExact(connection);
    } finally {
      try { context.handled(); }
      catch (RuntimeException error) { if (connection != null) closeExact(connection); }
    }
  }

  private boolean onClientThread() {
    try { return clientThread != null && clientThread.isOwnerThread(); }
    catch (RuntimeException error) { return false; }
  }

  private void receive(BodyProofNetworkFrame frame, NetworkContext<C> context) {
    Route<S, C> captured = route; // No owner lock or mutable ingress access on the network thread.
    C connection = null;
    try {
      connection = Objects.requireNonNull(context.connection());
      C exact = connection;
      BodyProofChannelHandler.Direction direction = context.direction();
      if (captured == null) {
        closeExact(exact);
        return;
      }
      CompletableFuture<Void> queued = Objects.requireNonNull(context.enqueue(() -> {
        try {
          var decision = captured.handler().dispatch(captured.server(), captured.handle(),
              () -> new BodyProofChannelHandler.Context<>(direction, exact), frame);
          if (decision == BodyProofChannelHandler.Decision.STALE_OWNER
              || decision == BodyProofChannelHandler.Decision.WRONG_THREAD) closeExact(exact);
        } catch (RuntimeException error) {
          captured.handler().disconnect(captured.server(), captured.handle(), () -> exact);
          closeExact(exact);
        }
      }));
      queued.whenComplete((ignored, error) -> { if (error != null) closeExact(exact); });
    } catch (RuntimeException error) {
      if (connection != null) closeExact(connection);
    } finally {
      try { context.handled(); }
      catch (RuntimeException error) { if (connection != null) closeExact(connection); }
    }
  }

  private boolean onThread() {
    try { return thread.isOwnerThread(); }
    catch (RuntimeException error) { return false; }
  }

  private void closeExact(C connection) {
    try { closer.close(connection); }
    catch (RuntimeException ignored) { /* Rejection does not imply successful disconnect/cleanup */ }
  }
}
