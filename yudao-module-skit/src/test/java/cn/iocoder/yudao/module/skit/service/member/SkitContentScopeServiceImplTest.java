package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitContentEntitlementDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitContentEntitlementMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.record.SkitAdminRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;

import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_SESSION_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitContentScopeServiceImplTest {

    private static final long TENANT_ID = 41L;
    private static final long MEMBER_ID = 51L;
    private static final long DRAMA_ID = 61L;
    private static final Instant NOW = Instant.parse("2026-07-20T02:00:00Z");

    @Mock private SkitAdminRecordMapper recordMapper;
    @Mock private SkitContentEntitlementMapper entitlementMapper;

    private SkitContentScopeServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
        service = new SkitContentScopeServiceImpl(recordMapper, entitlementMapper,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void runtimeConstructorIsExplicitlyAutowiredWhenTestClockAddsAnOverload()
            throws NoSuchMethodException {
        assertNotNull(SkitContentScopeServiceImpl.class.getConstructor(
                SkitAdminRecordMapper.class, SkitContentEntitlementMapper.class, ObjectMapper.class)
                .getAnnotation(Autowired.class));
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void currentTenantOnlineCatalogComputesExactRequestedEpisodeScope() {
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID))).thenReturn(Collections.singletonList(
                catalog(TENANT_ID, false, 0, "上架", 20, 2, 3)));
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(3))).thenReturn(Collections.emptyList());

        SkitContentScopeService.UnlockScope scope =
                service.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3);

        assertEquals(TENANT_ID, scope.getTenantId());
        assertEquals(DRAMA_ID, scope.getDramaId());
        assertEquals(3, scope.getEpisodeFrom());
        assertEquals(3, scope.getEpisodeTo());
        assertEquals("drama:61:episode:3", scope.getCanonicalScope());
        assertFalse(scope.isAlreadyEntitled());
        verify(recordMapper).selectDramaCatalogByBusinessIdForShare(
                TENANT_ID, Long.toString(DRAMA_ID));
    }

    @Test
    void catalogUnlockSizeNeverExpandsTheRequestedEpisodeScope() {
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID))).thenReturn(Collections.singletonList(
                catalog(TENANT_ID, false, 0, "连载中", 20, 2, 100)));
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(3))).thenReturn(Collections.emptyList());

        SkitContentScopeService.UnlockScope scope =
                service.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3);

        assertEquals(3, scope.getEpisodeFrom());
        assertEquals(3, scope.getEpisodeTo());
        assertEquals("drama:61:episode:3", scope.getCanonicalScope());
        assertFalse(scope.isAlreadyEntitled());
    }

    @Test
    void requestedOwnedEpisodeReturnsNoAdOutcome() {
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID))).thenReturn(Collections.singletonList(
                catalog(TENANT_ID, false, 0, "正常", 20, 0, 3)));
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(3))).thenReturn(Collections.singletonList(
                entitlement(TENANT_ID, MEMBER_ID, DRAMA_ID, 3, "GRANTED")));

        SkitContentScopeService.UnlockScope scope =
                service.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3);

        assertTrue(scope.isAlreadyEntitled());
        assertEquals(3, scope.getEpisodeFrom());
        assertEquals(3, scope.getEpisodeTo());
        verify(entitlementMapper, org.mockito.Mockito.never())
                .countGrantedEpisodesInRange(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    }

    @Test
    void expiredRequestedEpisodeRenewsOnlyThatEpisode() {
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID))).thenReturn(Collections.singletonList(
                catalog(TENANT_ID, false, 0, "正常", 20, 2, 3)));
        SkitContentEntitlementDO expired = entitlement(
                TENANT_ID, MEMBER_ID, DRAMA_ID, 3, "GRANTED")
                .setGrantedAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(3))).thenReturn(Collections.singletonList(expired));

        SkitContentScopeService.UnlockScope scope =
                service.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3);

        assertFalse(scope.isAlreadyEntitled());
        assertEquals(3, scope.getEpisodeFrom());
        assertEquals(3, scope.getEpisodeTo());
    }

    @Test
    void expiredPreviouslyGrantedLaterEpisodeRenewsOnlyThatEpisodeWithoutLiveFrontier() {
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID))).thenReturn(Collections.singletonList(
                catalog(TENANT_ID, false, 0, "正常", 20, 2, 3)));
        SkitContentEntitlementDO expired = entitlement(
                TENANT_ID, MEMBER_ID, DRAMA_ID, 9, "GRANTED")
                .setGrantedAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(9))).thenReturn(Collections.singletonList(expired));

        SkitContentScopeService.UnlockScope scope =
                service.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 9);

        assertFalse(scope.isAlreadyEntitled());
        assertEquals(9, scope.getEpisodeFrom());
        assertEquals(9, scope.getEpisodeTo());
        assertEquals("drama:61:episode:9", scope.getCanonicalScope());
        verify(entitlementMapper, org.mockito.Mockito.never())
                .countGrantedEpisodesInRange(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    }

    @Test
    void missingWrongTenantDeletedOfflineOrAmbiguousCatalogFailsClosed() {
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID)))
                .thenReturn(Collections.emptyList())
                .thenReturn(Collections.singletonList(
                        catalog(TENANT_ID + 1, false, 0, "上架", 20, 2, 3)))
                .thenReturn(Collections.singletonList(
                        catalog(TENANT_ID, true, 0, "上架", 20, 2, 3)))
                .thenReturn(Collections.singletonList(
                        catalog(TENANT_ID, false, 0, "下架", 20, 2, 3)))
                .thenReturn(Arrays.asList(
                        catalog(TENANT_ID, false, 0, "上架", 20, 2, 3),
                        catalog(TENANT_ID, false, 0, "上架", 20, 2, 3)));

        assertInvalid(() -> service.requireAccessibleDrama(DRAMA_ID));
        assertInvalid(() -> service.requireAccessibleDrama(DRAMA_ID));
        assertInvalid(() -> service.requireAccessibleDrama(DRAMA_ID));
        assertInvalid(() -> service.requireAccessibleDrama(DRAMA_ID));
        assertInvalid(() -> service.requireAccessibleDrama(DRAMA_ID));
    }

    @Test
    void catalogFreeSetDoesNotExemptAnEpisodeFromItsOwnAdScope() {
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID))).thenReturn(Collections.singletonList(
                catalog(TENANT_ID, false, 0, "上架", 20, 8, 5)));
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(1))).thenReturn(Collections.emptyList());

        SkitContentScopeService.UnlockScope scope =
                service.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 1);

        assertEquals(1, scope.getEpisodeFrom());
        assertEquals(1, scope.getEpisodeTo());
        assertEquals("drama:61:episode:1", scope.getCanonicalScope());
        assertFalse(scope.isAlreadyEntitled());
    }

    @Test
    void finalEpisodeBoundaryProducesItsOwnAdScopeWithoutAContinuityRequirement() {
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID))).thenReturn(Collections.singletonList(
                catalog(TENANT_ID, false, 0, "上架", 20, 8, 5)));
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(20))).thenReturn(Collections.emptyList());

        SkitContentScopeService.UnlockScope scope =
                service.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 20);

        assertEquals(20, scope.getEpisodeFrom());
        assertEquals(20, scope.getEpisodeTo());
        assertEquals("drama:61:episode:20", scope.getCanonicalScope());
        assertFalse(scope.isAlreadyEntitled());
        verify(entitlementMapper, org.mockito.Mockito.never())
                .countGrantedEpisodesInRange(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    }

    @Test
    void outOfBoundsEpisodeCanNeverProduceAdRevenue() {
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID))).thenReturn(Collections.singletonList(
                catalog(TENANT_ID, false, 0, "上架", 20, 8, 5)));

        assertInvalid(() -> service.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 0));
        assertInvalid(() -> service.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 21));
        verify(entitlementMapper, org.mockito.Mockito.never())
                .selectEpisodesForUpdate(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void malformedCatalogAndRevokedRequestedEntitlementFailClosed() {
        SkitAdminRecordDO malformed = catalog(TENANT_ID, false, 0, "上架", 20, 2, 3);
        malformed.setRecordData("{not-json");
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID)))
                .thenReturn(Collections.singletonList(malformed))
                .thenReturn(Collections.singletonList(
                        catalog(TENANT_ID, false, 0, "上架", 20, 2, 3)));

        assertInvalid(() -> service.requireAccessibleDrama(DRAMA_ID));

        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(3))).thenReturn(Collections.singletonList(
                entitlement(TENANT_ID, MEMBER_ID, DRAMA_ID, 3, "SECURITY_REVOKED")));
        assertInvalid(() -> service.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 3));
    }

    @Test
    void nonContinuousEpisodeJumpProducesItsOwnSingleEpisodeScope() {
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID))).thenReturn(Collections.singletonList(
                catalog(TENANT_ID, false, 0, "已完结", 20, 2, 3)));
        when(entitlementMapper.selectEpisodesForUpdate(TENANT_ID, MEMBER_ID, DRAMA_ID,
                Collections.singletonList(9))).thenReturn(Collections.emptyList());

        SkitContentScopeService.UnlockScope scope =
                service.resolveUnlockScopeForUpdate(MEMBER_ID, DRAMA_ID, 9);

        assertEquals(9, scope.getEpisodeFrom());
        assertEquals(9, scope.getEpisodeTo());
        assertEquals("drama:61:episode:9", scope.getCanonicalScope());
        assertFalse(scope.isAlreadyEntitled());
        verify(entitlementMapper, org.mockito.Mockito.never())
                .countGrantedEpisodesInRange(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    }

    @Test
    void catalogAdPolicyFieldsCannotOverrideTheFixedPerEpisodeAdPolicy() {
        SkitAdminRecordDO missing = catalog(TENANT_ID, false, 0, "已上架", 20, 2, 3);
        missing.setRecordData("{\"pangleDramaId\":61,\"episodes\":20,"
                + "\"publishStatus\":\"已上架\"}");
        SkitAdminRecordDO zero = catalog(TENANT_ID, false, 0, "连载中", 20, 2, 3);
        zero.setRecordData("{\"pangleDramaId\":61,\"episodes\":20,"
                + "\"freeEpisodes\":0,\"unlockSize\":0,\"status\":\"连载中\"}");
        SkitAdminRecordDO malformed = catalog(TENANT_ID, false, 0, "已完结", 20, 2, 3);
        malformed.setRecordData("{\"pangleDramaId\":61,\"episodes\":20,"
                + "\"freeSet\":{\"unexpected\":true},\"lockSet\":\"not-a-number\","
                + "\"publishStatus\":\"已完结\"}");
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID))).thenReturn(Collections.singletonList(missing))
                .thenReturn(Collections.singletonList(zero))
                .thenReturn(Collections.singletonList(malformed));

        assertAccessibleWithFixedPerEpisodePolicy();
        assertAccessibleWithFixedPerEpisodePolicy();
        assertAccessibleWithFixedPerEpisodePolicy();
    }

    @Test
    void explicitUnpublishedCatalogStillFailsClosed() {
        SkitAdminRecordDO unpublished = catalog(TENANT_ID, false, 0, "连载中", 20, 2, 3);
        unpublished.setRecordData("{\"pangleDramaId\":61,\"episodes\":20,"
                + "\"status\":\"连载中\",\"publishStatus\":\"下架\"}");
        when(recordMapper.selectDramaCatalogByBusinessIdForShare(TENANT_ID,
                Long.toString(DRAMA_ID))).thenReturn(Collections.singletonList(unpublished));

        assertInvalid(() -> service.requireAccessibleDrama(DRAMA_ID));
    }

    private void assertAccessibleWithFixedPerEpisodePolicy() {
        SkitContentScopeService.AccessibleDrama drama = service.requireAccessibleDrama(DRAMA_ID);
        assertEquals(TENANT_ID, drama.getTenantId());
        assertEquals(DRAMA_ID, drama.getDramaId());
        assertEquals(20, drama.getTotalEpisodes());
        assertEquals(0, drama.getFreeEpisodes());
        assertEquals(1, drama.getUnlockSize());
    }

    private void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        ServiceException failure = assertThrows(ServiceException.class, executable);
        assertEquals(AD_SESSION_INVALID.getCode(), failure.getCode());
    }

    private SkitAdminRecordDO catalog(long tenantId, boolean deleted, int recordStatus,
                                      String publishStatus, int totalEpisodes,
                                      int freeEpisodes, int unlockSize) {
        SkitAdminRecordDO row = SkitAdminRecordDO.builder()
                .id(71L).pageKey("drama").rowKey("drama-61")
                .recordData("{\"id\":61,\"episodes\":" + totalEpisodes
                        + ",\"freeEpisodes\":" + freeEpisodes
                        + ",\"unlockSize\":" + unlockSize
                        + ",\"status\":\"" + publishStatus + "\"}")
                .status(recordStatus).sort(0).build();
        row.setTenantId(tenantId);
        row.setDeleted(deleted);
        return row;
    }

    private SkitContentEntitlementDO entitlement(long tenantId, long memberId, long dramaId,
                                                  int episodeNo, String status) {
        SkitContentEntitlementDO row = new SkitContentEntitlementDO()
                .setId(81L + episodeNo).setMemberId(memberId).setDramaId(dramaId)
                .setEpisodeNo(episodeNo).setStatus(status)
                .setGrantedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        row.setTenantId(tenantId);
        row.setDeleted(false);
        return row;
    }
}
