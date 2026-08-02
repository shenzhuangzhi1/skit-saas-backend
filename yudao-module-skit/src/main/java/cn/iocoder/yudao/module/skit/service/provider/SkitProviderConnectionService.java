package cn.iocoder.yudao.module.skit.service.provider;

import java.time.LocalDateTime;

public interface SkitProviderConnectionService {
  ConnectionView createSharedMaster(CreateSharedMasterCommand command);

  RouteView createDraftRoute(
      long providerConnectionId, RoutePurpose purpose, String reason, long actorUserId);

  IssuedRoute issueOnce(IssueRouteCommand command);

  RouteView abandonNeverShared(AbandonRouteCommand command);

  RouteView markSubmitted(MarkSubmittedCommand command);

  ConnectionView block(BlockConnectionCommand command);

  ProviderRouteResolution resolveProviderImpression(char[] callbackKey, LocalDateTime receivedAt);

  ProviderRouteResolution resolveProviderImpression(
      cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService.RouteLookup
          resolvedRoute,
      LocalDateTime receivedAt);

  enum RoutePurpose {
    GATE_TEST,
    PRODUCTION
  }

  final class CreateSharedMasterCommand {
    private char[] externalAccountReference;
    public final long actorUserId;

    public CreateSharedMasterCommand(char[] value, long actor) {
      externalAccountReference = value == null ? null : value.clone();
      actorUserId = actor;
    }

    synchronized char[] consumeExternalAccountReference() {
      if (externalAccountReference == null) {
        throw new IllegalStateException("External account reference has already been consumed");
      }
      char[] result = externalAccountReference;
      externalAccountReference = null;
      return result;
    }
  }

  final class IssueRouteCommand {
    public final long routeId, actorUserId;

    public IssueRouteCommand(long routeId, long actor) {
      this.routeId = routeId;
      this.actorUserId = actor;
    }
  }

  final class AbandonRouteCommand {
    public final long routeId, actorUserId;
    public final String neverSharedDeclaration;

    public AbandonRouteCommand(long id, long actor, String declaration) {
      routeId = id;
      actorUserId = actor;
      neverSharedDeclaration = declaration;
    }
  }

  final class MarkSubmittedCommand {
    public final long routeId, actorUserId;
    public final String ticket, reference, recipient;

    public MarkSubmittedCommand(
        long id, long actor, String ticket, String reference, String recipient) {
      routeId = id;
      actorUserId = actor;
      this.ticket = ticket;
      this.reference = reference;
      this.recipient = recipient;
    }
  }

  final class BlockConnectionCommand {
    public final long providerConnectionId, actorUserId;
    public final String reason;

    public BlockConnectionCommand(long id, long actor, String reason) {
      providerConnectionId = id;
      actorUserId = actor;
      this.reason = reason;
    }
  }

  final class ConnectionView {
    private final long id;
    private final String state;

    ConnectionView(long id, String state) {
      this.id = id;
      this.state = state;
    }

    public long getId() {
      return id;
    }

    public String getState() {
      return state;
    }
  }

  final class RouteView {
    private final long id;
    private final String routeStatus;
    private final String fingerprint;

    RouteView(long id, String state, String fingerprint) {
      this.id = id;
      this.routeStatus = state;
      this.fingerprint = fingerprint;
    }

    public long getId() {
      return id;
    }

    public String getRouteStatus() {
      return routeStatus;
    }

    public String getFingerprint() {
      return fingerprint;
    }
  }

  final class ProviderRouteResolution {
    private final long providerConnectionId;
    private final long providerRouteId;
    private final boolean accepting;

    ProviderRouteResolution(long connection, long route, boolean accepting) {
      this.providerConnectionId = connection;
      this.providerRouteId = route;
      this.accepting = accepting;
    }

    public long getProviderConnectionId() {
      return providerConnectionId;
    }

    public long getProviderRouteId() {
      return providerRouteId;
    }

    public boolean isAccepting() {
      return accepting;
    }
  }

  interface IssuedRoute {
    char[] consumeCallbackUrl();

    long getRouteId();

    String getFingerprint();
  }
}
