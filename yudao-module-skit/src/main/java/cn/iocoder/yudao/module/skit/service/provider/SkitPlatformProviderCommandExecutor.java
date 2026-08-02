package cn.iocoder.yudao.module.skit.service.provider;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PLATFORM_ADMIN_REQUIRED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PROVIDER_COMMAND_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PROVIDER_RESOURCE_NOT_FOUND;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderCallbackRouteDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderConnectionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitPlatformProviderCommandAuditDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderConnectionReadProjection;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderCallbackRouteMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderConnectionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitPlatformProviderCommandAuditMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderConnectionReadMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the outer transaction for platform-only provider lifecycle mutations and their audit row.
 *
 * <p>The lifecycle service uses REQUIRED transactions, so every mutation below joins this boundary.
 * Only bounded, allowlisted state is hashed or persisted to the command audit.
 */
@Service
public class SkitPlatformProviderCommandExecutor {

  public static final String NEVER_SHARED_DECLARATION = "CALLBACK_URL_WAS_NEVER_SHARED";

  private static final String RESULT_SUCCEEDED = "SUCCEEDED";
  private static final String RESULT_CODE_SUCCEEDED = "OK";
  private static final int MAX_REASON_LENGTH = 256;
  private static final Pattern CALLBACK_TOKEN =
      Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])");

  private final SkitProviderConnectionService connectionService;
  private final SkitCurrentAdminPasswordReauthService reauthService;
  private final SkitAdProviderConnectionMapper connectionMapper;
  private final SkitAdProviderCallbackRouteMapper routeMapper;
  private final SkitPlatformProviderCommandAuditMapper auditMapper;
  private final SkitProviderConnectionReadMapper readMapper;
  private final Clock clock;
  private final Supplier<String> traceIdSupplier;

  @Autowired
  public SkitPlatformProviderCommandExecutor(
      SkitProviderConnectionService connectionService,
      SkitCurrentAdminPasswordReauthService reauthService,
      SkitAdProviderConnectionMapper connectionMapper,
      SkitAdProviderCallbackRouteMapper routeMapper,
      SkitPlatformProviderCommandAuditMapper auditMapper,
      SkitProviderConnectionReadMapper readMapper) {
    this(
        connectionService,
        reauthService,
        connectionMapper,
        routeMapper,
        auditMapper,
        readMapper,
        Clock.systemUTC(),
        () -> UUID.randomUUID().toString());
  }

  /** Visible for deterministic transaction and fingerprint tests. */
  public SkitPlatformProviderCommandExecutor(
      SkitProviderConnectionService connectionService,
      SkitCurrentAdminPasswordReauthService reauthService,
      SkitAdProviderConnectionMapper connectionMapper,
      SkitAdProviderCallbackRouteMapper routeMapper,
      SkitPlatformProviderCommandAuditMapper auditMapper,
      SkitProviderConnectionReadMapper readMapper,
      Clock clock,
      Supplier<String> traceIdSupplier) {
    this.connectionService = Objects.requireNonNull(connectionService, "connectionService");
    this.reauthService = Objects.requireNonNull(reauthService, "reauthService");
    this.connectionMapper = Objects.requireNonNull(connectionMapper, "connectionMapper");
    this.routeMapper = Objects.requireNonNull(routeMapper, "routeMapper");
    this.auditMapper = Objects.requireNonNull(auditMapper, "auditMapper");
    this.readMapper = Objects.requireNonNull(readMapper, "readMapper");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
  }

  @Transactional(readOnly = true)
  public ResourceView getConnection(long connectionId) {
    requirePositive(connectionId);
    SkitProviderConnectionReadProjection projection =
        readMapper.selectSafeByConnectionId(connectionId);
    if (projection == null) {
      throw exception(PROVIDER_RESOURCE_NOT_FOUND);
    }
    return ResourceView.from(projection);
  }

  @Transactional(rollbackFor = Exception.class)
  public ResourceView createSharedMaster(
      char[] currentPassword, char[] externalAccountReference, String reason) {
    try {
      Actor actor = authenticate(currentPassword);
      String safeReason = validateSafeText(reason, 10, MAX_REASON_LENGTH);
      validateExternalReference(externalAccountReference);
      SkitProviderConnectionService.ConnectionView created =
          connectionService.createSharedMaster(
              new SkitProviderConnectionService.CreateSharedMasterCommand(
                  externalAccountReference, actor.userId));
      SkitAdProviderConnectionDO after = requiredConnection(created.getId());
      appendAudit(
          actor,
          "CREATE_SHARED_MASTER",
          after.getId(),
          null,
          null,
          safeReason,
          requestHash("CREATE_SHARED_MASTER", actor, null, null, null),
          null,
          stateHash(after, null));
      return ResourceView.from(after, null);
    } finally {
      clear(currentPassword);
      clear(externalAccountReference);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public ResourceView createDraftRoute(
      long connectionId,
      SkitProviderConnectionService.RoutePurpose purpose,
      char[] currentPassword,
      String reason) {
    try {
      Actor actor = authenticate(currentPassword);
      requirePositive(connectionId);
      if (purpose == null) {
        throw exception(PROVIDER_COMMAND_INVALID);
      }
      String safeReason = validateSafeText(reason, 10, MAX_REASON_LENGTH);
      SkitAdProviderConnectionDO before = requiredConnection(connectionId);
      SkitProviderConnectionService.RouteView created =
          connectionService.createDraftRoute(connectionId, purpose, safeReason, actor.userId);
      SkitAdProviderCallbackRouteDO afterRoute = requiredRoute(created.getId());
      SkitAdProviderConnectionDO afterConnection = requiredConnection(connectionId);
      appendAudit(
          actor,
          "CREATE_DRAFT_ROUTE",
          connectionId,
          afterRoute.getId(),
          null,
          safeReason,
          requestHash(
              "CREATE_DRAFT_ROUTE", actor, connectionId, afterRoute.getId(), purpose.name()),
          stateHash(before, null),
          stateHash(afterConnection, afterRoute));
      return ResourceView.from(afterConnection, afterRoute);
    } finally {
      clear(currentPassword);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public SkitProviderConnectionService.IssuedRoute issueOnce(
      long routeId, char[] currentPassword, String reason) {
    try {
      Actor actor = authenticate(currentPassword);
      requirePositive(routeId);
      String safeReason = validateSafeText(reason, 10, MAX_REASON_LENGTH);
      SkitAdProviderCallbackRouteDO beforeRoute = requiredRoute(routeId);
      SkitAdProviderConnectionDO beforeConnection =
          requiredConnection(beforeRoute.getProviderConnectionId());
      SkitProviderConnectionService.IssuedRoute issued =
          connectionService.issueOnce(
              new SkitProviderConnectionService.IssueRouteCommand(routeId, actor.userId));
      SkitAdProviderCallbackRouteDO afterRoute = requiredRoute(routeId);
      SkitAdProviderConnectionDO afterConnection =
          requiredConnection(afterRoute.getProviderConnectionId());
      appendAudit(
          actor,
          "ISSUE_ROUTE_ONCE",
          afterConnection.getId(),
          routeId,
          afterRoute.getCallbackRouteRegistryId(),
          safeReason,
          requestHash(
              "ISSUE_ROUTE_ONCE", actor, afterConnection.getId(), routeId, afterRoute.getPurpose()),
          stateHash(beforeConnection, beforeRoute),
          stateHash(afterConnection, afterRoute));
      return issued;
    } finally {
      clear(currentPassword);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public ResourceView abandonNeverShared(
      long routeId, char[] currentPassword, String neverSharedDeclaration) {
    try {
      Actor actor = authenticate(currentPassword);
      requirePositive(routeId);
      String declaration = validateSafeText(neverSharedDeclaration, 10, 64);
      if (!NEVER_SHARED_DECLARATION.equals(declaration)) {
        throw exception(PROVIDER_COMMAND_INVALID);
      }
      SkitAdProviderCallbackRouteDO beforeRoute = requiredRoute(routeId);
      SkitAdProviderConnectionDO beforeConnection =
          requiredConnection(beforeRoute.getProviderConnectionId());
      connectionService.abandonNeverShared(
          new SkitProviderConnectionService.AbandonRouteCommand(
              routeId, actor.userId, declaration));
      SkitAdProviderCallbackRouteDO afterRoute = requiredRoute(routeId);
      SkitAdProviderConnectionDO afterConnection =
          requiredConnection(afterRoute.getProviderConnectionId());
      appendAudit(
          actor,
          "ABANDON_NEVER_SHARED",
          afterConnection.getId(),
          routeId,
          afterRoute.getCallbackRouteRegistryId(),
          declaration,
          requestHash("ABANDON_NEVER_SHARED", actor, afterConnection.getId(), routeId, null),
          stateHash(beforeConnection, beforeRoute),
          stateHash(afterConnection, afterRoute));
      return ResourceView.from(afterConnection, afterRoute);
    } finally {
      clear(currentPassword);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public ResourceView markSubmitted(
      long routeId,
      char[] currentPassword,
      String ticket,
      String reference,
      String recipient,
      String reason) {
    try {
      Actor actor = authenticate(currentPassword);
      requirePositive(routeId);
      String safeTicket = validateSafeText(ticket, 1, 128);
      String safeReference = validateSafeText(reference, 1, 128);
      String safeRecipient = validateSafeText(recipient, 1, 255);
      String safeReason = validateSafeText(reason, 10, MAX_REASON_LENGTH);
      SkitAdProviderCallbackRouteDO beforeRoute = requiredRoute(routeId);
      SkitAdProviderConnectionDO beforeConnection =
          requiredConnection(beforeRoute.getProviderConnectionId());
      connectionService.markSubmitted(
          new SkitProviderConnectionService.MarkSubmittedCommand(
              routeId, actor.userId, safeTicket, safeReference, safeRecipient));
      SkitAdProviderCallbackRouteDO afterRoute = requiredRoute(routeId);
      SkitAdProviderConnectionDO afterConnection =
          requiredConnection(afterRoute.getProviderConnectionId());
      appendAudit(
          actor,
          "MARK_ROUTE_SUBMITTED",
          afterConnection.getId(),
          routeId,
          afterRoute.getCallbackRouteRegistryId(),
          safeReason,
          requestHash(
              "MARK_ROUTE_SUBMITTED",
              actor,
              afterConnection.getId(),
              routeId,
              afterRoute.getPurpose()),
          stateHash(beforeConnection, beforeRoute),
          stateHash(afterConnection, afterRoute));
      return ResourceView.from(afterConnection, afterRoute);
    } finally {
      clear(currentPassword);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public ResourceView block(long connectionId, char[] currentPassword, String reason) {
    try {
      Actor actor = authenticate(currentPassword);
      requirePositive(connectionId);
      String safeReason = validateSafeText(reason, 10, MAX_REASON_LENGTH);
      SkitAdProviderConnectionDO before = requiredConnection(connectionId);
      connectionService.block(
          new SkitProviderConnectionService.BlockConnectionCommand(
              connectionId, actor.userId, safeReason));
      SkitAdProviderConnectionDO after = requiredConnection(connectionId);
      appendAudit(
          actor,
          "BLOCK_CONNECTION",
          connectionId,
          null,
          null,
          safeReason,
          requestHash("BLOCK_CONNECTION", actor, connectionId, null, null),
          stateHash(before, null),
          stateHash(after, null));
      return ResourceView.from(after, null);
    } finally {
      clear(currentPassword);
    }
  }

  private Actor authenticate(char[] currentPassword) {
    LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
    if (loginUser == null
        || loginUser.getId() == null
        || loginUser.getId() <= 0
        || loginUser.getTenantId() == null
        || loginUser.getTenantId() <= 0) {
      clear(currentPassword);
      throw exception(PLATFORM_ADMIN_REQUIRED);
    }
    Actor actor = new Actor(loginUser.getId(), loginUser.getTenantId());
    reauthService.verifyCurrentUserPassword(currentPassword);
    return new Actor(actor.userId, actor.originalTenantId, now());
  }

  private void appendAudit(
      Actor actor,
      String action,
      Long connectionId,
      Long routeId,
      Long registryId,
      String reason,
      byte[] requestFingerprint,
      byte[] beforeStateHash,
      byte[] afterStateHash) {
    String traceId = traceIdSupplier.get();
    if (traceId == null
        || traceId.isEmpty()
        || traceId.length() > 64
        || !traceId.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalStateException("Invalid server trace id");
    }
    LocalDateTime occurredAt = now();
    SkitPlatformProviderCommandAuditDO audit =
        new SkitPlatformProviderCommandAuditDO()
            .setActorUserId(actor.userId)
            .setOriginalLoginTenantId(actor.originalTenantId)
            .setAction(action)
            .setProviderConnectionId(connectionId)
            .setProviderCallbackRouteId(routeId)
            .setCallbackRouteRegistryId(registryId)
            .setReason(reason)
            .setReauthenticatedAt(actor.reauthenticatedAt)
            .setRequestFingerprint(requestFingerprint)
            .setBeforeStateHash(beforeStateHash)
            .setAfterStateHash(afterStateHash)
            .setTraceId(traceId)
            .setResultStatus(RESULT_SUCCEEDED)
            .setResultCode(RESULT_CODE_SUCCEEDED)
            .setOccurredAt(occurredAt);
    if (auditMapper.insert(audit) != 1) {
      throw new IllegalStateException("Provider command audit insert count was not one");
    }
  }

  private SkitAdProviderConnectionDO requiredConnection(long connectionId) {
    SkitAdProviderConnectionDO connection = connectionMapper.selectById(connectionId);
    if (connection == null) {
      throw exception(PROVIDER_RESOURCE_NOT_FOUND);
    }
    return connection;
  }

  private SkitAdProviderCallbackRouteDO requiredRoute(long routeId) {
    SkitAdProviderCallbackRouteDO route = routeMapper.selectById(routeId);
    if (route == null) {
      throw exception(PROVIDER_RESOURCE_NOT_FOUND);
    }
    return route;
  }

  private static void requirePositive(long value) {
    if (value <= 0) {
      throw exception(PROVIDER_COMMAND_INVALID);
    }
  }

  private static void validateExternalReference(char[] externalAccountReference) {
    if (externalAccountReference == null
        || externalAccountReference.length == 0
        || externalAccountReference.length > 512) {
      throw exception(PROVIDER_COMMAND_INVALID);
    }
    for (char character : externalAccountReference) {
      if (!Character.isWhitespace(character)) {
        return;
      }
    }
    throw exception(PROVIDER_COMMAND_INVALID);
  }

  static String validateSafeText(String value, int min, int max) {
    if (value == null || value.length() > max) {
      throw exception(PROVIDER_COMMAND_INVALID);
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isISOControl(character) || character == '\u2028' || character == '\u2029') {
        throw exception(PROVIDER_COMMAND_INVALID);
      }
    }
    String trimmed = value.trim();
    if (trimmed.length() < min || trimmed.length() > max) {
      throw exception(PROVIDER_COMMAND_INVALID);
    }
    String folded = trimmed.toLowerCase(Locale.ROOT);
    if (folded.contains("http")
        || folded.contains("acct_")
        || CALLBACK_TOKEN.matcher(trimmed).find()) {
      throw exception(PROVIDER_COMMAND_INVALID);
    }
    return trimmed;
  }

  private static byte[] requestHash(
      String action, Actor actor, Long connectionId, Long routeId, String purpose) {
    return digest(
        "skit-provider-command-request-v1",
        action,
        Long.toString(actor.userId),
        Long.toString(actor.originalTenantId),
        nullable(connectionId),
        nullable(routeId),
        nullable(purpose));
  }

  private static byte[] stateHash(
      SkitAdProviderConnectionDO connection, SkitAdProviderCallbackRouteDO route) {
    return digest(
        "skit-provider-safe-state-v1",
        nullable(connection == null ? null : connection.getId()),
        nullable(connection == null ? null : connection.getProvider()),
        nullable(connection == null ? null : connection.getAccountMode()),
        nullable(connection == null ? null : connection.getState()),
        nullable(connection == null ? null : connection.getActiveCallbackRouteId()),
        nullable(route == null ? null : route.getId()),
        nullable(route == null ? null : route.getRouteVersion()),
        nullable(route == null ? null : route.getPurpose()),
        nullable(route == null ? null : route.getState()),
        nullable(route == null ? null : route.getRouteSlot()),
        nullable(route == null ? null : route.getCallbackKeyFingerprint()),
        nullable(route == null ? null : route.getCallbackPathVersion()),
        nullable(route == null ? null : route.getCallbackTemplateVersion()));
  }

  private static byte[] digest(String domain, String... fields) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(digest, domain);
      for (String field : fields) {
        update(digest, field);
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void update(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    try {
      digest.update((byte) (bytes.length >>> 24));
      digest.update((byte) (bytes.length >>> 16));
      digest.update((byte) (bytes.length >>> 8));
      digest.update((byte) bytes.length);
      digest.update(bytes);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static String nullable(Object value) {
    return value == null ? "<null>" : value.toString();
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  private static void clear(char[] value) {
    if (value != null) {
      Arrays.fill(value, '\0');
    }
  }

  private static final class Actor {
    private final long userId;
    private final long originalTenantId;
    private final LocalDateTime reauthenticatedAt;

    private Actor(long userId, long originalTenantId) {
      this(userId, originalTenantId, null);
    }

    private Actor(long userId, long originalTenantId, LocalDateTime reauthenticatedAt) {
      this.userId = userId;
      this.originalTenantId = originalTenantId;
      this.reauthenticatedAt = reauthenticatedAt;
    }
  }

  /** Dedicated read allowlist. It is intentionally not backed by BeanUtils or an entity getter. */
  public static final class ResourceView {
    private final long connectionId;
    private final String provider;
    private final String accountMode;
    private final String connectionState;
    private final Long activeCallbackRouteId;
    private final LocalDateTime connectionCreatedAt;
    private final LocalDateTime connectionUpdatedAt;
    private final LocalDateTime connectionBlockedAt;
    private final Long routeId;
    private final Integer routeVersion;
    private final String purpose;
    private final String routeState;
    private final String routeSlot;
    private final String canonicalOrigin;
    private final Integer callbackPathVersion;
    private final Integer callbackTemplateVersion;
    private final String callbackKeyFingerprint;
    private final LocalDateTime issuedAt;
    private final LocalDateTime submittedAt;
    private final LocalDateTime abandonedAt;
    private final LocalDateTime routeUpdatedAt;

    private ResourceView(
        SkitAdProviderConnectionDO connection, SkitAdProviderCallbackRouteDO route) {
      this.connectionId = connection.getId();
      this.provider = connection.getProvider();
      this.accountMode = connection.getAccountMode();
      this.connectionState = connection.getState();
      this.activeCallbackRouteId = connection.getActiveCallbackRouteId();
      this.connectionCreatedAt = connection.getCreatedAt();
      this.connectionUpdatedAt = connection.getUpdatedAt();
      this.connectionBlockedAt = connection.getBlockedAt();
      this.routeId = route == null ? null : route.getId();
      this.routeVersion = route == null ? null : route.getRouteVersion();
      this.purpose = route == null ? null : route.getPurpose();
      this.routeState = route == null ? null : route.getState();
      this.routeSlot = route == null ? null : route.getRouteSlot();
      this.canonicalOrigin = route == null ? null : route.getCanonicalOrigin();
      this.callbackPathVersion = route == null ? null : route.getCallbackPathVersion();
      this.callbackTemplateVersion = route == null ? null : route.getCallbackTemplateVersion();
      this.callbackKeyFingerprint = route == null ? null : route.getCallbackKeyFingerprint();
      this.issuedAt = route == null ? null : route.getIssuedAt();
      this.submittedAt = route == null ? null : route.getSubmittedAt();
      this.abandonedAt = route == null ? null : route.getAbandonedAt();
      this.routeUpdatedAt = route == null ? null : route.getUpdatedAt();
    }

    private ResourceView(SkitProviderConnectionReadProjection projection) {
      this.connectionId = projection.getConnectionId();
      this.provider = projection.getProvider();
      this.accountMode = projection.getAccountMode();
      this.connectionState = projection.getConnectionState();
      this.activeCallbackRouteId = projection.getActiveCallbackRouteId();
      this.connectionCreatedAt = projection.getConnectionCreatedAt();
      this.connectionUpdatedAt = projection.getConnectionUpdatedAt();
      this.connectionBlockedAt = projection.getConnectionBlockedAt();
      this.routeId = projection.getRouteId();
      this.routeVersion = projection.getRouteVersion();
      this.purpose = projection.getPurpose();
      this.routeState = projection.getRouteState();
      this.routeSlot = projection.getRouteSlot();
      this.canonicalOrigin = projection.getCanonicalOrigin();
      this.callbackPathVersion = projection.getCallbackPathVersion();
      this.callbackTemplateVersion = projection.getCallbackTemplateVersion();
      this.callbackKeyFingerprint = projection.getCallbackKeyFingerprint();
      this.issuedAt = projection.getIssuedAt();
      this.submittedAt = projection.getSubmittedAt();
      this.abandonedAt = projection.getAbandonedAt();
      this.routeUpdatedAt = projection.getRouteUpdatedAt();
    }

    private static ResourceView from(
        SkitAdProviderConnectionDO connection, SkitAdProviderCallbackRouteDO route) {
      return new ResourceView(connection, route);
    }

    private static ResourceView from(SkitProviderConnectionReadProjection projection) {
      return new ResourceView(projection);
    }

    public long getConnectionId() {
      return connectionId;
    }

    public String getProvider() {
      return provider;
    }

    public String getAccountMode() {
      return accountMode;
    }

    public String getConnectionState() {
      return connectionState;
    }

    public Long getActiveCallbackRouteId() {
      return activeCallbackRouteId;
    }

    public LocalDateTime getConnectionCreatedAt() {
      return connectionCreatedAt;
    }

    public LocalDateTime getConnectionUpdatedAt() {
      return connectionUpdatedAt;
    }

    public LocalDateTime getConnectionBlockedAt() {
      return connectionBlockedAt;
    }

    public Long getRouteId() {
      return routeId;
    }

    public Integer getRouteVersion() {
      return routeVersion;
    }

    public String getPurpose() {
      return purpose;
    }

    public String getRouteState() {
      return routeState;
    }

    public String getRouteSlot() {
      return routeSlot;
    }

    public String getCanonicalOrigin() {
      return canonicalOrigin;
    }

    public Integer getCallbackPathVersion() {
      return callbackPathVersion;
    }

    public Integer getCallbackTemplateVersion() {
      return callbackTemplateVersion;
    }

    public String getCallbackKeyFingerprint() {
      return callbackKeyFingerprint;
    }

    public LocalDateTime getIssuedAt() {
      return issuedAt;
    }

    public LocalDateTime getSubmittedAt() {
      return submittedAt;
    }

    public LocalDateTime getAbandonedAt() {
      return abandonedAt;
    }

    public LocalDateTime getRouteUpdatedAt() {
      return routeUpdatedAt;
    }
  }
}
