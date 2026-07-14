package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdClientEventDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdClientEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService;
import cn.iocoder.yudao.module.skit.service.member.SkitContentEntitlementService;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_SESSION_ACCOUNT_UNAVAILABLE;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_SESSION_EVENT_CONFLICT;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_SESSION_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_SESSION_NOT_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_SESSION_STATE_CONFLICT;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_PLAYER_GRANT_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_DISABLED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_NOT_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_STATUS_INVALID;

@Service
public class SkitAdSessionServiceImpl implements SkitAdSessionService {

    private static final int PROTOCOL_VERSION = 1;
    private static final int SESSION_ID_BYTES = 16;
    private static final String PROVIDER = "TAKU";
    private static final String SCENE = "drama_unlock";
    private static final String BUSINESS_TYPE = "EPISODE_UNLOCK";
    private static final int CREATE_TRANSACTION_ATTEMPTS = 3;

    private final SkitAdSessionMapper sessionMapper;
    private final SkitAdClientEventMapper clientEventMapper;
    private final SkitAdAccountMapper accountMapper;
    private final SkitAgentMapper agentMapper;
    private final SkitMemberMapper memberMapper;
    private final SkitAdCredentialVersionService credentialService;
    private final SkitPolicySnapshotService snapshotService;
    private final SkitContentEntitlementService entitlementService;
    private final TenantService tenantService;
    private final SkitAdSessionTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Function<Supplier<CreateResult>, CreateResult> createTransaction;
    private final SkitAdSessionStateMachine stateMachine = new SkitAdSessionStateMachine();

    @Autowired
    public SkitAdSessionServiceImpl(SkitAdSessionMapper sessionMapper,
                                    SkitAdClientEventMapper clientEventMapper,
                                    SkitAdAccountMapper accountMapper,
                                    SkitAgentMapper agentMapper,
                                    SkitMemberMapper memberMapper,
                                    SkitAdCredentialVersionService credentialService,
                                    SkitPolicySnapshotService snapshotService,
                                    SkitContentEntitlementService entitlementService,
                                    TenantService tenantService,
                                    SkitAdSessionTokenService tokenService,
                                    ObjectMapper objectMapper,
                                    SkitAdSessionCreateTransactionExecutor createTransactionExecutor) {
        this(sessionMapper, clientEventMapper, accountMapper, agentMapper, memberMapper, credentialService,
                snapshotService, entitlementService, tenantService, tokenService, objectMapper,
                Clock.systemDefaultZone(), new SecureRandom(), createTransactionExecutor::execute);
    }

    SkitAdSessionServiceImpl(SkitAdSessionMapper sessionMapper,
                             SkitAdClientEventMapper clientEventMapper,
                             SkitAdAccountMapper accountMapper,
                             SkitAgentMapper agentMapper,
                             SkitMemberMapper memberMapper,
                             SkitAdCredentialVersionService credentialService,
                             SkitPolicySnapshotService snapshotService,
                             SkitContentEntitlementService entitlementService,
                             TenantService tenantService,
                             SkitAdSessionTokenService tokenService,
                             ObjectMapper objectMapper,
                             Clock clock,
                             SecureRandom secureRandom) {
        this(sessionMapper, clientEventMapper, accountMapper, agentMapper, memberMapper, credentialService,
                snapshotService, entitlementService, tenantService, tokenService, objectMapper,
                clock, secureRandom, Supplier::get);
    }

    private SkitAdSessionServiceImpl(SkitAdSessionMapper sessionMapper,
                                     SkitAdClientEventMapper clientEventMapper,
                                     SkitAdAccountMapper accountMapper,
                                     SkitAgentMapper agentMapper,
                                     SkitMemberMapper memberMapper,
                                     SkitAdCredentialVersionService credentialService,
                                     SkitPolicySnapshotService snapshotService,
                                     SkitContentEntitlementService entitlementService,
                                     TenantService tenantService,
                                     SkitAdSessionTokenService tokenService,
                                     ObjectMapper objectMapper,
                                     Clock clock,
                                     SecureRandom secureRandom,
                                     Function<Supplier<CreateResult>, CreateResult> createTransaction) {
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
        this.clientEventMapper = Objects.requireNonNull(clientEventMapper, "clientEventMapper");
        this.accountMapper = Objects.requireNonNull(accountMapper, "accountMapper");
        this.agentMapper = Objects.requireNonNull(agentMapper, "agentMapper");
        this.memberMapper = Objects.requireNonNull(memberMapper, "memberMapper");
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
        this.entitlementService = Objects.requireNonNull(entitlementService, "entitlementService");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.createTransaction = Objects.requireNonNull(createTransaction, "createTransaction");
    }

