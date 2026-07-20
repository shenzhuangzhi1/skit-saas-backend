package cn.iocoder.yudao.module.skit.dal.mysql;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdClientEventDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitContentEntitlementDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitEntitlementGrantDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitNativePlayerGrantDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdClientEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionPlanMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionRuleMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitContentEntitlementMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitEntitlementGrantMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberClosureMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitNativePlayerGrantMapper;
import cn.iocoder.yudao.module.system.dal.mysql.tenant.TenantMapper;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.annotation.JsonIgnore;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitTask5PersistenceContractTest {

    @Test
    void dataObjectsMapEveryTask5ColumnAndProtectStoredHashes() throws Exception {
        assertDataObject(SkitAdSessionDO.class, "skit_ad_session", fields(
                "id", Long.class,
                "sessionId", String.class,
                "sessionTokenHash", byte[].class,
                "sessionTokenKeyVersion", Integer.class,
                "protocolVersion", Integer.class,
                "memberId", Long.class,
                "adAccountId", Long.class,
                "policySnapshotId", Long.class,
                "callbackKeyVersion", Integer.class,
                "rewardSecretVersion", Integer.class,
                "provider", String.class,
                "placementId", String.class,
                "scenarioId", String.class,
                "businessType", String.class,
                "dramaId", Long.class,
                "episodeFrom", Integer.class,
                "episodeTo", Integer.class,
                "unlockScope", String.class,
                "activeScopeHash", byte[].class,
                "activeScopeReleasedAt", LocalDateTime.class,
                "activeScopeReleaseReason", String.class,
                "pseudonymousUserId", String.class,
                "accessMode", String.class,
                "nativePlayerGrantId", Long.class,
                "clientLifecycleStatus", String.class,
                "rewardVerificationStatus", String.class,
                "entitlementStatus", String.class,
                "revenueStatus", String.class,
                "loadExpiresAt", LocalDateTime.class,
                "rewardAcceptUntil", LocalDateTime.class,
                "rewardVerifiedAt", LocalDateTime.class,
                "entitledAt", LocalDateTime.class,
                "sdkRequestId", String.class,
                "providerShowId", String.class,
                "providerTransactionId", String.class,
                "networkFirmId", Integer.class,
                "adsourceId", String.class,
                "lastCallbackSequence", Integer.class,
                "lastClientEvent", String.class,
                "failureReason", String.class,
                "version", Integer.class));
        assertJsonIgnored(SkitAdSessionDO.class, "sessionTokenHash");
        assertJsonIgnored(SkitAdSessionDO.class, "activeScopeHash");

        assertDataObject(SkitAdClientEventDO.class, "skit_ad_client_event", fields(
                "id", Long.class,
                "adSessionId", Long.class,
                "protocolVersion", Integer.class,
                "clientEventId", String.class,
                "callbackSequence", Integer.class,
                "eventType", String.class,
                "nativeState", String.class,
                "sdkRequestId", String.class,
                "providerShowId", String.class,
                "networkFirmId", Integer.class,
                "adsourceId", String.class,
                "clientRewardObserved", Boolean.class,
                "closed", Boolean.class,
                "payloadHash", byte[].class,
                "occurredAt", LocalDateTime.class));
        assertJsonIgnored(SkitAdClientEventDO.class, "payloadHash");

        assertDataObject(SkitContentEntitlementDO.class, "skit_content_entitlement", fields(
                "id", Long.class,
                "memberId", Long.class,
                "dramaId", Long.class,
                "episodeNo", Integer.class,
                "status", String.class,
                "grantedAt", LocalDateTime.class,
                "version", Integer.class));

        assertDataObject(SkitEntitlementGrantDO.class, "skit_entitlement_grant", fields(
                "id", Long.class,
                "adSessionId", Long.class,
                "entitlementId", Long.class,
                "memberId", Long.class,
                "dramaId", Long.class,
                "episodeNo", Integer.class,
                "providerTransactionId", String.class,
                "grantResult", String.class,
                "grantedAt", LocalDateTime.class));

        assertDataObject(SkitNativePlayerGrantDO.class, "skit_native_player_grant", fields(
                "id", Long.class,
                "memberId", Long.class,
                "dramaId", Long.class,
                "grantTokenHash", byte[].class,
                "status", String.class,
                "expiresAt", LocalDateTime.class,
                "revokedAt", LocalDateTime.class,
                "version", Integer.class));
        assertJsonIgnored(SkitNativePlayerGrantDO.class, "grantTokenHash");
    }

    @Test
    void allTask5MappersAreNarrowAndTenantInsertsWorkWithoutTableInfo() throws Exception {
        List<Class<?>> mapperTypes = Arrays.asList(
                SkitAdSessionMapper.class,
                SkitAdClientEventMapper.class,
                SkitContentEntitlementMapper.class,
                SkitEntitlementGrantMapper.class,
                SkitNativePlayerGrantMapper.class);
        for (Class<?> mapperType : mapperTypes) {
            assertFalse(BaseMapper.class.isAssignableFrom(mapperType),
                    mapperType.getSimpleName() + " must not expose unrestricted CRUD");
        }

        assertTenantInsert(SkitAdSessionMapper.class.getMethod("insert", SkitAdSessionDO.class));
        assertTenantInsert(SkitAdClientEventMapper.class.getMethod("insertCanonical", SkitAdClientEventDO.class));
        assertTenantInsert(SkitContentEntitlementMapper.class
                .getMethod("insertGrantedIfAbsent", SkitContentEntitlementDO.class));
        assertTenantInsert(SkitEntitlementGrantMapper.class.getMethod("insert", SkitEntitlementGrantDO.class));
        assertTenantInsert(SkitNativePlayerGrantMapper.class.getMethod("insert", SkitNativePlayerGrantDO.class));
    }

    @Test
    void adAccountMapperLocksEveryEnabledTakuCandidateWithoutChoosingOneInSql() throws Exception {
        Method method = SkitAdAccountMapper.class.getMethod("selectEnabledTakuForUpdate", Long.class);
        assertEquals(List.class, method.getReturnType());
        ParameterizedType returnType = (ParameterizedType) method.getGenericReturnType();
        assertEquals(SkitAdAccountDO.class, returnType.getActualTypeArguments()[0]);

        String sql = selectSql(method);
        assertContainsAll(sql, "tenant_id=#{tenantid}", "provider='taku'", "status=0",
                "deleted=b'0'", "for update");
        assertFalse(sql.contains("order by"), "service must reject ambiguity rather than accept SQL ordering");
        assertFalse(sql.contains("limit"), "all enabled Taku candidates must be locked and inspected");
        assertFalse(sql.contains("app_key") || sql.contains("secret"),
                "session creation must not load encrypted provider credentials it never uses");
    }

    @Test
    void appendOnlyEventAndGrantExposeNoMutationOrDeletion() {
        assertAppendOnly(SkitAdClientEventMapper.class);
        assertAppendOnly(SkitEntitlementGrantMapper.class);
    }

    @Test
    void sessionMapperLocksCompoundScopeAndUsesStrictCasPredicates() throws Exception {
        String statusRead = selectSql(SkitAdSessionMapper.class.getMethod(
                "selectByTenantMemberAndSessionId", Long.class, Long.class, String.class));
        assertContainsAll(statusRead, "tenant_id=#{tenantid}", "member_id=#{memberid}",
                "session_id=#{sessionid}", "deleted=b'0'", "limit 1");
        assertFalse(statusRead.contains("for update"), "status polling must not take a row lock");

        String bySession = selectSql(SkitAdSessionMapper.class.getMethod(
                "selectByTenantMemberAndSessionIdForUpdate", Long.class, Long.class, String.class));
        assertContainsAll(bySession, "tenant_id=#{tenantid}", "member_id=#{memberid}",
                "session_id=#{sessionid}", "deleted=b'0'", "for update");
        assertFalse(bySession.contains("limit"),
                "tenant SQL rewriting must not move LIMIT after FOR UPDATE on MySQL");

        String byScope = selectSql(SkitAdSessionMapper.class.getMethod(
                "selectActiveScopeForUpdate", Long.class, Long.class, byte[].class));
        assertContainsAll(byScope, "tenant_id=#{tenantid}", "member_id=#{memberid}",
                "active_scope_hash=#{activescopehash}", "active_scope_released_at is null", "for update");
        assertFalse(byScope.contains("limit"),
                "tenant SQL rewriting must not move LIMIT after FOR UPDATE on MySQL");

        Method lifecycle = Arrays.stream(SkitAdSessionMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("updateClientLifecycleCas"))
                .findFirst().orElseThrow(AssertionError::new);
        String lifecycleSql = updateSql(lifecycle);
        assertContainsAll(lifecycleSql,
                "tenant_id=#{tenantid}", "id=#{id}", "member_id=#{memberid}",
                "version=#{expectedversion}", "client_lifecycle_status=#{expectedstatus}",
                "last_callback_sequence=#{expectedlastcallbacksequence}",
                "#{callbacksequence} > last_callback_sequence", "version=version+1");

        assertFalse(Arrays.stream(SkitAdSessionMapper.class.getDeclaredMethods())
                        .anyMatch(method -> method.getName().equals("releaseActiveScopeAfterServerTerminalCas")),
                "strict lifecycle checks forbid a separately committed terminal state before release");

        Method loadExpiry = SkitAdSessionMapper.class.getMethod("markLoadExpiredCas",
                Long.class, Long.class, Long.class, Integer.class, LocalDateTime.class);
        String loadExpirySql = updateSql(loadExpiry);
        assertContainsAll(loadExpirySql,
                "tenant_id=#{tenantid}", "id=#{id}", "member_id=#{memberid}",
                "version=#{expectedversion}", "client_lifecycle_status in ('created','loading')",
                "load_expires_at < #{authoritativenow}", "client_lifecycle_status='load_expired'",
                "reward_verification_status='pending'", "entitlement_status='none'",
                "revenue_status='none'", "provider_show_id is null",
                "provider_transaction_id is null", "reward_callback_inbox_id is null",
                "reward_callback_received_at is null", "network_firm_id is null",
                "adsource_id is null", "last_client_event='load_started'",
                "version=version+1");
        assertFalse(loadExpirySql.contains("active_scope_hash=null"),
                "load expiry must retain the scope until the reward acceptance window ends");

        Method orphanCreatedRelease = SkitAdSessionMapper.class.getMethod(
                "rejectPureCreatedAndReleaseScopeCas",
                Long.class, Long.class, Long.class, Integer.class, LocalDateTime.class);
        String orphanCreatedReleaseSql = updateSql(orphanCreatedRelease);
        assertContainsAll(orphanCreatedReleaseSql,
                "tenant_id=#{tenantid}", "id=#{id}", "member_id=#{memberid}",
                "version=#{expectedversion}", "client_lifecycle_status='created'",
                "reward_verification_status='pending'", "entitlement_status='none'",
                "revenue_status='none'", "sdk_request_id is null",
                "provider_show_id is null", "provider_transaction_id is null",
                "reward_callback_inbox_id is null", "reward_callback_received_at is null",
                "network_firm_id is null", "adsource_id is null",
                "last_callback_sequence=-1", "last_client_event is null",
                "reward_verification_status='rejected'", "active_scope_hash=null",
                "active_scope_released_at=#{rejectedat}", "version=version+1");

        Method legacyScopeRelease = SkitAdSessionMapper.class.getMethod(
                "rejectLegacyMultiEpisodeScopeCas",
                Long.class, Long.class, Long.class, Integer.class, LocalDateTime.class);
        String legacyScopeReleaseSql = updateSql(legacyScopeRelease);
        assertContainsAll(legacyScopeReleaseSql,
                "tenant_id=#{tenantid}", "id=#{id}", "member_id=#{memberid}",
                "version=#{expectedversion}", "episode_from<>episode_to",
                "reward_verification_status='pending'", "entitlement_status='none'",
                "reward_verification_status='rejected'", "active_scope_hash=null",
                "active_scope_released_at=#{rejectedat}", "version=version+1");

        Method retryRelease = SkitAdSessionMapper.class.getMethod(
                "rejectUnstartedLoadExpiredAndReleaseScopeCas",
                Long.class, Long.class, Long.class, Integer.class, LocalDateTime.class);
        String retryReleaseSql = updateSql(retryRelease);
        assertContainsAll(retryReleaseSql,
                "tenant_id=#{tenantid}", "id=#{id}", "member_id=#{memberid}",
                "version=#{expectedversion}", "client_lifecycle_status='load_expired'",
                "reward_verification_status='pending'", "entitlement_status='none'",
                "revenue_status='none'", "provider_show_id is null",
                "provider_transaction_id is null", "reward_callback_inbox_id is null",
                "reward_callback_received_at is null", "network_firm_id is null",
                "adsource_id is null", "last_callback_sequence=-1",
                "last_client_event is null", "active_scope_hash is not null",
                "active_scope_released_at is null", "active_scope_release_reason is null",
                "reward_verification_status='rejected'", "active_scope_hash=null",
                "active_scope_released_at=#{rejectedat}",
                "active_scope_release_reason='reward_rejected'", "version=version+1",
                "last_client_event='load_started'", "sdk_request_id is not null");

        Method failedRelease = SkitAdSessionMapper.class.getMethod(
                "rejectPreShowFailedAndReleaseScopeCas",
                Long.class, Long.class, Long.class, Integer.class, LocalDateTime.class);
        String failedReleaseSql = updateSql(failedRelease);
        assertContainsAll(failedReleaseSql,
                "tenant_id=#{tenantid}", "id=#{id}", "member_id=#{memberid}",
                "version=#{expectedversion}", "client_lifecycle_status='failed'",
                "reward_verification_status='pending'", "entitlement_status='none'",
                "revenue_status='none'", "provider_show_id is null",
                "provider_transaction_id is null", "reward_callback_inbox_id is null",
                "reward_callback_received_at is null", "network_firm_id is null",
                "adsource_id is null", "last_callback_sequence>=0",
                "last_client_event='failed'", "sdk_request_id is not null",
                "active_scope_hash is not null", "active_scope_released_at is null",
                "active_scope_release_reason is null", "reward_verification_status='rejected'",
                "active_scope_hash=null", "active_scope_released_at=#{rejectedat}",
                "active_scope_release_reason='reward_rejected'", "version=version+1");

        Method nativeGrantTakeover = Arrays.stream(SkitAdSessionMapper.class.getDeclaredMethods())
                .filter(method -> method.getName()
                        .equals("rejectSupersededNativeGrantLoadingAndReleaseScopeCas"))
                .findFirst().orElse(null);
        assertNotNull(nativeGrantTakeover,
                "native-player restart recovery requires one strict session takeover CAS");
        String nativeGrantTakeoverSql = updateSql(nativeGrantTakeover);
        assertContainsAll(nativeGrantTakeoverSql,
                "tenant_id=#{tenantid}", "id=#{id}", "member_id=#{memberid}",
                "version=#{expectedversion}", "access_mode='native_player_grant'",
                "native_player_grant_id=#{expectednativeplayergrantid}",
                "native_player_grant_id<>#{currentnativeplayergrantid}",
                "client_lifecycle_status='loading'", "last_callback_sequence>=0",
                "last_client_event='load_started'", "sdk_request_id is not null",
                "reward_verification_status='pending'", "entitlement_status='none'",
                "revenue_status='none'", "provider_show_id is null",
                "provider_transaction_id is null", "reward_callback_inbox_id is null",
                "reward_callback_received_at is null", "network_firm_id is null",
                "adsource_id is null", "active_scope_hash is not null",
                "active_scope_released_at is null", "active_scope_release_reason is null",
                "reward_verification_status='rejected'", "active_scope_hash=null",
                "active_scope_released_at=#{rejectedat}",
                "active_scope_release_reason='reward_rejected'", "version=version+1");

        Method timeout = SkitAdSessionMapper.class.getMethod(
                "markRewardVerifyTimeoutAndReleaseScopeCas",
                Long.class, Long.class, Long.class, Integer.class, LocalDateTime.class);
        String timeoutSql = updateSql(timeout);
        assertContainsAll(timeoutSql,
                "tenant_id=#{tenantid}", "id=#{id}", "member_id=#{memberid}",
                "version=#{expectedversion}", "reward_verification_status='pending'",
                "reward_accept_until < #{authoritativenow}",
                "reward_verification_status='verify_timeout'", "active_scope_hash=null",
                "active_scope_released_at=#{authoritativenow}",
                "active_scope_release_reason='verify_timeout'", "version=version+1",
                "active_scope_hash is not null", "active_scope_released_at is null",
                "active_scope_release_reason is null");
        assertFalse(timeoutSql.contains("client_lifecycle_status"));
        assertFalse(Arrays.stream(SkitAdSessionMapper.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("markRewardVerifyTimeoutCas")),
                "timeout status and active-scope release must not be exposed as two mapper calls");
    }

    @Test
    void clientEventMapperSupportsCanonicalIdempotencyReads() throws Exception {
        String byId = selectSql(SkitAdClientEventMapper.class.getMethod(
                "selectByClientEventId", Long.class, Long.class, String.class));
        assertContainsAll(byId, "tenant_id=#{tenantid}", "ad_session_id=#{adsessionid}",
                "client_event_id=#{clienteventid}", "deleted=b'0'");

        String bySequence = selectSql(SkitAdClientEventMapper.class.getMethod(
                "selectBySequence", Long.class, Long.class, Integer.class));
        assertContainsAll(bySequence, "tenant_id=#{tenantid}", "ad_session_id=#{adsessionid}",
                "callback_sequence=#{callbacksequence}", "deleted=b'0'");
    }

    @Test
    void entitlementMapperLocksEpisodeSetAndSafelyReturnsExistingId() throws Exception {
        String granted = selectSql(SkitContentEntitlementMapper.class.getMethod(
                "selectGrantedEpisodes", Long.class, Long.class, Long.class, LocalDateTime.class));
        assertContainsAll(granted, "tenant_id=#{tenantid}", "member_id=#{memberid}",
                "drama_id=#{dramaid}", "deleted=b'0'", "status='granted'",
                "granted_at > #{activeafter}",
                "order by episode_no asc");
        assertFalse(granted.contains("for update"), "GET entitlement reads must not take row locks");

        Method lock = SkitContentEntitlementMapper.class.getMethod(
                "selectEpisodesForUpdate", Long.class, Long.class, Long.class, List.class);
        String lockSql = selectSql(lock);
        assertContainsAll(lockSql, "tenant_id=#{tenantid}", "member_id=#{memberid}",
                "drama_id=#{dramaid}", "episode_no in", "for update");
        assertFalse(lockSql.contains("order by"),
                "tenant SQL rewriting must not move ORDER BY after FOR UPDATE on MySQL");
        assertTrue(lockSql.contains("otherwise") || lockSql.contains("1=0"),
                "empty episode collections must fail closed instead of producing IN ()");

        Method insert = SkitContentEntitlementMapper.class
                .getMethod("insertGrantedIfAbsent", SkitContentEntitlementDO.class);
        String insertSql = insertSql(insert);
        assertContainsAll(insertSql, "on duplicate key update", "id=last_insert_id(id)");
    }

    @Test
    void rewardProvenanceReadUsesOnlyOneExactSignedGrantChain() throws Exception {
        Method evidence = SkitEntitlementGrantMapper.class.getMethod(
                "selectVerifiedRewardProvenance",
                Long.class, Long.class, Long.class, Integer.class, LocalDateTime.class);
        String sql = selectSql(evidence);
        assertContainsAll(sql,
                "from skit_entitlement_grant g",
                "inner join skit_content_entitlement e",
                "inner join skit_ad_session s",
                "inner join skit_ad_callback_inbox i",
                "g.tenant_id=#{tenantid}", "g.member_id=#{memberid}",
                "g.drama_id=#{dramaid}", "g.episode_no=#{episodeno}",
                "g.grant_result='created'", "e.status='granted'",
                "e.granted_at > #{activeafter}", "g.granted_at=e.granted_at",
                "s.reward_verification_status='signed_verified'",
                "s.entitlement_status='granted'",
                "s.active_scope_release_reason='entitlement_granted'",
                "i.callback_type='reward'", "i.evidence_provenance='signed_ilrd'",
                "i.authentication_level='signed_reward'", "i.signature_status='valid'",
                "i.delivery_integrity_status='canonical'", "i.processing_status='succeeded'",
                "i.provider_show_id is not null",
                "hex(i.provider_show_id)=hex(s.provider_show_id)",
                "hex(i.provider_transaction_id)=hex(s.provider_transaction_id)",
                "hex(s.provider_transaction_id)=hex(g.provider_transaction_id)",
                "limit 2");
        assertFalse(sql.contains("select *"),
                "provenance reads must not expose arbitrary callback-inbox fields");
        assertFalse(sql.contains("order by"),
                "ambiguous reward grants must fail closed instead of selecting a latest row");
    }

    @Test
    void rewardProvenanceReadIsParsableByTheRuntimeSqlInterceptor() throws Exception {
        Method evidence = SkitEntitlementGrantMapper.class.getMethod(
                "selectVerifiedRewardProvenance",
                Long.class, Long.class, Long.class, Integer.class, LocalDateTime.class);
        String rawSql = String.join(" ", evidence.getAnnotation(Select.class).value());

        assertDoesNotThrow(() -> CCJSqlParserUtil.parse(rawSql.replaceAll("#\\{[^}]+}", "?")));
    }

    @Test
    void nativeGrantMapperResolvesHashThenLocksExactCompoundIdentityAndSerializesUse() throws Exception {
        String globalLookup = selectSql(SkitNativePlayerGrantMapper.class
                .getMethod("selectByTokenHash", byte[].class));
        assertContainsAll(globalLookup, "grant_token_hash=#{granttokenhash}", "deleted=b'0'", "limit 1");
        assertFalse(globalLookup.contains("tenant_id=#{tenantid}"),
                "the opaque token hash is the only global tenant-resolution input");

        String exactLock = selectSql(SkitNativePlayerGrantMapper.class.getMethod(
                "selectExactForUpdate", Long.class, Long.class, Long.class, Long.class));
        assertContainsAll(exactLock, "tenant_id=#{tenantid}", "id=#{id}",
                "member_id=#{memberid}", "drama_id=#{dramaid}", "for update");
        assertFalse(exactLock.contains("limit"),
                "tenant SQL rewriting must not move LIMIT after FOR UPDATE on MySQL");

        Method use = Arrays.stream(SkitNativePlayerGrantMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("recordActiveUseCas"))
                .findFirst().orElseThrow(AssertionError::new);
        String useSql = updateSql(use);
        assertContainsAll(useSql, "tenant_id=#{tenantid}", "id=#{id}",
                "member_id=#{memberid}", "drama_id=#{dramaid}",
                "version=#{expectedversion}", "status='active'", "revoked_at is null",
                "expires_at > #{usedat}", "expires_at=#{renewedexpiresat}",
                "version=version+1");

        Method revoke = Arrays.stream(SkitNativePlayerGrantMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("revokeActiveCas"))
                .findFirst().orElseThrow(AssertionError::new);
        String revokeSql = updateSql(revoke);
        assertContainsAll(revokeSql, "tenant_id=#{tenantid}", "id=#{id}",
                "member_id=#{memberid}", "drama_id=#{dramaid}",
                "version=#{expectedversion}", "status='active'", "status='revoked'",
                "revoked_at=#{revokedat}", "version=version+1");
    }

    @Test
    void sessionEligibilityAndSnapshotValidationUseCompatibleSharedLocks() throws Exception {
        Method account = requireMethod(SkitAdAccountMapper.class,
                "selectEnabledTakuForShare", Long.class);
        assertSharedRead(account, "tenant_id=#{tenantid}", "provider='taku'", "status=0");

        Method tenant = requireMethod(TenantMapper.class, "selectByIdForShare", Long.class);
        assertSharedRead(tenant, "id=#{id}", "deleted=b'0'");

        Method plan = requireMethod(SkitCommissionPlanMapper.class,
                "selectActiveForShare", Long.class);
        assertSharedRead(plan, "tenant_id=#{tenantid}", "status=0", "deleted=b'0'");

        Method rules = requireMethod(SkitCommissionRuleMapper.class,
                "selectListByPlanIdForShare", Long.class, Long.class);
        assertSharedRead(rules, "tenant_id=#{tenantid}", "plan_id=#{planid}", "deleted=b'0'");

        Method closure = requireMethod(SkitMemberClosureMapper.class,
                "selectAncestorsForShare", Long.class, Long.class);
        assertSharedRead(closure, "tenant_id=#{tenantid}",
                "descendant_id=#{descendantid}", "deleted=b'0'");

        Method ancestors = requireMethod(SkitMemberMapper.class,
                "selectByTenantAndIdsForShare", Long.class, List.class);
        assertSharedRead(ancestors, "tenant_id=#{tenantid}", "id in", "deleted=b'0'");

        Method member = requireMethod(SkitMemberMapper.class,
                "selectByTenantAndIdForShare", Long.class, Long.class);
        assertSharedRead(member, "tenant_id=#{tenantid}", "id=#{id}", "deleted=b'0'");

    }

    private static void assertDataObject(Class<?> type, String tableName,
                                         Map<String, Class<?>> expectedFields) throws Exception {
        assertTrue(TenantBaseDO.class.isAssignableFrom(type));
        TableName table = type.getAnnotation(TableName.class);
        assertNotNull(table);
        assertEquals(tableName, table.value());
        assertTrue(type.getDeclaredField("id").isAnnotationPresent(TableId.class));
        for (Map.Entry<String, Class<?>> expected : expectedFields.entrySet()) {
            assertEquals(expected.getValue(), type.getDeclaredField(expected.getKey()).getType(),
                    type.getSimpleName() + "." + expected.getKey());
        }
    }

    private static void assertJsonIgnored(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        assertTrue(field.isAnnotationPresent(JsonIgnore.class), type.getSimpleName() + "." + fieldName);
    }

    private static void assertTenantInsert(Method method) {
        String sql = rawInsertSql(method);
        assertTrue(sql.contains("(tenant_id,"),
                method + " must carry an explicit unquoted tenant_id for absent TableInfo");
        assertTrue(sql.contains("(#{tenantId},"), method + " must bind the row tenant id");
        assertFalse(sql.contains("(`tenant_id`,"),
                "quoted tenant_id is not recognized by TenantLine ignoreInsert in every parser path");
    }

    private static void assertAppendOnly(Class<?> mapperType) {
        assertFalse(Arrays.stream(mapperType.getDeclaredMethods())
                .anyMatch(method -> method.getName().startsWith("update")
                        || method.getName().startsWith("delete")
                        || method.isAnnotationPresent(Update.class)
                        || method.isAnnotationPresent(Delete.class)),
                mapperType.getSimpleName() + " must expose append-only persistence");
    }

    private static Method requireMethod(Class<?> mapperType, String name, Class<?>... parameterTypes) {
        try {
            return mapperType.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException exception) {
            assertTrue(false, mapperType.getSimpleName() + "." + name
                    + " must expose a dedicated shared-lock read");
            throw new AssertionError(exception);
        }
    }

    private static void assertSharedRead(Method method, String... predicates) {
        String sql = selectSql(method);
        assertContainsAll(sql, predicates);
        assertTrue(sql.contains("for share"), method + " must use a compatible shared lock");
        assertFalse(sql.contains("for update"), method + " must not take an exclusive lock");
        assertFalse(sql.contains("limit"), method + " must avoid parser-sensitive LIMIT lock syntax");
        assertFalse(sql.contains("order by"), method + " must sort in service code after locking");
    }

    private static String rawInsertSql(Method method) {
        Insert insert = method.getAnnotation(Insert.class);
        assertNotNull(insert, method.toString());
        return String.join(" ", insert.value());
    }

    private static String insertSql(Method method) {
        return normalize(rawInsertSql(method));
    }

    private static String selectSql(Method method) {
        Select select = method.getAnnotation(Select.class);
        assertNotNull(select, method.toString());
        return normalize(String.join(" ", select.value()));
    }

    private static String updateSql(Method method) {
        Update update = method.getAnnotation(Update.class);
        assertNotNull(update, method.toString());
        return normalize(String.join(" ", update.value()));
    }

    private static String normalize(String sql) {
        return sql.replace("`", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static void assertContainsAll(String sql, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(sql.contains(fragment), () -> "Missing [" + fragment + "] in SQL: " + sql);
        }
    }

    private static Map<String, Class<?>> fields(Object... entries) {
        Map<String, Class<?>> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], (Class<?>) entries[i + 1]);
        }
        return result;
    }

}
