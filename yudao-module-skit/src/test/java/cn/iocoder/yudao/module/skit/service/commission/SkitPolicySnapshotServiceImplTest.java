package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitAdPolicySnapshotDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionPlanDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionRuleDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberClosureDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitAdPolicySnapshotMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionPlanMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionRuleMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberClosureMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.COMMISSION_RULE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_DISABLED;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.COMMISSION_PLAN_ACTIVE;
import static cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService.EligibilityReason.DISABLED;
import static cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService.EligibilityReason.ELIGIBLE;
import static cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService.EligibilityReason.MISSING_ANCESTOR;
import static cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService.EligibilityReason.TENANT_MISMATCH;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitPolicySnapshotServiceImplTest {

    private static final Long TENANT_ID = 42L;
    private static final Long SOURCE_MEMBER_ID = 100L;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-14T10:11:12.987654321Z"), ZoneOffset.UTC);

    @Mock
    private SkitCommissionPlanMapper planMapper;
    @Mock
    private SkitCommissionRuleMapper ruleMapper;
    @Mock
    private SkitMemberClosureMapper closureMapper;
    @Mock
    private SkitMemberMapper memberMapper;
    @Mock
    private SkitAdPolicySnapshotMapper snapshotMapper;
    @Mock
    private TenantService tenantService;

    @BeforeEach
    void setTenant() {
        TenantContextHolder.setTenantId(TENANT_ID);
        lenient().when(memberMapper.selectByTenantAndIdsForShare(anyLong(), anyList()))
                .thenAnswer(invocation -> ((List<Long>) invocation.getArgument(1)).stream()
                        .map(id -> memberMapper.selectByTenantAndIdForShare(TENANT_ID, id))
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList()));
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void snapshotsViewerAndArbitraryAncestorsInCanonicalLevelOrder() {
        SkitCommissionPlanDO plan = activePlan(11L, 7, TENANT_ID);
        List<SkitCommissionRuleDO> rules = Arrays.asList(
                rule(11L, 0, 4000), rule(11L, 1, 2000),
                rule(11L, 2, 1000), rule(11L, 3, 500));
        when(planMapper.selectActiveForShare(TENANT_ID)).thenReturn(plan);
        when(tenantService.getTenantForShare(TENANT_ID)).thenReturn(enabledTenant());
        when(ruleMapper.selectListByPlanIdForShare(TENANT_ID, 11L)).thenReturn(rules);
        when(closureMapper.selectAncestorsForShare(TENANT_ID, SOURCE_MEMBER_ID)).thenReturn(Arrays.asList(
                closure(SOURCE_MEMBER_ID, 0), closure(90L, 1),
                closure(80L, 2), closure(10L, 3)));
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, SOURCE_MEMBER_ID))
                .thenReturn(member(SOURCE_MEMBER_ID, TENANT_ID, CommonStatusEnum.ENABLE.getStatus()));
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, 90L))
                .thenReturn(member(90L, TENANT_ID, CommonStatusEnum.ENABLE.getStatus()));
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, 80L))
                .thenReturn(member(80L, TENANT_ID, CommonStatusEnum.ENABLE.getStatus()));
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, 10L))
                .thenReturn(member(10L, TENANT_ID, CommonStatusEnum.ENABLE.getStatus()));
        when(snapshotMapper.insert(any(SkitAdPolicySnapshotDO.class))).thenAnswer(invocation -> {
            SkitAdPolicySnapshotDO row = invocation.getArgument(0);
            row.setId(501L);
            return 1;
        });

        SkitPolicySnapshotService.PolicySnapshot result = service().createSnapshot(SOURCE_MEMBER_ID);

        assertEquals(501L, result.getId());
        assertEquals(TENANT_ID, result.getTenantId());
        assertEquals(11L, result.getPlanId());
        assertEquals(7, result.getRuleVersion());
        assertEquals(SOURCE_MEMBER_ID, result.getSourceMemberId());
        assertEquals(LocalDateTime.of(2026, 7, 14, 10, 11, 12), result.getPolicySnapshotAt());
        assertEquals(Arrays.asList(0, 1, 2, 3), result.getBeneficiaries().stream()
                .map(SkitPolicySnapshotService.BeneficiarySlot::getLevel)
                .collect(Collectors.toList()));
        assertEquals(Arrays.asList(0, 1, 2, 3), result.getChain().stream()
                .map(SkitPolicySnapshotService.ChainNode::getLevel)
                .collect(Collectors.toList()));
        assertEquals(7_500, result.getConfiguredRateBps());
        assertEquals(7_500, result.getEligibleRateBps());
        assertTrue(result.getBeneficiaries().stream()
                .allMatch(slot -> slot.isEligible() && slot.getReason() == ELIGIBLE));

        String expectedJson = "{\"schemaVersion\":1,\"tenantId\":42,\"tenantStatus\":0,"
                + "\"planId\":11,\"ruleVersion\":7,\"sourceMemberId\":100,"
                + "\"policySnapshotAt\":\"2026-07-14T10:11:12\",\"chain\":["
                + "{\"level\":0,\"memberId\":100,\"memberStatus\":0,"
                + "\"eligible\":true,\"reason\":\"ELIGIBLE\"},"
                + "{\"level\":1,\"memberId\":90,\"memberStatus\":0,"
                + "\"eligible\":true,\"reason\":\"ELIGIBLE\"},"
                + "{\"level\":2,\"memberId\":80,\"memberStatus\":0,"
                + "\"eligible\":true,\"reason\":\"ELIGIBLE\"},"
                + "{\"level\":3,\"memberId\":10,\"memberStatus\":0,"
                + "\"eligible\":true,\"reason\":\"ELIGIBLE\"}],\"beneficiaries\":["
                + "{\"level\":0,\"rateBps\":4000,\"memberId\":100,\"memberStatus\":0,"
                + "\"eligible\":true,\"reason\":\"ELIGIBLE\"},"
                + "{\"level\":1,\"rateBps\":2000,\"memberId\":90,\"memberStatus\":0,"
                + "\"eligible\":true,\"reason\":\"ELIGIBLE\"},"
                + "{\"level\":2,\"rateBps\":1000,\"memberId\":80,\"memberStatus\":0,"
                + "\"eligible\":true,\"reason\":\"ELIGIBLE\"},"
                + "{\"level\":3,\"rateBps\":500,\"memberId\":10,\"memberStatus\":0,"
                + "\"eligible\":true,\"reason\":\"ELIGIBLE\"}],"
                + "\"configuredRateBps\":7500,\"eligibleRateBps\":7500}";
        assertEquals(expectedJson, result.getSnapshotJson());
        assertArrayEquals(sha256(expectedJson), result.getSnapshotHash());

        ArgumentCaptor<SkitAdPolicySnapshotDO> rowCaptor =
                ArgumentCaptor.forClass(SkitAdPolicySnapshotDO.class);
        verify(snapshotMapper).insert(rowCaptor.capture());
        SkitAdPolicySnapshotDO row = rowCaptor.getValue();
        assertEquals(TENANT_ID, row.getTenantId());
        assertEquals(11L, row.getPlanId());
        assertEquals(7, row.getRuleVersion());
        assertEquals(1, row.getSnapshotSchemaVersion());
        assertEquals(expectedJson, row.getSnapshotJson());
        assertArrayEquals(sha256(expectedJson), row.getSnapshotHash());
        assertEquals(result.getPolicySnapshotAt(), row.getPolicySnapshotAt());

        InOrder order = inOrder(tenantService, planMapper, ruleMapper, closureMapper, memberMapper, snapshotMapper);
        order.verify(tenantService).getTenantForShare(TENANT_ID);
        order.verify(planMapper).selectActiveForShare(TENANT_ID);
        order.verify(ruleMapper).selectListByPlanIdForShare(TENANT_ID, 11L);
        order.verify(closureMapper).selectAncestorsForShare(TENANT_ID, SOURCE_MEMBER_ID);
        order.verify(memberMapper).selectByTenantAndIdForShare(TENANT_ID, 10L);
        order.verify(memberMapper).selectByTenantAndIdForShare(TENANT_ID, 80L);
        order.verify(memberMapper).selectByTenantAndIdForShare(TENANT_ID, 90L);
        order.verify(memberMapper).selectByTenantAndIdForShare(TENANT_ID, SOURCE_MEMBER_ID);
        order.verify(snapshotMapper).insert(any(SkitAdPolicySnapshotDO.class));
        verify(memberMapper).selectByTenantAndIdsForShare(
                TENANT_ID, Arrays.asList(10L, 80L, 90L, SOURCE_MEMBER_ID));
    }

    @Test
    void shanghaiClockSnapshotsThePolicyAtSystemLocalTime() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        mockPlanAndTenant(11L, 7, Collections.singletonList(rule(11L, 0, 7000)));
        when(closureMapper.selectAncestorsForShare(TENANT_ID, SOURCE_MEMBER_ID))
                .thenReturn(Collections.singletonList(closure(SOURCE_MEMBER_ID, 0)));
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, SOURCE_MEMBER_ID))
                .thenReturn(member(SOURCE_MEMBER_ID, TENANT_ID, CommonStatusEnum.ENABLE.getStatus()));
        assignSnapshotIds(502L);
        SkitPolicySnapshotServiceImpl shanghaiService = new SkitPolicySnapshotServiceImpl(
                planMapper, ruleMapper, closureMapper, memberMapper, snapshotMapper,
                tenantService, Clock.fixed(FIXED_CLOCK.instant(), shanghai));

        SkitPolicySnapshotService.PolicySnapshot snapshot =
                shanghaiService.createSnapshot(SOURCE_MEMBER_ID);

        assertEquals(LocalDateTime.of(2026, 7, 14, 18, 11, 12),
                snapshot.getPolicySnapshotAt());
        assertTrue(snapshot.getSnapshotJson()
                .contains("\"policySnapshotAt\":\"2026-07-14T18:11:12\""));
    }

    @Test
    void preservesMissingDisabledAndCrossTenantAncestorSlotsAsIneligible() {
        mockPlanAndTenant(11L, 7, Arrays.asList(
                rule(11L, 0, 5000), rule(11L, 1, 1000),
                rule(11L, 2, 1000), rule(11L, 3, 1000), rule(11L, 101, 500)));
        when(closureMapper.selectAncestorsForShare(TENANT_ID, SOURCE_MEMBER_ID)).thenReturn(Arrays.asList(
                closure(SOURCE_MEMBER_ID, 0), closure(90L, 1), closure(80L, 2), closure(70L, 3),
                closure(60L, 4)));
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, SOURCE_MEMBER_ID))
                .thenReturn(member(SOURCE_MEMBER_ID, TENANT_ID, CommonStatusEnum.ENABLE.getStatus()));
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, 90L))
                .thenReturn(member(90L, TENANT_ID, CommonStatusEnum.DISABLE.getStatus()));
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, 80L)).thenReturn(null);
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, 70L))
                .thenReturn(member(70L, 99L, CommonStatusEnum.ENABLE.getStatus()));
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, 60L))
                .thenReturn(member(60L, TENANT_ID, CommonStatusEnum.ENABLE.getStatus()));
        assignSnapshotIds(601L);

        SkitPolicySnapshotService.PolicySnapshot snapshot = service().createSnapshot(SOURCE_MEMBER_ID);

        assertSlot(snapshot, 0, SOURCE_MEMBER_ID, true, ELIGIBLE);
        assertSlot(snapshot, 1, 90L, false, DISABLED);
        assertSlot(snapshot, 2, 80L, false, MISSING_ANCESTOR);
        assertSlot(snapshot, 3, 70L, false, TENANT_MISMATCH);
        assertSlot(snapshot, 101, null, false, MISSING_ANCESTOR);
        assertEquals(Arrays.asList(0, 1, 2, 3, 4), snapshot.getChain().stream()
                .map(SkitPolicySnapshotService.ChainNode::getLevel)
                .collect(Collectors.toList()));
        assertFalse(snapshot.getBeneficiaries().stream().anyMatch(slot -> slot.getLevel() == 4));
        assertEquals(8_500, snapshot.getConfiguredRateBps());
        assertEquals(5_000, snapshot.getEligibleRateBps());
        assertTrue(snapshot.getSnapshotJson().contains("\"reason\":\"DISABLED\""));
        assertTrue(snapshot.getSnapshotJson().contains("\"reason\":\"MISSING_ANCESTOR\""));
        assertTrue(snapshot.getSnapshotJson().contains("\"reason\":\"TENANT_MISMATCH\""));
    }

    @Test
    void sourceMemberMustBeEnabledInTheCurrentTenant() {
        mockPlanAndTenant(11L, 7, Collections.singletonList(rule(11L, 0, 10000)));
        when(closureMapper.selectAncestorsForShare(TENANT_ID, SOURCE_MEMBER_ID))
                .thenReturn(Collections.singletonList(closure(SOURCE_MEMBER_ID, 0)));
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, SOURCE_MEMBER_ID))
                .thenReturn(member(SOURCE_MEMBER_ID, TENANT_ID, CommonStatusEnum.DISABLE.getStatus()));

        ServiceException failure = assertThrows(ServiceException.class,
                () -> service().createSnapshot(SOURCE_MEMBER_ID));

        assertEquals(MEMBER_DISABLED.getCode(), failure.getCode());
        verifyNoInteractions(snapshotMapper);
    }

    @Test
    void rejectsPlanOrRuleStateThatCannotBeAValidPublishedPolicy() {
        when(planMapper.selectActiveForShare(TENANT_ID))
                .thenReturn(activePlan(11L, 7, 99L));

        assertThrows(IllegalStateException.class, () -> service().createSnapshot(SOURCE_MEMBER_ID));
        verifyNoInteractions(ruleMapper, closureMapper, memberMapper, snapshotMapper);

        when(planMapper.selectActiveForShare(TENANT_ID))
                .thenReturn(activePlan(11L, 7, TENANT_ID));
        when(tenantService.getTenantForShare(TENANT_ID)).thenReturn(enabledTenant());
        when(ruleMapper.selectListByPlanIdForShare(TENANT_ID, 11L)).thenReturn(Arrays.asList(
                rule(11L, 0, 7000), rule(11L, 1, 4000)));

        ServiceException invalidTotal = assertThrows(ServiceException.class,
                () -> service().createSnapshot(SOURCE_MEMBER_ID));
        assertEquals(COMMISSION_RULE_INVALID.getCode(), invalidTotal.getCode());
        verifyNoInteractions(closureMapper, memberMapper, snapshotMapper);
    }

    @Test
    void rejectsPublishedRulesWithoutExactlyOneLevelZero() {
        mockPlanAndTenant(11L, 7, Arrays.asList(rule(11L, 1, 1000), rule(11L, 2, 500)));

        ServiceException missingViewer = assertThrows(ServiceException.class,
                () -> service().createSnapshot(SOURCE_MEMBER_ID));

        assertEquals(COMMISSION_RULE_INVALID.getCode(), missingViewer.getCode());
        verifyNoInteractions(closureMapper, memberMapper, snapshotMapper);
    }

    @Test
    void tenantMustStillBeEnabledWhenThePublishedPlanIsLocked() {
        when(tenantService.getTenantForShare(TENANT_ID)).thenReturn(new TenantDO().setId(TENANT_ID)
                .setStatus(CommonStatusEnum.DISABLE.getStatus()));

        assertThrows(IllegalStateException.class, () -> service().createSnapshot(SOURCE_MEMBER_ID));

        verifyNoInteractions(ruleMapper, closureMapper, memberMapper, snapshotMapper);
    }

    @Test
    void publishingANewPlanNeverMutatesAnExistingSnapshotPayloadOrHash() {
        when(planMapper.selectActiveForShare(TENANT_ID)).thenReturn(
                activePlan(11L, 1, TENANT_ID), activePlan(12L, 2, TENANT_ID));
        when(tenantService.getTenantForShare(TENANT_ID)).thenReturn(enabledTenant());
        when(ruleMapper.selectListByPlanIdForShare(TENANT_ID, 11L))
                .thenReturn(Collections.singletonList(rule(11L, 0, 7000)));
        when(ruleMapper.selectListByPlanIdForShare(TENANT_ID, 12L))
                .thenReturn(Collections.singletonList(rule(12L, 0, 2500)));
        when(closureMapper.selectAncestorsForShare(TENANT_ID, SOURCE_MEMBER_ID))
                .thenReturn(Collections.singletonList(closure(SOURCE_MEMBER_ID, 0)));
        when(memberMapper.selectByTenantAndIdForShare(TENANT_ID, SOURCE_MEMBER_ID))
                .thenReturn(member(SOURCE_MEMBER_ID, TENANT_ID, CommonStatusEnum.ENABLE.getStatus()));
        assignSnapshotIds(701L, 702L);

        SkitPolicySnapshotService.PolicySnapshot oldSnapshot = service().createSnapshot(SOURCE_MEMBER_ID);
        String oldJson = oldSnapshot.getSnapshotJson();
        byte[] oldHash = oldSnapshot.getSnapshotHash();
        SkitPolicySnapshotService.PolicySnapshot newSnapshot = service().createSnapshot(SOURCE_MEMBER_ID);

        assertEquals(701L, oldSnapshot.getId());
        assertEquals(1, oldSnapshot.getRuleVersion());
        assertEquals(oldJson, oldSnapshot.getSnapshotJson());
        assertArrayEquals(oldHash, oldSnapshot.getSnapshotHash());
        assertTrue(oldJson.contains("\"rateBps\":7000"));
        assertEquals(702L, newSnapshot.getId());
        assertEquals(2, newSnapshot.getRuleVersion());
        assertTrue(newSnapshot.getSnapshotJson().contains("\"rateBps\":2500"));
        assertNotEquals(oldSnapshot.getSnapshotJson(), newSnapshot.getSnapshotJson());
        assertFalse(Arrays.equals(oldSnapshot.getSnapshotHash(), newSnapshot.getSnapshotHash()));

        byte[] exposedHash = oldSnapshot.getSnapshotHash();
        exposedHash[0] ^= 0x7f;
        assertArrayEquals(oldHash, oldSnapshot.getSnapshotHash(), "hash getter must be defensive");
        assertThrows(UnsupportedOperationException.class,
                () -> oldSnapshot.getBeneficiaries().add(oldSnapshot.getBeneficiaries().get(0)));
    }

    @Test
    void rejectsDuplicateOrGappedClosureDistancesBeforeLockingBeneficiaries() {
        mockPlanAndTenant(11L, 7, Arrays.asList(
                rule(11L, 0, 5000), rule(11L, 1, 1000), rule(11L, 2, 1000)));
        when(closureMapper.selectAncestorsForShare(TENANT_ID, SOURCE_MEMBER_ID)).thenReturn(
                Arrays.asList(closure(SOURCE_MEMBER_ID, 0), closure(80L, 2)),
                Arrays.asList(closure(SOURCE_MEMBER_ID, 0), closure(90L, 1), closure(80L, 1)));

        ServiceException gap = assertThrows(ServiceException.class,
                () -> service().createSnapshot(SOURCE_MEMBER_ID));
        ServiceException duplicate = assertThrows(ServiceException.class,
                () -> service().createSnapshot(SOURCE_MEMBER_ID));

        assertEquals(COMMISSION_RULE_INVALID.getCode(), gap.getCode());
        assertEquals(COMMISSION_RULE_INVALID.getCode(), duplicate.getCode());
        verifyNoInteractions(memberMapper, snapshotMapper);
    }

    @Test
    void getRequiredRehashesRawUtf8AndReturnsThePersistedHistoricalSnapshot() {
        String json = simpleSnapshotJson(10000);
        SkitAdPolicySnapshotDO row = persistedRow(801L, json);
        when(snapshotMapper.selectByTenantAndId(TENANT_ID, 801L)).thenReturn(row);

        SkitPolicySnapshotService.PolicySnapshot snapshot = service().getRequired(801L);

        assertEquals(801L, snapshot.getId());
        assertEquals(json, snapshot.getSnapshotJson());
        assertArrayEquals(sha256(json), snapshot.getSnapshotHash());
        assertEquals(1, snapshot.getBeneficiaries().size());
        assertSlot(snapshot, 0, SOURCE_MEMBER_ID, true, ELIGIBLE);
        verify(snapshotMapper).selectByTenantAndId(TENANT_ID, 801L);
        verifyNoInteractions(planMapper, ruleMapper, closureMapper, memberMapper, tenantService);
    }

    @Test
    void getRequiredRejectsHashUnknownSchemaRowPayloadMismatchAndInvalidRules() {
        String validJson = simpleSnapshotJson(10000);
        SkitAdPolicySnapshotDO badHash = persistedRow(802L, validJson);
        badHash.setSnapshotHash(new byte[32]);
        when(snapshotMapper.selectByTenantAndId(TENANT_ID, 802L)).thenReturn(badHash);
        assertThrows(IllegalStateException.class, () -> service().getRequired(802L));

        String schemaTwoJson = validJson.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        SkitAdPolicySnapshotDO unknownSchema = persistedRow(803L, schemaTwoJson);
        unknownSchema.setSnapshotSchemaVersion(2);
        when(snapshotMapper.selectByTenantAndId(TENANT_ID, 803L)).thenReturn(unknownSchema);
        assertThrows(IllegalStateException.class, () -> service().getRequired(803L));

        SkitAdPolicySnapshotDO planMismatch = persistedRow(804L, validJson);
        planMismatch.setPlanId(12L);
        when(snapshotMapper.selectByTenantAndId(TENANT_ID, 804L)).thenReturn(planMismatch);
        assertThrows(IllegalStateException.class, () -> service().getRequired(804L));

        SkitAdPolicySnapshotDO timeMismatch = persistedRow(805L, validJson);
        timeMismatch.setPolicySnapshotAt(LocalDateTime.of(2026, 7, 14, 10, 11, 13));
        when(snapshotMapper.selectByTenantAndId(TENANT_ID, 805L)).thenReturn(timeMismatch);
        assertThrows(IllegalStateException.class, () -> service().getRequired(805L));

        String invalidRateJson = simpleSnapshotJson(10001);
        when(snapshotMapper.selectByTenantAndId(TENANT_ID, 806L))
                .thenReturn(persistedRow(806L, invalidRateJson));
        assertThrows(IllegalStateException.class, () -> service().getRequired(806L));

        String duplicateLevelJson = validJson.replace("],\"configuredRateBps\"", ","
                + "{\"level\":0,\"rateBps\":0,\"memberId\":100,\"memberStatus\":0,"
                + "\"eligible\":true,\"reason\":\"ELIGIBLE\"}],\"configuredRateBps\"");
        when(snapshotMapper.selectByTenantAndId(TENANT_ID, 807L))
                .thenReturn(persistedRow(807L, duplicateLevelJson));
        assertThrows(IllegalStateException.class, () -> service().getRequired(807L));
    }

    @Test
    void snapshotApiAndMapperExposeAppendOnlyRows() throws Exception {
        Method create = SkitPolicySnapshotServiceImpl.class
                .getMethod("createSnapshot", Long.class);
        assertNotNull(create.getAnnotation(Transactional.class));
        assertFalse(BaseMapper.class.isAssignableFrom(SkitAdPolicySnapshotMapper.class));
        assertFalse(Arrays.stream(SkitAdPolicySnapshotMapper.class.getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.startsWith("update") || name.startsWith("delete")));
    }

    @Test
    void narrowMapperInsertCarriesOneTenantColumnWithOrWithoutTableMetadata() throws Exception {
        Method method = SkitAdPolicySnapshotMapper.class.getMethod("insert", SkitAdPolicySnapshotDO.class);
        String sql = String.join(" ", method.getAnnotation(Insert.class).value());
        assertTrue(sql.contains("(tenant_id,"),
                "an explicit unquoted tenant column is required when narrow mapper metadata is absent");
        assertTrue(sql.contains("(#{tenantId},"));

        net.sf.jsqlparser.statement.insert.Insert parsed =
                (net.sf.jsqlparser.statement.insert.Insert) CCJSqlParserUtil.parse(
                        sql.replaceAll("#\\{[^}]+}", "?"));
        TenantDatabaseInterceptor tenantHandler = new TenantDatabaseInterceptor(new TenantProperties());
        assertTrue(tenantHandler.ignoreInsert(parsed.getColumns(), tenantHandler.getTenantIdColumn()),
                "TenantLine must recognize the existing column instead of injecting it twice");
    }

    private SkitPolicySnapshotServiceImpl service() {
        return new SkitPolicySnapshotServiceImpl(planMapper, ruleMapper, closureMapper,
                memberMapper, snapshotMapper, tenantService, FIXED_CLOCK);
    }

    private void mockPlanAndTenant(Long planId, int version, List<SkitCommissionRuleDO> rules) {
        when(planMapper.selectActiveForShare(TENANT_ID))
                .thenReturn(activePlan(planId, version, TENANT_ID));
        when(tenantService.getTenantForShare(TENANT_ID)).thenReturn(enabledTenant());
        when(ruleMapper.selectListByPlanIdForShare(TENANT_ID, planId)).thenReturn(rules);
    }

    private void assignSnapshotIds(Long... ids) {
        AtomicLong index = new AtomicLong();
        when(snapshotMapper.insert(any(SkitAdPolicySnapshotDO.class))).thenAnswer(invocation -> {
            SkitAdPolicySnapshotDO row = invocation.getArgument(0);
            row.setId(ids[(int) index.getAndIncrement()]);
            return 1;
        });
    }

    private static SkitCommissionPlanDO activePlan(Long id, int version, Long tenantId) {
        SkitCommissionPlanDO plan = SkitCommissionPlanDO.builder().id(id).version(version)
                .status(COMMISSION_PLAN_ACTIVE).publishedTime(LocalDateTime.of(2026, 7, 1, 0, 0)).build();
        plan.setTenantId(tenantId);
        return plan;
    }

    private static SkitCommissionRuleDO rule(Long planId, int level, int rateBps) {
        SkitCommissionRuleDO rule = SkitCommissionRuleDO.builder()
                .planId(planId).levelNo(level).rateBps(rateBps).build();
        rule.setTenantId(TENANT_ID);
        return rule;
    }

    private static SkitMemberClosureDO closure(Long ancestorId, int distance) {
        SkitMemberClosureDO closure = SkitMemberClosureDO.builder().ancestorId(ancestorId)
                .descendantId(SOURCE_MEMBER_ID).distance(distance).build();
        closure.setTenantId(TENANT_ID);
        return closure;
    }

    private static SkitMemberDO member(Long id, Long tenantId, Integer status) {
        SkitMemberDO member = SkitMemberDO.builder().id(id).status(status).build();
        member.setTenantId(tenantId);
        return member;
    }

    private static TenantDO enabledTenant() {
        return new TenantDO().setId(TENANT_ID).setStatus(CommonStatusEnum.ENABLE.getStatus());
    }

    private static String simpleSnapshotJson(int rateBps) {
        return "{\"schemaVersion\":1,\"tenantId\":42,\"tenantStatus\":0,"
                + "\"planId\":11,\"ruleVersion\":7,\"sourceMemberId\":100,"
                + "\"policySnapshotAt\":\"2026-07-14T10:11:12\",\"chain\":["
                + "{\"level\":0,\"memberId\":100,\"memberStatus\":0,"
                + "\"eligible\":true,\"reason\":\"ELIGIBLE\"}],\"beneficiaries\":["
                + "{\"level\":0,\"rateBps\":" + rateBps
                + ",\"memberId\":100,\"memberStatus\":0,"
                + "\"eligible\":true,\"reason\":\"ELIGIBLE\"}],"
                + "\"configuredRateBps\":" + rateBps + ",\"eligibleRateBps\":" + rateBps + "}";
    }

    private static SkitAdPolicySnapshotDO persistedRow(Long id, String json) {
        SkitAdPolicySnapshotDO row = new SkitAdPolicySnapshotDO()
                .setId(id).setPlanId(11L).setSourceMemberId(SOURCE_MEMBER_ID)
                .setRuleVersion(7).setSnapshotSchemaVersion(1).setSnapshotJson(json)
                .setSnapshotHash(sha256(json))
                .setPolicySnapshotAt(LocalDateTime.of(2026, 7, 14, 10, 11, 12));
        row.setTenantId(TENANT_ID);
        return row;
    }

    private static void assertSlot(SkitPolicySnapshotService.PolicySnapshot snapshot, int level,
                                   Long memberId, boolean eligible,
                                   SkitPolicySnapshotService.EligibilityReason reason) {
        SkitPolicySnapshotService.BeneficiarySlot slot = snapshot.getBeneficiaries().stream()
                .filter(candidate -> candidate.getLevel() == level)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(memberId, slot.getMemberId());
        assertEquals(eligible, slot.isEligible());
        assertEquals(reason, slot.getReason());
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

}
