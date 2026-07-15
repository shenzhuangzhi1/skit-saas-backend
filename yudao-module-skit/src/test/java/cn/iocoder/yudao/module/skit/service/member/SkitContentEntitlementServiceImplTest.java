package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitPlayerGrantRespVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitContentEntitlementDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitNativePlayerGrantDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitContentEntitlementMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitNativePlayerGrantMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitContentEntitlementServiceImplTest {

    private static final long TENANT_ID = 41L;
    private static final long MEMBER_ID = 51L;
    private static final long DRAMA_ID = 61L;
    private static final Instant NOW = Instant.parse("2026-07-14T05:00:00Z");
    private static final SkitTenantAdCapabilityService.ClientRuntime RUNTIME =
            new SkitTenantAdCapabilityService.ClientRuntime("2.4.0", 1);

    @Mock private SkitNativePlayerGrantMapper nativeGrantMapper;
    @Mock private SkitContentEntitlementMapper entitlementMapper;
    @Mock private SkitContentScopeService contentScopeService;
    @Mock private SkitMemberMapper memberMapper;
    @Mock private SkitAgentMapper agentMapper;
    @Mock private TenantService tenantService;
    @Mock private SkitTenantAdCapabilityService capabilityService;

    private SkitContentEntitlementServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
        org.mockito.Mockito.lenient().when(tenantService.getTenantForShare(anyLong()))
                .thenAnswer(invocation -> enabledTenant(invocation.getArgument(0)));
        org.mockito.Mockito.lenient().when(contentScopeService.requireAccessibleDrama(DRAMA_ID))
                .thenReturn(accessibleDrama(TENANT_ID, DRAMA_ID));
        service = new SkitContentEntitlementServiceImpl(nativeGrantMapper, entitlementMapper,
                contentScopeService,
                memberMapper, agentMapper, tenantService,
                Clock.fixed(NOW, ZoneOffset.UTC), new FixedSecureRandom(sequence(32)), capabilityService);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void playerGrantIsLoginTenantBoundShortLivedAndPersistsOnlyHash() throws Exception {
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent());
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, MEMBER_ID)).thenReturn(enabledMember());
        when(nativeGrantMapper.insert(any(SkitNativePlayerGrantDO.class))).thenAnswer(invocation -> {
            invocation.<SkitNativePlayerGrantDO>getArgument(0).setId(71L);
            return 1;
        });

        SkitContentEntitlementService.PlayerGrantIssue issue =
                service.issuePlayerGrant(MEMBER_ID, DRAMA_ID, RUNTIME);
        String token = issue.consumeGrantToken();

        org.mockito.ArgumentCaptor<SkitNativePlayerGrantDO> row =
                org.mockito.ArgumentCaptor.forClass(SkitNativePlayerGrantDO.class);
        verify(nativeGrantMapper).insert(row.capture());
        assertEquals(TENANT_ID, row.getValue().getTenantId());
        assertEquals(MEMBER_ID, row.getValue().getMemberId());
        assertEquals(DRAMA_ID, row.getValue().getDramaId());
        assertEquals("ACTIVE", row.getValue().getStatus());
        assertEquals(LocalDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC), row.getValue().getExpiresAt());
        assertArrayEquals(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)), row.getValue().getGrantTokenHash());
        assertFalse(issue.toString().contains(token));
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(issue).contains(token));
        assertThrows(IllegalStateException.class, issue::consumeGrantToken);
    }

    @Test
    void playerGrantRolloutGateRunsBeforeMemberOrTokenWork() {
        SkitTenantAdCapabilityService.ClientRuntime runtime =
                new SkitTenantAdCapabilityService.ClientRuntime("2.4.0", 1);
        org.mockito.Mockito.doThrow(cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                        cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_NOT_READY,
                        "CLIENT_VERSION_REVOKED"))
                .when(capabilityService).checkClientAccess(MEMBER_ID, runtime,
                        SkitTenantAdCapabilityService.AccessOperation.PLAYER_GRANT);

        assertThrows(RuntimeException.class,
                () -> service.issuePlayerGrant(MEMBER_ID, DRAMA_ID, runtime));

        verify(capabilityService).checkClientAccess(MEMBER_ID, runtime,
                SkitTenantAdCapabilityService.AccessOperation.PLAYER_GRANT);
        org.mockito.Mockito.verifyNoInteractions(memberMapper, nativeGrantMapper, entitlementMapper);
    }

    @Test
    void playerGrantCannotBeIssuedForContentOutsideTheTenantCatalog() {
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent());
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, MEMBER_ID)).thenReturn(enabledMember());
        org.mockito.Mockito.doThrow(
                        cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                                cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_SESSION_INVALID))
                .when(contentScopeService).requireAccessibleDrama(DRAMA_ID);

        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.issuePlayerGrant(MEMBER_ID, DRAMA_ID, RUNTIME));

        verify(nativeGrantMapper, never()).insert(any());
    }

    @Test
    void protectedContentGateRunsBeforeEntitlementLookup() {
        SkitTenantAdCapabilityService.ClientRuntime runtime =
                new SkitTenantAdCapabilityService.ClientRuntime("2.4.0", 1);
        org.mockito.Mockito.doThrow(cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                        cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_NOT_READY,
                        "CLIENT_VERSION_REVOKED"))
                .when(capabilityService).checkClientAccess(MEMBER_ID, runtime,
                        SkitTenantAdCapabilityService.AccessOperation.PROTECTED_CONTENT);

        assertThrows(RuntimeException.class,
                () -> service.listGrantedEpisodes(MEMBER_ID, DRAMA_ID, runtime));

        verify(capabilityService).checkClientAccess(MEMBER_ID, runtime,
                SkitTenantAdCapabilityService.AccessOperation.PROTECTED_CONTENT);
        org.mockito.Mockito.verifyNoInteractions(entitlementMapper);
    }

    @Test
    void publicRuntimeContractHasNoUngatedGrantOrProtectedContentOverloads() {
        assertThrows(NoSuchMethodException.class, () -> SkitContentEntitlementService.class.getMethod(
                "issuePlayerGrant", Long.class, Long.class));
        assertThrows(NoSuchMethodException.class, () -> SkitContentEntitlementService.class.getMethod(
                "listGrantedEpisodes", Long.class, Long.class));
        assertThrows(NoSuchMethodException.class, () -> SkitContentEntitlementService.class.getMethod(
                "listGrantedEpisodesForPlayerGrant", String.class));
    }

    @Test
    void missingCapabilityGateIsRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new SkitContentEntitlementServiceImpl(
                nativeGrantMapper, entitlementMapper, contentScopeService,
                memberMapper, agentMapper, tenantService,
                Clock.fixed(NOW, ZoneOffset.UTC), new FixedSecureRandom(sequence(32)), null));
    }

    @Test
    void shanghaiClockKeepsPlayerGrantExpiryAsTrueEpochInstantInJson() throws Exception {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        TimeZone previousTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(shanghai));
        try {
            when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent());
            when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, MEMBER_ID)).thenReturn(enabledMember());
            when(nativeGrantMapper.insert(any(SkitNativePlayerGrantDO.class))).thenAnswer(invocation -> {
                invocation.<SkitNativePlayerGrantDO>getArgument(0).setId(71L);
                return 1;
            });
            SkitContentEntitlementServiceImpl shanghaiService =
                    new SkitContentEntitlementServiceImpl(nativeGrantMapper, entitlementMapper,
                            contentScopeService, memberMapper, agentMapper, tenantService,
                            Clock.fixed(NOW, shanghai),
                            new FixedSecureRandom(sequence(32)), capabilityService);

            SkitContentEntitlementService.PlayerGrantIssue issue =
                    shanghaiService.issuePlayerGrant(MEMBER_ID, DRAMA_ID, RUNTIME);

            org.mockito.ArgumentCaptor<SkitNativePlayerGrantDO> row =
                    org.mockito.ArgumentCaptor.forClass(SkitNativePlayerGrantDO.class);
            verify(nativeGrantMapper).insert(row.capture());
            assertEquals(LocalDateTime.ofInstant(NOW.plusSeconds(300), shanghai),
                    row.getValue().getExpiresAt());
            com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper().readTree(
                    JsonUtils.toJsonString(SkitPlayerGrantRespVO.from(issue)));
            assertEquals(NOW.plusSeconds(300).toEpochMilli(), json.path("expiresAt").asLong());
        } finally {
            TimeZone.setDefault(previousTimeZone);
        }
    }

    @Test
    void opaqueGrantResolvesGloballyThenRequiresEnabledTenantMemberAndExactDramaLock() {
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sequence(32));
        SkitNativePlayerGrantDO discovered = activeGrant(token).setVersion(4);
        when(nativeGrantMapper.selectByTokenHash(any(byte[].class))).thenAnswer(invocation -> {
            assertTrue(TenantContextHolder.isIgnore());
            return discovered;
        });

        SkitContentEntitlementService.PlayerGrantReference reference = service.resolvePlayerGrant(token);
        assertFalse(TenantContextHolder.isIgnore());
        assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent());
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, MEMBER_ID)).thenReturn(enabledMember());
        when(nativeGrantMapper.selectExactForShare(TENANT_ID, 71L, MEMBER_ID, DRAMA_ID))
                .thenReturn(discovered);

        SkitContentEntitlementService.PlayerGrantScope scope =
                service.lockAndUsePlayerGrant(reference, DRAMA_ID);

        assertEquals(TENANT_ID, scope.getTenantId());
        assertEquals(MEMBER_ID, scope.getMemberId());
        assertEquals(DRAMA_ID, scope.getDramaId());
        assertEquals(71L, scope.getGrantId());
    }

    @Test
    void expiredOrCrossTenantGrantFailsClosedWithoutRecordingUse() {
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sequence(32));
        SkitNativePlayerGrantDO discovered = activeGrant(token).setVersion(2)
                .setExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(nativeGrantMapper.selectByTokenHash(any(byte[].class))).thenReturn(discovered);
        SkitContentEntitlementService.PlayerGrantReference reference = service.resolvePlayerGrant(token);
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent());
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, MEMBER_ID)).thenReturn(enabledMember());
        when(nativeGrantMapper.selectExactForShare(TENANT_ID, 71L, MEMBER_ID, DRAMA_ID))
                .thenReturn(discovered);
        assertThrows(RuntimeException.class, () -> service.lockAndUsePlayerGrant(reference, DRAMA_ID));
        verify(nativeGrantMapper, never()).recordActiveUseCas(any(), any(), any(), any(), any(), any());

        TenantContextHolder.setTenantId(999L);
        assertThrows(RuntimeException.class, () -> service.lockAndUsePlayerGrant(reference, DRAMA_ID));
    }

    @Test
    void disabledMemberCannotUseStillUnexpiredNativeGrant() {
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sequence(32));
        SkitNativePlayerGrantDO discovered = activeGrant(token).setVersion(2);
        when(nativeGrantMapper.selectByTokenHash(any(byte[].class))).thenReturn(discovered);
        SkitContentEntitlementService.PlayerGrantReference reference = service.resolvePlayerGrant(token);
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent());
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, MEMBER_ID))
                .thenReturn(enabledMember().setStatus(CommonStatusEnum.DISABLE.getStatus()));

        assertThrows(RuntimeException.class, () -> service.lockAndUsePlayerGrant(reference, DRAMA_ID));

        verify(nativeGrantMapper, never()).selectExactForShare(any(), any(), any(), any());
    }

    @Test
    void entitlementReadsAreAlwaysBoundToTenantMemberDramaEpisode() {
        SkitContentEntitlementDO existing = new SkitContentEntitlementDO()
                .setId(81L).setMemberId(MEMBER_ID).setDramaId(DRAMA_ID).setEpisodeNo(3)
                .setStatus("GRANTED");
        existing.setTenantId(TENANT_ID);
        when(entitlementMapper.selectGrantedEpisodes(TENANT_ID, MEMBER_ID, DRAMA_ID))
                .thenReturn(Collections.singletonList(existing));
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(4))).thenReturn(Collections.emptyList());

        assertEquals(Collections.singletonList(3),
                service.listGrantedEpisodes(MEMBER_ID, DRAMA_ID, RUNTIME));
        assertFalse(service.ownsEpisodeForUpdate(MEMBER_ID, DRAMA_ID, 4));
    }

    @Test
    void nativeEntitlementReadDerivesEveryScopeFieldFromGrant() {
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sequence(32));
        SkitNativePlayerGrantDO discovered = activeGrant(token).setVersion(2);
        SkitContentEntitlementDO episode = new SkitContentEntitlementDO()
                .setId(81L).setMemberId(MEMBER_ID).setDramaId(DRAMA_ID).setEpisodeNo(3)
                .setStatus("GRANTED");
        episode.setTenantId(TENANT_ID);
        when(nativeGrantMapper.selectByTokenHash(any(byte[].class))).thenReturn(discovered);
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent());
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, MEMBER_ID)).thenReturn(enabledMember());
        when(nativeGrantMapper.selectExactForShare(TENANT_ID, 71L, MEMBER_ID, DRAMA_ID))
                .thenReturn(discovered);
        when(entitlementMapper.selectGrantedEpisodes(TENANT_ID, MEMBER_ID, DRAMA_ID))
                .thenReturn(Collections.singletonList(episode));

        assertEquals(Collections.singletonList(3),
                service.listGrantedEpisodesForPlayerGrant(token, RUNTIME));
        assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
    }

    private SkitAgentDO enabledAgent() {
        return SkitAgentDO.builder().id(1L).tenantId(TENANT_ID)
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
    }

    private SkitContentScopeService.AccessibleDrama accessibleDrama(long tenantId, long dramaId) {
        return new SkitContentScopeService.AccessibleDrama(
                tenantId, 701L, dramaId, 20, 8, 5);
    }

    private TenantDO enabledTenant(long tenantId) {
        return new TenantDO().setId(tenantId).setStatus(CommonStatusEnum.ENABLE.getStatus());
    }

    private SkitMemberDO enabledMember() {
        SkitMemberDO member = SkitMemberDO.builder().id(MEMBER_ID)
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
        member.setTenantId(TENANT_ID);
        return member;
    }

    private SkitNativePlayerGrantDO activeGrant(String token) {
        SkitNativePlayerGrantDO row = new SkitNativePlayerGrantDO()
                .setId(71L).setMemberId(MEMBER_ID).setDramaId(DRAMA_ID)
                .setGrantTokenHash(hash(token)).setStatus("ACTIVE")
                .setExpiresAt(LocalDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
        row.setTenantId(TENANT_ID);
        return row;
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] sequence(int size) {
        byte[] result = new byte[size];
        for (int index = 0; index < size; index++) {
            result[index] = (byte) index;
        }
        return result;
    }

    private static final class FixedSecureRandom extends SecureRandom {
        private final byte[] bytes;

        private FixedSecureRandom(byte[] bytes) {
            this.bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public void nextBytes(byte[] target) {
            System.arraycopy(bytes, 0, target, 0, target.length);
        }
    }

}
