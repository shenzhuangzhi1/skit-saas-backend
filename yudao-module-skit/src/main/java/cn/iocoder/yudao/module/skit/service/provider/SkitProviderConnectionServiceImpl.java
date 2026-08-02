package cn.iocoder.yudao.module.skit.service.provider;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderCallbackRouteDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderConnectionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderCallbackRouteMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderConnectionMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitCallbackPublicUrlService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SkitProviderConnectionServiceImpl implements SkitProviderConnectionService {
  private static final char[] BASE64_URL =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
  private final SkitAdProviderConnectionMapper connectionMapper;
  private final SkitAdProviderCallbackRouteMapper routeMapper;
  private final SkitCallbackRouteRegistryService registryService;
  private final SkitCallbackPublicUrlService urlService;
  private final SkitProviderImpressionProductionGate productionGate;
  private final SecureRandom random;
  private final Clock clock;

  @Autowired
  public SkitProviderConnectionServiceImpl(
      SkitAdProviderConnectionMapper connectionMapper,
      SkitAdProviderCallbackRouteMapper routeMapper,
      SkitCallbackRouteRegistryService registryService,
      SkitCallbackPublicUrlService urlService,
      SkitProviderImpressionProductionGate productionGate) {
    this(
        connectionMapper,
        routeMapper,
        registryService,
        urlService,
        productionGate,
        new SecureRandom(),
        Clock.systemUTC());
  }

  public SkitProviderConnectionServiceImpl(
      SkitAdProviderConnectionMapper connectionMapper,
      SkitAdProviderCallbackRouteMapper routeMapper,
      SkitCallbackRouteRegistryService registryService,
      SkitCallbackPublicUrlService urlService,
      SkitProviderImpressionProductionGate productionGate,
      SecureRandom random,
      Clock clock) {
    this.connectionMapper = connectionMapper;
    this.routeMapper = routeMapper;
    this.registryService = registryService;
    this.urlService = urlService;
    this.productionGate = productionGate;
    this.random = random;
    this.clock = clock;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ConnectionView createSharedMaster(CreateSharedMasterCommand command) {
    return ignoreTenant(
        () -> {
          require(command != null, "Invalid shared connection command");
          char[] externalAccountReference = command.consumeExternalAccountReference();
          byte[] ref = null;
          char[] code = null;
          try {
            require(
                command.actorUserId > 0 && externalAccountReference.length > 0,
                "Invalid shared connection command");
            LocalDateTime now = now();
            ref = externalAccountReferenceHash(externalAccountReference);
            code = randomCode();
            SkitAdProviderConnectionDO row =
                new SkitAdProviderConnectionDO()
                    .setConnectionCode(new String(code))
                    .setProvider("TAKU")
                    .setAccountMode("SHARED_MASTER")
                    .setExternalAccountRefHash(ref)
                    .setState("CONFIGURING")
                    .setCreatedByUserId(command.actorUserId)
                    .setUpdatedByUserId(command.actorUserId)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            connectionMapper.insert(row);
            return new ConnectionView(row.getId(), row.getState());
          } finally {
            if (ref != null) Arrays.fill(ref, (byte) 0);
            if (code != null) Arrays.fill(code, '\0');
            Arrays.fill(externalAccountReference, '\0');
          }
        });
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public RouteView createDraftRoute(
      long connectionId, RoutePurpose purpose, String reason, long actor) {
    return ignoreTenant(
        () -> {
          require(
              connectionId > 0 && actor > 0 && purpose != null && bounded(reason, 256),
              "Invalid draft route command");
          SkitAdProviderConnectionDO connection =
              required(connectionMapper.selectByIdForUpdate(connectionId));
          require("CONFIGURING".equals(connection.getState()), "Connection is not configurable");
          Integer maximum = routeMapper.selectMaxRouteVersion(connectionId);
          if (maximum == null || maximum >= Integer.MAX_VALUE)
            throw new IllegalStateException("Provider route version is exhausted");
          int version = maximum + 1;
          SkitAdProviderCallbackRouteDO route =
              new SkitAdProviderCallbackRouteDO()
                  .setProviderConnectionId(connectionId)
                  .setRouteVersion(version)
                  .setPurpose(purpose.name())
                  .setCreatedByUserId(actor)
                  .setUpdatedByUserId(actor)
                  .setCreatedAt(now())
                  .setUpdatedAt(now());
          routeMapper.insertDraft(route);
          return view(route);
        });
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public IssuedRoute issueOnce(IssueRouteCommand command) {
    return ignoreTenant(() -> issue(command));
  }

  private IssuedRoute issue(IssueRouteCommand command) {
    require(
        command != null && command.routeId > 0 && command.actorUserId > 0, "Invalid issue command");
    SkitAdProviderCallbackRouteDO candidate = required(routeMapper.selectById(command.routeId));
    SkitAdProviderConnectionDO connection =
        required(connectionMapper.selectByIdForUpdate(candidate.getProviderConnectionId()));
    SkitAdProviderCallbackRouteDO route =
        required(routeMapper.selectByIdForUpdate(command.routeId));
    require(
        connection.getId().equals(route.getProviderConnectionId())
            && "CONFIGURING".equals(connection.getState())
            && "DRAFT".equals(route.getState()),
        "Route is not issuable");
    if (RoutePurpose.PRODUCTION.name().equals(route.getPurpose()))
      productionGate.assertProductionIssueAllowed(
          connection.getId(), route.getId(), command.actorUserId);
    char[] key = null, url = null, originChars = null;
    byte[] keyHash = null, origin = null, contract = null;
    boolean delivered = false;
    try {
      key = generateKey();
      keyHash = hash(key);
      url = urlService.providerImpressionCallbackUrl(key);
      LocalDateTime now = now();
      long registryId =
          registryService.registerProviderRoute(
              new SkitCallbackRouteRegistryService.ProviderCallbackRouteRegistration(
                  route.getId(), keyHash, now));
      originChars = urlService.getPublicBaseUrl().toCharArray();
      origin = hash(originChars);
      contract = urlService.providerImpressionContractFingerprint(keyHash);
      String fingerprint = hex(keyHash, 8);
      if (routeMapper.issueCas(
              route.getId(),
              registryId,
              fingerprint,
              urlService.getPublicBaseUrl(),
              urlService.providerImpressionPathVersion(),
              urlService.providerImpressionTemplateVersion(),
              origin,
              contract,
              command.actorUserId,
              now)
          != 1) throw new IllegalStateException("Provider route issue CAS failed");
      OneTimeIssuedRoute issued = new OneTimeIssuedRoute(route.getId(), fingerprint, url);
      registerForCommit(issued);
      delivered = true;
      return issued;
    } finally {
      if (key != null) Arrays.fill(key, '\0');
      if (keyHash != null) Arrays.fill(keyHash, (byte) 0);
      if (originChars != null) Arrays.fill(originChars, '\0');
      if (origin != null) Arrays.fill(origin, (byte) 0);
      if (contract != null) Arrays.fill(contract, (byte) 0);
      if (!delivered && url != null) Arrays.fill(url, '\0');
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public RouteView abandonNeverShared(AbandonRouteCommand command) {
    return ignoreTenant(
        () -> {
          require(
              command != null
                  && command.routeId > 0
                  && command.actorUserId > 0
                  && bounded(command.neverSharedDeclaration, 256),
              "Invalid abandon command");
          SkitAdProviderCallbackRouteDO candidate =
              required(routeMapper.selectById(command.routeId));
          SkitAdProviderConnectionDO connection =
              required(connectionMapper.selectByIdForUpdate(candidate.getProviderConnectionId()));
          SkitAdProviderCallbackRouteDO route =
              required(routeMapper.selectByIdForUpdate(command.routeId));
          require(
              connection.getId().equals(route.getProviderConnectionId())
                  && "CONFIGURING".equals(connection.getState())
                  && "ISSUED".equals(route.getState()),
              "Route cannot be abandoned");
          LocalDateTime at = now();
          if (routeMapper.abandonCas(route.getId(), route.getPurpose(), command.actorUserId, at)
              != 1) throw new IllegalStateException("Provider route abandon CAS failed");
          registryService.tombstoneProviderRoute(route.getId(), at);
          route.setState("ABANDONED");
          return view(route);
        });
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public RouteView markSubmitted(MarkSubmittedCommand command) {
    return ignoreTenant(
        () -> {
          require(
              command != null
                  && command.routeId > 0
                  && command.actorUserId > 0
                  && bounded(command.ticket, 128)
                  && bounded(command.reference, 128)
                  && bounded(command.recipient, 255),
              "Invalid submission command");
          SkitAdProviderCallbackRouteDO candidate =
              required(routeMapper.selectById(command.routeId));
          SkitAdProviderConnectionDO connection =
              required(connectionMapper.selectByIdForUpdate(candidate.getProviderConnectionId()));
          SkitAdProviderCallbackRouteDO route =
              required(routeMapper.selectByIdForUpdate(command.routeId));
          require(
              connection.getId().equals(route.getProviderConnectionId())
                  && "CONFIGURING".equals(connection.getState())
                  && "ISSUED".equals(route.getState())
                  && RoutePurpose.PRODUCTION.name().equals(route.getPurpose()),
              "Route cannot be submitted");
          productionGate.assertProductionIssueAllowed(
              connection.getId(), route.getId(), command.actorUserId);
          if (routeMapper.submitCas(
                  route.getId(),
                  command.ticket,
                  command.reference,
                  command.recipient,
                  command.actorUserId,
                  now())
              != 1) throw new IllegalStateException("Provider route submit CAS failed");
          route.setState("SUBMITTED");
          return view(route);
        });
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ConnectionView block(BlockConnectionCommand command) {
    return ignoreTenant(
        () -> {
          require(
              command != null
                  && command.providerConnectionId > 0
                  && command.actorUserId > 0
                  && bounded(command.reason, 256),
              "Invalid block command");
          SkitAdProviderConnectionDO connection =
              required(connectionMapper.selectByIdForUpdate(command.providerConnectionId));
          List<SkitAdProviderCallbackRouteDO> routes =
              routeMapper.selectAcceptingForUpdate(connection.getId());
          LocalDateTime now = now();
          if (routeMapper.blockAccepting(connection.getId(), command.actorUserId, now)
              != routes.size())
            throw new IllegalStateException("Provider route block count changed");
          for (SkitAdProviderCallbackRouteDO route : routes)
            registryService.tombstoneProviderRoute(route.getId(), now);
          if (connectionMapper.block(connection.getId(), command.actorUserId, now) != 1
              && !"BLOCKED".equals(connection.getState()))
            throw new IllegalStateException("Provider connection block CAS failed");
          return new ConnectionView(connection.getId(), "BLOCKED");
        });
  }

  @Override
  public ProviderRouteResolution resolveProviderImpression(
      char[] callbackKey, LocalDateTime receivedAt) {
    return ignoreTenant(
        () -> {
          require(
              callbackKey != null
                  && callbackKey.length == 43
                  && receivedAt != null
                  && hasAccountPrefix(callbackKey),
              "Invalid provider callback route");
          byte[] hash = hash(callbackKey);
          try {
            return resolveProviderImpression(registryService.lookup(hash, receivedAt), receivedAt);
          } catch (SkitCallbackRouteRegistryService.CallbackRouteRejectedException rejected) {
            return new ProviderRouteResolution(0, 0, false);
          } finally {
            Arrays.fill(hash, (byte) 0);
          }
        });
  }

  /** Resolves an already-proven global registry owner without another registry lookup. */
  @Override
  public ProviderRouteResolution resolveProviderImpression(
      SkitCallbackRouteRegistryService.RouteLookup resolvedRoute, LocalDateTime receivedAt) {
    return ignoreTenant(
        () -> {
          if (resolvedRoute == null
              || receivedAt == null
              || resolvedRoute.getRouteType()
                  != SkitCallbackRouteRegistryService.RouteType.PROVIDER_CALLBACK_ROUTE
              || resolvedRoute.getProviderCallbackRouteId() == null
              || resolvedRoute.getProviderCallbackRouteId() <= 0)
            return new ProviderRouteResolution(0, 0, false);
          SkitAdProviderCallbackRouteDO route =
              routeMapper.selectById(resolvedRoute.getProviderCallbackRouteId());
          if (route == null) return new ProviderRouteResolution(0, 0, false);
          SkitAdProviderConnectionDO connection =
              connectionMapper.selectById(route.getProviderConnectionId());
          boolean accepting =
              connection != null
                  && "CONFIGURING".equals(connection.getState())
                  && ("ISSUED".equals(route.getState()) || "SUBMITTED".equals(route.getState()))
                  && "PRIMARY_ACCEPTING".equals(route.getRouteSlot())
                  && (route.getAcceptUntil() == null
                      || !receivedAt.isAfter(route.getAcceptUntil()));
          return new ProviderRouteResolution(
              route.getProviderConnectionId(), route.getId(), accepting);
        });
  }

  private char[] generateKey() {
    byte[] entropy = new byte[29];
    try {
      random.nextBytes(entropy);
      return callbackKeyFromFirst228Bits(entropy);
    } finally {
      Arrays.fill(entropy, (byte) 0);
    }
  }

  /** Encodes exactly 38 consecutive six-bit groups from the first 228 random bits. */
  static char[] callbackKeyFromFirst228Bits(byte[] entropy) {
    if (entropy == null || entropy.length != 29)
      throw new IllegalArgumentException("Provider key entropy must be 29 bytes");
    char[] key = new char[43];
    key[0] = 'a';
    key[1] = 'c';
    key[2] = 'c';
    key[3] = 't';
    key[4] = '_';
    for (int group = 0; group < 38; group++) {
      int value = 0;
      for (int offset = 0; offset < 6; offset++) {
        int bit = group * 6 + offset;
        value = (value << 1) | ((entropy[bit >>> 3] >>> (7 - (bit & 7))) & 1);
      }
      key[5 + group] = BASE64_URL[value];
    }
    return key;
  }

  private static boolean hasAccountPrefix(char[] key) {
    return key[0] == 'a'
        && key[1] == 'c'
        && key[2] == 'c'
        && key[3] == 't'
        && key[4] == '_'
        && isBase64Url(key);
  }

  private static boolean isBase64Url(char[] key) {
    for (char symbol : key) {
      if (!((symbol >= 'A' && symbol <= 'Z')
          || (symbol >= 'a' && symbol <= 'z')
          || (symbol >= '0' && symbol <= '9')
          || symbol == '_'
          || symbol == '-')) return false;
    }
    return true;
  }

  private char[] randomCode() {
    char[] result = new char[32];
    byte[] source = new byte[24];
    try {
      random.nextBytes(source);
      int bit = 0;
      for (int i = 0; i < result.length; i++) {
        int value = 0;
        for (int j = 0; j < 6; j++) {
          value = (value << 1) | ((source[bit >>> 3] >>> (7 - (bit & 7))) & 1);
          bit++;
        }
        result[i] = BASE64_URL[value];
      }
      return result;
    } finally {
      Arrays.fill(source, (byte) 0);
    }
  }

  private static byte[] hash(char[] value) {
    byte[] bytes = new byte[value.length];
    for (int i = 0; i < value.length; i++) bytes[i] = (byte) value[i];
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  static byte[] externalAccountReferenceHash(char[] value) {
    ByteBuffer encoded = null;
    try {
      encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(encoded);
      return digest.digest();
    } catch (CharacterCodingException | NoSuchAlgorithmException e) {
      throw new IllegalArgumentException("External account reference is not valid UTF-8 input", e);
    } finally {
      if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
    }
  }

  private static String hex(byte[] value, int bytes) {
    char[] out = new char[bytes * 2];
    char[] digits = "0123456789abcdef".toCharArray();
    for (int i = 0; i < bytes; i++) {
      out[i * 2] = digits[(value[i] >>> 4) & 15];
      out[i * 2 + 1] = digits[value[i] & 15];
    }
    return new String(out);
  }

  private static boolean bounded(String value, int max) {
    return value != null && !value.trim().isEmpty() && value.length() <= max;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  private static <T> T required(T value) {
    if (value == null) throw new IllegalStateException("Provider route state is missing");
    return value;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  private static <T> T ignoreTenant(Supplier<T> work) {
    AtomicReference<T> result = new AtomicReference<>();
    TenantUtils.executeIgnore((Runnable) () -> result.set(work.get()));
    return result.get();
  }

  private static RouteView view(SkitAdProviderCallbackRouteDO route) {
    return new RouteView(route.getId(), route.getState(), route.getCallbackKeyFingerprint());
  }

  private static void registerForCommit(OneTimeIssuedRoute issued) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Provider route issue requires transaction synchronization");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              issued.destroy();
            }
          }
        });
  }

  private static final class OneTimeIssuedRoute implements IssuedRoute {
    private final long routeId;
    private final String fingerprint;
    private char[] url;

    private OneTimeIssuedRoute(long id, String fp, char[] url) {
      routeId = id;
      fingerprint = fp;
      this.url = url;
    }

    public synchronized char[] consumeCallbackUrl() {
      if (url == null) throw new IllegalStateException("Callback URL has already been consumed");
      char[] result = url;
      url = null;
      return result;
    }

    synchronized void destroy() {
      if (url != null) {
        Arrays.fill(url, '\0');
        url = null;
      }
    }

    public long getRouteId() {
      return routeId;
    }

    public String getFingerprint() {
      return fingerprint;
    }

    public String toString() {
      return "IssuedRoute{routeId="
          + routeId
          + ", status=ISSUED, fingerprint='"
          + fingerprint
          + "'}";
    }
  }
}