    @Override
    public CreateResult createForMember(Long memberId, CreateCommand command) {
        requirePositive(memberId, "memberId");
        return createWithRetry(() -> createInsideTenant(memberId, command, "MEMBER_OAUTH", null));
    }

    @Override
    public CreateResult createForNativeGrant(String grantToken, CreateCommand command) {
        validateCreateCommand(command);
        SkitContentEntitlementService.PlayerGrantReference reference =
                entitlementService.resolvePlayerGrant(grantToken);
        AtomicReference<CreateResult> result = new AtomicReference<>();
        TenantUtils.execute(reference.getTenantId(), () -> result.set(createWithRetry(
                () -> createInsideTenant(reference.getMemberId(), command,
                        "NATIVE_PLAYER_GRANT", reference))));
        return result.get();
    }

    private CreateResult createWithRetry(Supplier<CreateResult> operation) {
        RuntimeException lastConflict = null;
        for (int attempt = 0; attempt < CREATE_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                return createTransaction.apply(operation);
            } catch (DuplicateKeyException | PessimisticLockingFailureException conflict) {
                lastConflict = conflict;
            }
        }
        throw Objects.requireNonNull(lastConflict, "lastConflict");
    }

    private CreateResult createInsideTenant(Long memberId, CreateCommand command, String accessMode,
                                            SkitContentEntitlementService.PlayerGrantReference grantReference) {
        validateCreateCommand(command);
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        requireEnabledTenant(tenantId, tenantService.getTenantForShare(tenantId));
        tenantService.validTenant(tenantId);
        requireEnabledAgent(tenantId, agentMapper.selectByTenantId(tenantId));
        SkitAdAccountDO account = requireEnabledTakuAccount(tenantId,
                accountMapper.selectEnabledTakuForShare(tenantId));
        requireEnabledMember(tenantId, memberId,
                memberMapper.selectByTenantAndIdForUpdate(tenantId, memberId));

        Long nativeGrantId = null;
        if (grantReference != null) {
            SkitContentEntitlementService.PlayerGrantScope scope =
                    entitlementService.lockAndUsePlayerGrant(grantReference, command.getDramaId());
            if (!tenantId.equals(scope.getTenantId()) || !memberId.equals(scope.getMemberId())
                    || !command.getDramaId().equals(scope.getDramaId()) || scope.getGrantId() == null) {
                throw exception(AD_SESSION_INVALID);
            }
            nativeGrantId = scope.getGrantId();
        }

        String unlockScope = unlockScope(command.getDramaId(), command.getEpisodeNo());
        byte[] activeScopeHash = activeScopeHash(tenantId, memberId, unlockScope);
        SkitAdSessionDO existing = sessionMapper.selectActiveScopeForUpdate(
                tenantId, memberId, activeScopeHash);
        CreateResult existingResult = resolveExisting(existing, tenantId, memberId, account,
                command, unlockScope);
        if (existingResult != null) {
            return existingResult;
        }
        if (entitlementService.ownsEpisodeForUpdate(memberId, command.getDramaId(), command.getEpisodeNo())) {
            return alreadyEntitled();
        }

        SkitAdCredentialVersionService.CredentialMetadata callbackKey =
                credentialService.getActiveCallbackKeyVersion(tenantId, account.getId());
        SkitAdCredentialVersionService.CredentialMetadata rewardSecret =
                credentialService.getActiveRewardSecretVersion(tenantId, account.getId());
        validateCredential(callbackKey, tenantId, account.getId());
        validateCredential(rewardSecret, tenantId, account.getId());
        SkitPolicySnapshotService.PolicySnapshot snapshot = snapshotService.createSnapshot(memberId);
        if (snapshot == null || snapshot.getId() == null || snapshot.getId() <= 0
                || !tenantId.equals(snapshot.getTenantId()) || !memberId.equals(snapshot.getSourceMemberId())) {
            throw new IllegalStateException("Commission policy snapshot escaped the session scope");
        }
        LocalDateTime now = now();
        String sessionId = newSessionId();
        SkitAdSessionTokenService.IssuedToken issuedToken = tokenService.issue(sessionId);
        String placementId = readPlacementId(account.getConfigData());
        SkitAdSessionDO row = new SkitAdSessionDO()
                .setSessionId(sessionId).setSessionTokenHash(issuedToken.getTokenHash())
                .setSessionTokenKeyVersion(issuedToken.getKeyVersion()).setProtocolVersion(PROTOCOL_VERSION)
                .setMemberId(memberId).setAdAccountId(account.getId()).setPolicySnapshotId(snapshot.getId())
                .setCallbackKeyVersion(callbackKey.getVersion()).setRewardSecretVersion(rewardSecret.getVersion())
                .setProvider(PROVIDER).setPlacementId(placementId).setScenarioId(SCENE)
                .setBusinessType(BUSINESS_TYPE).setDramaId(command.getDramaId())
                .setEpisodeFrom(command.getEpisodeNo()).setEpisodeTo(command.getEpisodeNo())
                .setUnlockScope(unlockScope).setActiveScopeHash(activeScopeHash)
                .setPseudonymousUserId(tokenService.pseudonymousUserId(tenantId, memberId))
                .setAccessMode(accessMode).setNativePlayerGrantId(nativeGrantId)
                .setClientLifecycleStatus("CREATED").setRewardVerificationStatus("PENDING")
                .setEntitlementStatus("NONE").setRevenueStatus("NONE")
                .setLoadExpiresAt(now.plusMinutes(5)).setRewardAcceptUntil(now.plusMinutes(20))
                .setLastCallbackSequence(-1).setVersion(0);
        row.setTenantId(tenantId);
        if (sessionMapper.insert(row) != 1 || row.getId() == null || row.getId() <= 0) {
            throw new IllegalStateException("Ad session was not inserted exactly once");
        }
        return new CreateResult("CREATED", PROTOCOL_VERSION, sessionId, PROVIDER, placementId,
                row.getPseudonymousUserId(), issuedToken.consumeCustomData(), SCENE,
                row.getLoadExpiresAt(), row.getRewardAcceptUntil());
    }

    private CreateResult resolveExisting(SkitAdSessionDO row, Long tenantId, Long memberId,
                                         SkitAdAccountDO account, CreateCommand command,
                                         String unlockScope) {
        if (row == null) {
            return null;
        }
        if (!tenantId.equals(row.getTenantId()) || !memberId.equals(row.getMemberId())
                || !account.getId().equals(row.getAdAccountId()) || !PROVIDER.equals(row.getProvider())
                || !command.getDramaId().equals(row.getDramaId())
                || !command.getEpisodeNo().equals(row.getEpisodeFrom())
                || !command.getEpisodeNo().equals(row.getEpisodeTo()) || !unlockScope.equals(row.getUnlockScope())
                || row.getSessionId() == null || row.getSessionTokenKeyVersion() == null
                || row.getRewardAcceptUntil() == null || row.getVersion() == null) {
            throw new IllegalStateException("Active ad scope escaped its immutable session envelope");
        }
        LocalDateTime now = now();
        if ("PENDING".equals(row.getRewardVerificationStatus()) && now.isAfter(row.getRewardAcceptUntil())) {
            if (sessionMapper.markRewardVerifyTimeoutAndReleaseScopeCas(tenantId, row.getId(), memberId,
                    row.getVersion(), now) != 1) {
                throw exception(AD_SESSION_STATE_CONFLICT);
            }
            return null;
        }
        if ("GRANTED".equals(row.getEntitlementStatus())) {
            return alreadyEntitled();
        }
        if ("REJECTED".equals(row.getRewardVerificationStatus())
                || "VERIFY_TIMEOUT".equals(row.getRewardVerificationStatus())) {
            throw new IllegalStateException("Terminal ad session retained an active scope");
        }
        SkitAdSessionTokenService.IssuedToken token = tokenService.restore(
                row.getSessionId(), row.getSessionTokenKeyVersion());
        if (!MessageDigest.isEqual(token.getTokenHash(), row.getSessionTokenHash())) {
            throw new IllegalStateException("Stored ad session token hash does not match its key version");
        }
        return new CreateResult("REUSED", row.getProtocolVersion(), row.getSessionId(), row.getProvider(),
                row.getPlacementId(), row.getPseudonymousUserId(), token.consumeCustomData(), row.getScenarioId(),
                row.getLoadExpiresAt(), row.getRewardAcceptUntil());
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public SessionView getForMember(Long memberId, String sessionId) {
        requirePositive(memberId, "memberId");
        return getInsideTenant(memberId, sessionId, null);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public SessionView getForNativeGrant(String grantToken, String sessionId) {
        SkitContentEntitlementService.PlayerGrantReference reference =
                entitlementService.resolvePlayerGrant(grantToken);
        AtomicReference<SessionView> result = new AtomicReference<>();
        TenantUtils.execute(reference.getTenantId(), () -> {
            SkitContentEntitlementService.PlayerGrantScope scope =
                    entitlementService.lockAndUsePlayerGrant(reference, reference.getDramaId());
            result.set(getInsideTenant(scope.getMemberId(), sessionId, scope));
        });
        return result.get();
    }

    private SessionView getInsideTenant(Long memberId, String sessionId,
                                        SkitContentEntitlementService.PlayerGrantScope nativeScope) {
        validateSessionId(sessionId);
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitAdSessionDO row = sessionMapper.selectByTenantMemberAndSessionId(
                tenantId, memberId, sessionId);
        requireSession(row, tenantId, memberId, sessionId);
        requireNativeSessionScope(row, tenantId, nativeScope);
        LocalDateTime authoritativeNow = now();
        if (!needsServerExpiry(row, authoritativeNow)) {
            return toView(row);
        }
        row = sessionMapper.selectByTenantMemberAndSessionIdForUpdate(tenantId, memberId, sessionId);
        requireSession(row, tenantId, memberId, sessionId);
        requireNativeSessionScope(row, tenantId, nativeScope);
        expireLoad(row, tenantId, memberId, authoritativeNow);
        expirePendingReward(row, tenantId, memberId, authoritativeNow);
        return toView(row);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public SessionView recordClientEvents(Long memberId, String sessionId, List<ClientEventCommand> events) {
        requirePositive(memberId, "memberId");
        return recordClientEventsInsideTenant(memberId, sessionId, events, null);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public SessionView recordClientEventsForNativeGrant(String grantToken, String sessionId,
                                                         List<ClientEventCommand> events) {
        SkitContentEntitlementService.PlayerGrantReference reference =
                entitlementService.resolvePlayerGrant(grantToken);
        AtomicReference<SessionView> result = new AtomicReference<>();
        TenantUtils.execute(reference.getTenantId(), () -> {
            SkitContentEntitlementService.PlayerGrantScope scope =
                    entitlementService.lockAndUsePlayerGrant(reference, reference.getDramaId());
            result.set(recordClientEventsInsideTenant(scope.getMemberId(), sessionId, events, scope));
        });
        return result.get();
    }

    private SessionView recordClientEventsInsideTenant(
            Long memberId, String sessionId, List<ClientEventCommand> events,
            SkitContentEntitlementService.PlayerGrantScope nativeScope) {
        validateSessionId(sessionId);
        if (events == null || events.isEmpty() || events.size() > 20) {
            throw exception(AD_SESSION_INVALID);
        }
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitAdSessionDO row = sessionMapper.selectByTenantMemberAndSessionIdForUpdate(
                tenantId, memberId, sessionId);
        requireSession(row, tenantId, memberId, sessionId);
        requireNativeSessionScope(row, tenantId, nativeScope);
        LocalDateTime authoritativeNow = now();
        expireLoad(row, tenantId, memberId, authoritativeNow);
        expirePendingReward(row, tenantId, memberId, authoritativeNow);
        if ("LOAD_EXPIRED".equals(row.getClientLifecycleStatus())) {
            return toView(row);
        }
        for (ClientEventCommand event : events) {
            applyClientEvent(row, event, tenantId, memberId, sessionId);
        }
        return toView(row);
    }

    private void applyClientEvent(SkitAdSessionDO session, ClientEventCommand event,
                                  Long tenantId, Long memberId, String sessionId) {
        validateClientEvent(session, event, sessionId);
        byte[] payloadHash = clientEventHash(event);
        SkitAdClientEventDO byId = clientEventMapper.selectByClientEventId(
                tenantId, session.getId(), event.getClientEventId());
        if (byId != null) {
            if (byId.getPayloadHash() != null && MessageDigest.isEqual(payloadHash, byId.getPayloadHash())) {
                return;
            }
            throw exception(AD_SESSION_EVENT_CONFLICT);
        }
        SkitAdClientEventDO bySequence = clientEventMapper.selectBySequence(
                tenantId, session.getId(), event.getCallbackSequence());
        if (bySequence != null) {
            throw exception(AD_SESSION_EVENT_CONFLICT);
        }
        SkitAdSessionStateMachine.ClientLifecycle current = enumValue(
                SkitAdSessionStateMachine.ClientLifecycle.class, session.getClientLifecycleStatus());
        SkitAdSessionStateMachine.ClientEvent clientEvent = enumValue(
                SkitAdSessionStateMachine.ClientEvent.class, event.getEventType());
        SkitAdSessionStateMachine.ClientLifecycle next;
        try {
            next = stateMachine.applyClientEvent(current, clientEvent);
        } catch (IllegalStateException ex) {
            throw exception(AD_SESSION_STATE_CONFLICT);
        }
        SkitAdClientEventDO evidence = new SkitAdClientEventDO()
                .setAdSessionId(session.getId()).setProtocolVersion(event.getProtocolVersion())
                .setClientEventId(event.getClientEventId()).setCallbackSequence(event.getCallbackSequence())
                .setEventType(event.getEventType()).setNativeState(event.getNativeState())
                .setSdkRequestId(event.getSdkRequestId()).setProviderShowId(event.getProviderShowId())
                .setNetworkFirmId(event.getNetworkFirmId()).setAdsourceId(event.getAdsourceId())
                .setClientRewardObserved(Boolean.TRUE.equals(event.getClientRewardObserved()))
                .setClosed(Boolean.TRUE.equals(event.getClosed())).setPayloadHash(payloadHash).setOccurredAt(now());
        evidence.setTenantId(tenantId);
        if (clientEventMapper.insertCanonical(evidence) != 1) {
            throw new IllegalStateException("Client event evidence was not appended exactly once");
        }
        int updated = sessionMapper.updateClientLifecycleCas(tenantId, session.getId(), memberId,
                session.getVersion(), session.getClientLifecycleStatus(), session.getLastCallbackSequence(),
                event.getCallbackSequence(), next.name(), event.getEventType(), event.getSdkRequestId(),
                event.getProviderShowId(), event.getNetworkFirmId(), event.getAdsourceId());
        if (updated != 1) {
            throw exception(AD_SESSION_STATE_CONFLICT);
        }
        session.setClientLifecycleStatus(next.name()).setLastCallbackSequence(event.getCallbackSequence())
                .setLastClientEvent(event.getEventType()).setVersion(session.getVersion() + 1);
        if (session.getSdkRequestId() == null) {
            session.setSdkRequestId(event.getSdkRequestId());
        }
        if (session.getProviderShowId() == null) {
            session.setProviderShowId(event.getProviderShowId());
        }
        if (session.getNetworkFirmId() == null) {
            session.setNetworkFirmId(event.getNetworkFirmId());
        }
        if (session.getAdsourceId() == null) {
            session.setAdsourceId(event.getAdsourceId());
        }
    }

    private void validateClientEvent(SkitAdSessionDO session, ClientEventCommand event, String sessionId) {
        if (event == null || !Integer.valueOf(PROTOCOL_VERSION).equals(event.getProtocolVersion())
                || !sessionId.equals(event.getSessionId()) || !PROVIDER.equals(event.getProvider())
                || !session.getPlacementId().equals(event.getPlacementId())
                || !validPrintableAscii(event.getClientEventId(), 128)
                || event.getCallbackSequence() == null || event.getCallbackSequence() < 0
                || !validPrintableAscii(event.getSdkRequestId(), 128)
                || !validPrintableAscii(event.getEventType(), 32)
                || !validPrintableAscii(event.getNativeState(), 32)
                || !nativeStateMatches(event.getEventType(), event.getNativeState())
                || event.getClientRewardObserved() == null || event.getClosed() == null
                || (event.getNetworkFirmId() != null && event.getNetworkFirmId() <= 0)
                || (event.getAdsourceId() != null && !validPrintableAscii(event.getAdsourceId(), 128))
                || (session.getSdkRequestId() != null
                && !session.getSdkRequestId().equals(event.getSdkRequestId()))
                || (session.getProviderShowId() != null
                && !session.getProviderShowId().equals(event.getProviderShowId()))
                || (session.getNetworkFirmId() != null && event.getNetworkFirmId() != null
                && !session.getNetworkFirmId().equals(event.getNetworkFirmId()))
                || (session.getAdsourceId() != null && event.getAdsourceId() != null
                && !session.getAdsourceId().equals(event.getAdsourceId()))) {
            throw exception(AD_SESSION_INVALID);
        }
        boolean showIdRequired = "SHOWN".equals(event.getEventType())
                || "REWARD_OBSERVED".equals(event.getEventType())
                || "CLOSED".equals(event.getEventType());
        if ((showIdRequired && !validPrintableAscii(event.getProviderShowId(), 128))
                || (!showIdRequired && event.getProviderShowId() != null)
                || (!showIdRequired && (event.getNetworkFirmId() != null || event.getAdsourceId() != null))
                || !rewardObservationMatches(session.getClientLifecycleStatus(), event)
                || ("CLOSED".equals(event.getEventType())
                != Boolean.TRUE.equals(event.getClosed()))) {
            throw exception(AD_SESSION_INVALID);
        }
    }

    private boolean rewardObservationMatches(String currentStatus, ClientEventCommand event) {
        boolean observed = Boolean.TRUE.equals(event.getClientRewardObserved());
        if ("REWARD_OBSERVED".equals(event.getEventType())) {
            return observed;
        }
        if ("CLOSED".equals(event.getEventType())) {
            if ("CLIENT_REWARDED".equals(currentStatus)) {
                return observed;
            }
            if ("SHOWN".equals(currentStatus)) {
                return !observed;
            }
            return "CLOSED".equals(currentStatus) || !observed;
        }
        return !observed;
    }

    private boolean nativeStateMatches(String eventType, String nativeState) {
        if ("LOAD_STARTED".equals(eventType)) return "LOADING".equals(nativeState);
        if ("SHOWN".equals(eventType) || "REWARD_OBSERVED".equals(eventType)) {
            return "SHOWING".equals(nativeState);
        }
        if ("CLOSED".equals(eventType)) return "CLOSED".equals(nativeState);
        if ("FAILED".equals(eventType)) return "ERROR".equals(nativeState);
        return false;
    }

    private byte[] clientEventHash(ClientEventCommand event) {
        String canonical = PROTOCOL_VERSION + "\n" + text(event.getClientEventId()) + "\n"
                + event.getCallbackSequence() + "\n" + text(event.getSessionId()) + "\n"
                + text(event.getProvider()) + "\n" + text(event.getPlacementId()) + "\n"
                + text(event.getEventType()) + "\n" + text(event.getNativeState()) + "\n"
                + text(event.getSdkRequestId()) + "\n" + text(event.getProviderShowId()) + "\n"
                + text(event.getNetworkFirmId()) + "\n" + text(event.getAdsourceId()) + "\n"
                + Boolean.TRUE.equals(event.getClientRewardObserved()) + "\n"
                + Boolean.TRUE.equals(event.getClosed());
        return sha256(canonical);
    }

    private void expireLoad(SkitAdSessionDO row, Long tenantId, Long memberId,
                            LocalDateTime authoritativeNow) {
        if ("CREATED".equals(row.getClientLifecycleStatus()) && row.getLoadExpiresAt() != null
                && authoritativeNow.isAfter(row.getLoadExpiresAt())) {
            if (sessionMapper.markLoadExpiredCas(tenantId, row.getId(), memberId,
                    row.getVersion(), authoritativeNow) != 1) {
                throw exception(AD_SESSION_STATE_CONFLICT);
            }
            row.setClientLifecycleStatus("LOAD_EXPIRED").setFailureReason("LOAD_WINDOW_EXPIRED")
                    .setVersion(row.getVersion() + 1);
        }
    }

    private void expirePendingReward(SkitAdSessionDO row, Long tenantId, Long memberId,
                                     LocalDateTime authoritativeNow) {
        if ("PENDING".equals(row.getRewardVerificationStatus()) && row.getRewardAcceptUntil() != null
                && authoritativeNow.isAfter(row.getRewardAcceptUntil()) && row.getActiveScopeHash() != null) {
            if (sessionMapper.markRewardVerifyTimeoutAndReleaseScopeCas(
                    tenantId, row.getId(), memberId, row.getVersion(), authoritativeNow) != 1) {
                throw exception(AD_SESSION_STATE_CONFLICT);
            }
            row.setRewardVerificationStatus("VERIFY_TIMEOUT").setActiveScopeHash(null)
                    .setActiveScopeReleasedAt(authoritativeNow).setActiveScopeReleaseReason("VERIFY_TIMEOUT")
                    .setVersion(row.getVersion() + 1);
        }
    }

    private boolean needsServerExpiry(SkitAdSessionDO row, LocalDateTime authoritativeNow) {
        return ("CREATED".equals(row.getClientLifecycleStatus()) && row.getLoadExpiresAt() != null
                && authoritativeNow.isAfter(row.getLoadExpiresAt()))
                || ("PENDING".equals(row.getRewardVerificationStatus())
                && row.getRewardAcceptUntil() != null && row.getActiveScopeHash() != null
                && authoritativeNow.isAfter(row.getRewardAcceptUntil()));
    }

    private void requireSession(SkitAdSessionDO row, Long tenantId, Long memberId, String sessionId) {
        if (row == null) {
            throw exception(AD_SESSION_NOT_EXISTS);
        }
        if (!tenantId.equals(row.getTenantId()) || !memberId.equals(row.getMemberId())
                || !sessionId.equals(row.getSessionId())) {
            throw new IllegalStateException("Ad session escaped the tenant/member identity boundary");
        }
    }

    private void requireNativeSessionScope(
            SkitAdSessionDO row, Long tenantId,
            SkitContentEntitlementService.PlayerGrantScope nativeScope) {
        if (nativeScope == null) {
            return;
        }
        boolean validSessionProvenance =
                ("MEMBER_OAUTH".equals(row.getAccessMode()) && row.getNativePlayerGrantId() == null)
                        || ("NATIVE_PLAYER_GRANT".equals(row.getAccessMode())
                        && row.getNativePlayerGrantId() != null);
        if (nativeScope.getGrantId() == null || nativeScope.getGrantId() <= 0
                || !tenantId.equals(nativeScope.getTenantId())
                || !row.getMemberId().equals(nativeScope.getMemberId())
                || !row.getDramaId().equals(nativeScope.getDramaId())
                || !validSessionProvenance) {
            throw exception(AD_PLAYER_GRANT_INVALID);
        }
    }

    private SkitAdAccountDO requireEnabledTakuAccount(Long tenantId, List<SkitAdAccountDO> rows) {
        if (rows == null || rows.size() != 1) {
            throw exception(AD_SESSION_ACCOUNT_UNAVAILABLE);
        }
        SkitAdAccountDO row = rows.get(0);
        if (row == null || !tenantId.equals(row.getTenantId()) || row.getId() == null || row.getId() <= 0
                || !PROVIDER.equals(row.getProvider())
                || !CommonStatusEnum.ENABLE.getStatus().equals(row.getStatus())
                || !validPrintableAscii(row.getAppId(), 128)
                || readPlacementId(row.getConfigData()).isEmpty()) {
            throw exception(AD_SESSION_ACCOUNT_UNAVAILABLE);
        }
        return row;
    }

    private void requireEnabledAgent(Long tenantId, SkitAgentDO agent) {
        if (agent == null || !tenantId.equals(agent.getTenantId()) || agent.getArchivedTime() != null
                || !CommonStatusEnum.ENABLE.getStatus().equals(agent.getStatus())) {
            throw exception(AD_SESSION_INVALID);
        }
    }

    private void requireEnabledTenant(Long tenantId, TenantDO tenant) {
        if (tenant == null || !tenantId.equals(tenant.getId())
                || !CommonStatusEnum.ENABLE.getStatus().equals(tenant.getStatus())) {
            throw exception(AD_SESSION_INVALID);
        }
    }

    private void requireEnabledMember(Long tenantId, Long memberId, SkitMemberDO member) {
        if (member == null || !tenantId.equals(member.getTenantId()) || !memberId.equals(member.getId())) {
            throw exception(MEMBER_NOT_EXISTS);
        }
        if (CommonStatusEnum.DISABLE.getStatus().equals(member.getStatus())) {
            throw exception(MEMBER_DISABLED);
        }
        if (!CommonStatusEnum.ENABLE.getStatus().equals(member.getStatus())) {
            throw exception(MEMBER_STATUS_INVALID);
        }
    }

    private void validateCredential(SkitAdCredentialVersionService.CredentialMetadata metadata,
                                    Long tenantId, Long accountId) {
        if (metadata == null || metadata.getTenantId() != tenantId || metadata.getAdAccountId() != accountId
                || metadata.getVersion() <= 0 || !metadata.isActive()) {
            throw exception(AD_SESSION_ACCOUNT_UNAVAILABLE);
        }
    }

    private String readPlacementId(String configData) {
        try {
            JsonNode node = objectMapper.readTree(configData == null ? "" : configData);
            String placementId = node == null ? "" : node.path("placementId").asText("").trim();
            return placementId.length() <= 128 ? placementId : "";
        } catch (Exception ex) {
            return "";
        }
    }

    private void validateCreateCommand(CreateCommand command) {
        if (command == null) {
            throw exception(AD_SESSION_INVALID);
        }
        requirePositive(command.getDramaId(), "dramaId");
        requirePositive(command.getEpisodeNo(), "episodeNo");
    }

    private String unlockScope(Long dramaId, Integer episodeNo) {
        return "drama:" + dramaId + ":episode:" + episodeNo;
    }

    private byte[] activeScopeHash(Long tenantId, Long memberId, String unlockScope) {
        return sha256("skit-ad-active-scope-v1\0" + tenantId + "\0" + memberId + "\0" + unlockScope);
    }

    private String newSessionId() {
        byte[] random = new byte[SESSION_ID_BYTES];
        secureRandom.nextBytes(random);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Arrays.fill(random, (byte) 0);
        return sessionId;
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private CreateResult alreadyEntitled() {
        return new CreateResult("ALREADY_ENTITLED", PROTOCOL_VERSION, null, null,
                null, null, null, SCENE, null, null);
    }

    private SessionView toView(SkitAdSessionDO row) {
        return new SessionView(row.getSessionId(), row.getClientLifecycleStatus(),
                row.getRewardVerificationStatus(), row.getEntitlementStatus(), row.getRevenueStatus(),
                row.getProviderShowId(), row.getLoadExpiresAt(), row.getRewardAcceptUntil());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{22}")) {
            throw exception(AD_SESSION_INVALID);
        }
    }

    private boolean validText(String value, int maximum) {
        return value != null && !value.isEmpty() && value.length() <= maximum;
    }

    private boolean validPrintableAscii(String value, int maximum) {
        if (!validText(value, maximum)) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void requirePositive(Number value, String field) {
        if (value == null || value.longValue() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException ex) {
            throw exception(AD_SESSION_INVALID);
        }
    }

}
