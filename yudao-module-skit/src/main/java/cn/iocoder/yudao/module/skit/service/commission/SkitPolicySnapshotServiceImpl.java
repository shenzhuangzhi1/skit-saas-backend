package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.COMMISSION_PLAN_NOT_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.COMMISSION_RULE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_DISABLED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_NOT_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_STATUS_INVALID;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.COMMISSION_PLAN_ACTIVE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.RATE_BASE;

@Service
public class SkitPolicySnapshotServiceImpl implements SkitPolicySnapshotService {

    private static final int SNAPSHOT_SCHEMA_VERSION = 1;
    private static final DateTimeFormatter SNAPSHOT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Set<String> ROOT_FIELDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "schemaVersion", "tenantId", "tenantStatus", "planId", "ruleVersion",
            "sourceMemberId", "policySnapshotAt", "chain", "beneficiaries",
            "configuredRateBps", "eligibleRateBps")));
    private static final Set<String> CHAIN_FIELDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("level", "memberId", "memberStatus", "eligible", "reason")));
    private static final Set<String> BENEFICIARY_FIELDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("level", "rateBps", "memberId", "memberStatus",
                    "eligible", "reason")));
    private static final ObjectMapper SNAPSHOT_READER = snapshotReader();

    private final SkitCommissionPlanMapper planMapper;
    private final SkitCommissionRuleMapper ruleMapper;
    private final SkitMemberClosureMapper closureMapper;
    private final SkitMemberMapper memberMapper;
    private final SkitAdPolicySnapshotMapper snapshotMapper;
    private final TenantService tenantService;
    private final Clock clock;

    @Autowired
    public SkitPolicySnapshotServiceImpl(SkitCommissionPlanMapper planMapper,
                                         SkitCommissionRuleMapper ruleMapper,
                                         SkitMemberClosureMapper closureMapper,
                                         SkitMemberMapper memberMapper,
                                         SkitAdPolicySnapshotMapper snapshotMapper,
                                         TenantService tenantService) {
        this(planMapper, ruleMapper, closureMapper, memberMapper, snapshotMapper,
                tenantService, Clock.systemDefaultZone());
    }

    SkitPolicySnapshotServiceImpl(SkitCommissionPlanMapper planMapper,
                                  SkitCommissionRuleMapper ruleMapper,
                                  SkitMemberClosureMapper closureMapper,
                                  SkitMemberMapper memberMapper,
                                  SkitAdPolicySnapshotMapper snapshotMapper,
                                  TenantService tenantService,
                                  Clock clock) {
        this.planMapper = Objects.requireNonNull(planMapper, "planMapper");
        this.ruleMapper = Objects.requireNonNull(ruleMapper, "ruleMapper");
        this.closureMapper = Objects.requireNonNull(closureMapper, "closureMapper");
        this.memberMapper = Objects.requireNonNull(memberMapper, "memberMapper");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public PolicySnapshot createSnapshot(Long sourceMemberId) {
        requirePositive(sourceMemberId, "sourceMemberId");
        Long tenantId = TenantContextHolder.getRequiredTenantId();

        TenantDO tenant = requireEnabledTenant(tenantId);
        SkitCommissionPlanDO plan = requireActivePlan(tenantId);
        List<SkitCommissionRuleDO> rules = validateAndSortRules(
                ruleMapper.selectListByPlanIdForShare(tenantId, plan.getId()), tenantId, plan.getId());
        List<SkitMemberClosureDO> chain = validateAndSortChain(
                closureMapper.selectAncestorsForShare(tenantId, sourceMemberId), tenantId, sourceMemberId);
        Map<Integer, LockedMember> lockedMembers = readChainMembersForShare(
                chain, tenantId, sourceMemberId);
        requireEnabledSource(lockedMembers.get(0), tenantId, sourceMemberId);

        List<ChainNode> chainSnapshot = buildChain(chain, lockedMembers);
        List<BeneficiarySlot> beneficiaries = buildBeneficiaries(rules, chainSnapshot);
        int configuredRateBps = configuredRateBps(beneficiaries);
        int eligibleRateBps = eligibleRateBps(beneficiaries);
        LocalDateTime snapshottedAt = LocalDateTime.now(clock).withNano(0);
        String snapshotJson = canonicalJson(SNAPSHOT_SCHEMA_VERSION, tenantId, tenant.getStatus(),
                plan.getId(), plan.getVersion(), sourceMemberId, snapshottedAt, chainSnapshot,
                beneficiaries, configuredRateBps, eligibleRateBps);
        byte[] snapshotHash = sha256(snapshotJson);

        SkitAdPolicySnapshotDO row = new SkitAdPolicySnapshotDO()
                .setPlanId(plan.getId())
                .setSourceMemberId(sourceMemberId)
                .setRuleVersion(plan.getVersion())
                .setSnapshotSchemaVersion(SNAPSHOT_SCHEMA_VERSION)
                .setSnapshotJson(snapshotJson)
                .setSnapshotHash(snapshotHash)
                .setPolicySnapshotAt(snapshottedAt);
        row.setTenantId(tenantId);
        if (snapshotMapper.insert(row) != 1 || row.getId() == null || row.getId() <= 0) {
            throw new IllegalStateException("Policy snapshot was not persisted exactly once");
        }
        return new PolicySnapshot(row.getId(), tenantId, tenant.getStatus(), plan.getId(),
                sourceMemberId, plan.getVersion(), SNAPSHOT_SCHEMA_VERSION, snapshotJson,
                snapshotHash, snapshottedAt, chainSnapshot, beneficiaries,
                configuredRateBps, eligibleRateBps);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PolicySnapshot getRequired(Long snapshotId) {
        requirePositive(snapshotId, "snapshotId");
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitAdPolicySnapshotDO row = snapshotMapper.selectByTenantAndId(tenantId, snapshotId);
        if (row == null) {
            throw new IllegalStateException("Policy snapshot does not exist in the current tenant");
        }
        validatePersistedRowEnvelope(row, tenantId, snapshotId);
        byte[] actualHash = sha256(row.getSnapshotJson());
        if (!MessageDigest.isEqual(actualHash, row.getSnapshotHash())) {
            throw new IllegalStateException("Policy snapshot hash mismatch");
        }

        ParsedSnapshot parsed = parseSnapshot(row.getSnapshotJson());
        validateParsedEnvelope(row, parsed, tenantId);
        String canonical = canonicalJson(parsed.schemaVersion, parsed.tenantId, parsed.tenantStatus,
                parsed.planId, parsed.ruleVersion, parsed.sourceMemberId,
                parsed.policySnapshotAt, parsed.chain, parsed.beneficiaries,
                parsed.configuredRateBps, parsed.eligibleRateBps);
        if (!canonical.equals(row.getSnapshotJson())) {
            throw new IllegalStateException("Policy snapshot JSON is not canonical");
        }
        return new PolicySnapshot(row.getId(), parsed.tenantId, parsed.tenantStatus,
                parsed.planId, parsed.sourceMemberId, parsed.ruleVersion, parsed.schemaVersion,
                row.getSnapshotJson(), actualHash, parsed.policySnapshotAt, parsed.chain,
                parsed.beneficiaries, parsed.configuredRateBps, parsed.eligibleRateBps);
    }

    private SkitCommissionPlanDO requireActivePlan(Long tenantId) {
        SkitCommissionPlanDO plan = planMapper.selectActiveForShare(tenantId);
        if (plan == null) {
            throw exception(COMMISSION_PLAN_NOT_EXISTS);
        }
        if (!tenantId.equals(plan.getTenantId()) || plan.getId() == null || plan.getId() <= 0
                || plan.getVersion() == null || plan.getVersion() <= 0
                || !Integer.valueOf(COMMISSION_PLAN_ACTIVE).equals(plan.getStatus())) {
            throw new IllegalStateException("Active commission plan is outside the locked tenant boundary");
        }
        return plan;
    }

    private TenantDO requireEnabledTenant(Long tenantId) {
        TenantDO tenant = tenantService.getTenantForShare(tenantId);
        tenantService.validTenant(tenantId);
        if (tenant == null || !tenantId.equals(tenant.getId())
                || !CommonStatusEnum.ENABLE.getStatus().equals(tenant.getStatus())) {
            throw new IllegalStateException("Tenant is not enabled for policy snapshotting");
        }
        return tenant;
    }

    private List<SkitCommissionRuleDO> validateAndSortRules(List<SkitCommissionRuleDO> supplied,
                                                             Long tenantId, Long planId) {
        if (supplied == null || supplied.isEmpty()) {
            throw invalidRule("必须配置 level 0 本人比例");
        }
        List<SkitCommissionRuleDO> rules = new ArrayList<>(supplied);
        Set<Integer> levels = new HashSet<>();
        long totalRate = 0L;
        for (SkitCommissionRuleDO rule : rules) {
            if (rule == null || !tenantId.equals(rule.getTenantId()) || !planId.equals(rule.getPlanId())) {
                throw invalidRule("规则不属于当前租户生效方案");
            }
            Integer level = rule.getLevelNo();
            Integer rate = rule.getRateBps();
            if (level == null || level < 0 || !levels.add(level)) {
                throw invalidRule("level 必须非负且不能重复");
            }
            if (rate == null || rate < 0 || rate > RATE_BASE) {
                throw invalidRule("rateBps 必须在 0 到 10000 之间");
            }
            totalRate += rate;
            if (totalRate > RATE_BASE) {
                throw invalidRule("全部层级比例之和不能超过 100% ");
            }
        }
        if (!levels.contains(0)) {
            throw invalidRule("必须配置 level 0 本人比例");
        }
        rules.sort(Comparator.comparingInt(SkitCommissionRuleDO::getLevelNo));
        return rules;
    }

    private List<SkitMemberClosureDO> validateAndSortChain(List<SkitMemberClosureDO> supplied,
                                                            Long tenantId, Long sourceMemberId) {
        if (supplied == null || supplied.isEmpty()) {
            throw invalidRule("会员邀请链缺少本人节点");
        }
        List<SkitMemberClosureDO> chain = new ArrayList<>(supplied);
        Set<Integer> distances = new HashSet<>();
        Set<Long> ancestors = new HashSet<>();
        for (SkitMemberClosureDO edge : chain) {
            if (edge == null || !tenantId.equals(edge.getTenantId())
                    || !sourceMemberId.equals(edge.getDescendantId())) {
                throw invalidRule("邀请链不属于当前租户会员");
            }
            if (edge.getAncestorId() == null || edge.getAncestorId() <= 0
                    || edge.getDistance() == null || edge.getDistance() < 0
                    || !distances.add(edge.getDistance()) || !ancestors.add(edge.getAncestorId())) {
                throw invalidRule("邀请链层级重复或不合法");
            }
        }
        chain.sort(Comparator.comparingInt(SkitMemberClosureDO::getDistance));
        for (int expected = 0; expected < chain.size(); expected++) {
            if (chain.get(expected).getDistance() != expected) {
                throw invalidRule("邀请链层级必须从 0 连续递增");
            }
        }
        if (!sourceMemberId.equals(chain.get(0).getAncestorId())) {
            throw invalidRule("level 0 必须是广告会员本人");
        }
        return chain;
    }

    private Map<Integer, LockedMember> readChainMembersForShare(List<SkitMemberClosureDO> chain,
                                                                Long tenantId, Long sourceMemberId) {
        List<Long> memberIds = new ArrayList<>();
        for (SkitMemberClosureDO edge : chain) {
            memberIds.add(edge.getAncestorId());
        }
        Collections.sort(memberIds);
        Set<Long> expectedMemberIds = new HashSet<>(memberIds);
        List<SkitMemberDO> lockedRows = memberMapper.selectByTenantAndIdsForShare(tenantId, memberIds);
        Map<Long, SkitMemberDO> memberById = new HashMap<>();
        if (lockedRows != null) {
            for (SkitMemberDO member : lockedRows) {
                if (member == null || member.getId() == null || !expectedMemberIds.contains(member.getId())
                        || memberById.put(member.getId(), member) != null) {
                    throw new IllegalStateException("Locked invitation members are inconsistent");
                }
            }
        }
        Map<Integer, LockedMember> result = new HashMap<>();
        for (SkitMemberClosureDO edge : chain) {
            Long expectedMemberId = edge.getAncestorId();
            SkitMemberDO member = memberById.get(expectedMemberId);
            result.put(edge.getDistance(), new LockedMember(expectedMemberId, member));
        }
        if (!result.containsKey(0) || !sourceMemberId.equals(result.get(0).expectedMemberId)) {
            throw invalidRule("邀请链缺少广告会员本人");
        }
        return result;
    }

    private void requireEnabledSource(LockedMember locked, Long tenantId, Long sourceMemberId) {
        if (locked == null || locked.member == null) {
            throw exception(MEMBER_NOT_EXISTS);
        }
        SkitMemberDO source = locked.member;
        if (!sourceMemberId.equals(source.getId()) || !tenantId.equals(source.getTenantId())) {
            throw new IllegalStateException("Locked source member escaped the tenant boundary");
        }
        if (CommonStatusEnum.DISABLE.getStatus().equals(source.getStatus())) {
            throw exception(MEMBER_DISABLED);
        }
        if (!CommonStatusEnum.ENABLE.getStatus().equals(source.getStatus())) {
            throw exception(MEMBER_STATUS_INVALID);
        }
    }

    private List<ChainNode> buildChain(List<SkitMemberClosureDO> chain,
                                       Map<Integer, LockedMember> lockedMembers) {
        List<ChainNode> result = new ArrayList<>();
        for (SkitMemberClosureDO edge : chain) {
            result.add(toChainNode(edge.getDistance(), edge.getAncestorId(),
                    lockedMembers.get(edge.getDistance())));
        }
        return result;
    }

    private ChainNode toChainNode(int level, Long expectedMemberId, LockedMember locked) {
        if (locked == null || locked.member == null) {
            return new ChainNode(level, expectedMemberId, null,
                    false, EligibilityReason.MISSING_ANCESTOR);
        }
        SkitMemberDO member = locked.member;
        if (!expectedMemberId.equals(member.getId())) {
            return new ChainNode(level, expectedMemberId, member.getStatus(),
                    false, EligibilityReason.OWNER_MISMATCH);
        }
        Long currentTenant = TenantContextHolder.getRequiredTenantId();
        if (!currentTenant.equals(member.getTenantId())) {
            return new ChainNode(level, expectedMemberId, member.getStatus(),
                    false, EligibilityReason.TENANT_MISMATCH);
        }
        if (CommonStatusEnum.ENABLE.getStatus().equals(member.getStatus())) {
            return new ChainNode(level, expectedMemberId, member.getStatus(),
                    true, EligibilityReason.ELIGIBLE);
        }
        if (CommonStatusEnum.DISABLE.getStatus().equals(member.getStatus())) {
            return new ChainNode(level, expectedMemberId, member.getStatus(),
                    false, EligibilityReason.DISABLED);
        }
        return new ChainNode(level, expectedMemberId, member.getStatus(),
                false, EligibilityReason.INVALID_STATUS);
    }

    private List<BeneficiarySlot> buildBeneficiaries(List<SkitCommissionRuleDO> rules,
                                                      List<ChainNode> chain) {
        Map<Integer, ChainNode> nodeByLevel = new HashMap<>();
        for (ChainNode node : chain) {
            nodeByLevel.put(node.getLevel(), node);
        }
        List<BeneficiarySlot> result = new ArrayList<>();
        for (SkitCommissionRuleDO rule : rules) {
            int level = rule.getLevelNo();
            ChainNode node = nodeByLevel.get(level);
            if (node == null) {
                result.add(new BeneficiarySlot(level, rule.getRateBps(), null, null,
                        false, EligibilityReason.MISSING_ANCESTOR));
                continue;
            }
            result.add(new BeneficiarySlot(level, rule.getRateBps(), node.getMemberId(),
                    node.getMemberStatus(), node.isEligible(), node.getReason()));
        }
        return result;
    }

    private int configuredRateBps(List<BeneficiarySlot> beneficiaries) {
        int result = 0;
        for (BeneficiarySlot slot : beneficiaries) {
            result += slot.getRateBps();
        }
        return result;
    }

    private int eligibleRateBps(List<BeneficiarySlot> beneficiaries) {
        int result = 0;
        for (BeneficiarySlot slot : beneficiaries) {
            if (slot.isEligible()) {
                result += slot.getRateBps();
            }
        }
        return result;
    }

    private void validatePersistedRowEnvelope(SkitAdPolicySnapshotDO row, Long tenantId, Long snapshotId) {
        if (!snapshotId.equals(row.getId()) || !tenantId.equals(row.getTenantId())
                || row.getPlanId() == null || row.getPlanId() <= 0
                || row.getSourceMemberId() == null || row.getSourceMemberId() <= 0
                || row.getRuleVersion() == null || row.getRuleVersion() <= 0
                || row.getSnapshotSchemaVersion() == null
                || row.getSnapshotSchemaVersion() != SNAPSHOT_SCHEMA_VERSION
                || row.getSnapshotJson() == null || row.getSnapshotJson().isEmpty()
                || row.getSnapshotHash() == null || row.getSnapshotHash().length != 32
                || row.getPolicySnapshotAt() == null || row.getPolicySnapshotAt().getNano() != 0) {
            throw new IllegalStateException("Policy snapshot row envelope is invalid");
        }
    }

    private ParsedSnapshot parseSnapshot(String json) {
        final JsonNode root;
        try {
            root = SNAPSHOT_READER.readTree(json);
        } catch (Exception parseFailure) {
            throw new IllegalStateException("Policy snapshot JSON cannot be parsed", parseFailure);
        }
        if (root == null || !root.isObject() || !fieldNames(root).equals(ROOT_FIELDS)) {
            throw new IllegalStateException("Policy snapshot JSON envelope is invalid");
        }
        int schemaVersion = requiredInt(root, "schemaVersion");
        if (schemaVersion != SNAPSHOT_SCHEMA_VERSION) {
            throw new IllegalStateException("Unknown policy snapshot schema version");
        }
        long tenantId = requiredLong(root, "tenantId");
        int tenantStatus = requiredInt(root, "tenantStatus");
        long planId = requiredLong(root, "planId");
        int ruleVersion = requiredInt(root, "ruleVersion");
        long sourceMemberId = requiredLong(root, "sourceMemberId");
        LocalDateTime policySnapshotAt = requiredSnapshotTime(root, "policySnapshotAt");
        List<ChainNode> chain = parseChain(root.get("chain"), sourceMemberId);
        List<BeneficiarySlot> beneficiaries = parseBeneficiaries(root.get("beneficiaries"), sourceMemberId);
        int configuredRateBps = requiredInt(root, "configuredRateBps");
        int eligibleRateBps = requiredInt(root, "eligibleRateBps");
        validateSnapshotDetail(chain, beneficiaries, configuredRateBps, eligibleRateBps);
        return new ParsedSnapshot(schemaVersion, tenantId, tenantStatus, planId, ruleVersion,
                sourceMemberId, policySnapshotAt, chain, beneficiaries,
                configuredRateBps, eligibleRateBps);
    }

    private List<ChainNode> parseChain(JsonNode node, long sourceMemberId) {
        if (node == null || !node.isArray() || node.size() == 0) {
            throw new IllegalStateException("Policy snapshot has no invitation chain");
        }
        List<ChainNode> result = new ArrayList<>();
        Set<Long> memberIds = new HashSet<>();
        int expectedLevel = 0;
        for (JsonNode item : node) {
            if (item == null || !item.isObject() || !fieldNames(item).equals(CHAIN_FIELDS)) {
                throw new IllegalStateException("Policy snapshot chain envelope is invalid");
            }
            int level = requiredInt(item, "level");
            Long memberId = nullablePositiveLong(item, "memberId");
            Integer memberStatus = nullableInt(item, "memberStatus");
            boolean eligible = requiredBoolean(item, "eligible");
            EligibilityReason reason = requiredReason(item, "reason");
            if (level != expectedLevel++ || memberId == null || !memberIds.add(memberId)) {
                throw new IllegalStateException("Policy snapshot invitation chain is not canonical");
            }
            validateEligibility(memberId, memberStatus, eligible, reason);
            result.add(new ChainNode(level, memberId, memberStatus, eligible, reason));
        }
        ChainNode viewer = result.get(0);
        if (!Long.valueOf(sourceMemberId).equals(viewer.getMemberId())
                || !viewer.isEligible() || viewer.getReason() != EligibilityReason.ELIGIBLE) {
            throw new IllegalStateException("Policy snapshot invitation chain viewer is invalid");
        }
        return result;
    }

    private List<BeneficiarySlot> parseBeneficiaries(JsonNode node, long sourceMemberId) {
        if (node == null || !node.isArray() || node.size() == 0) {
            throw new IllegalStateException("Policy snapshot has no beneficiary rules");
        }
        List<BeneficiarySlot> result = new ArrayList<>();
        Set<Integer> levels = new HashSet<>();
        long totalRate = 0L;
        int priorLevel = -1;
        for (JsonNode item : node) {
            if (item == null || !item.isObject() || !fieldNames(item).equals(BENEFICIARY_FIELDS)) {
                throw new IllegalStateException("Policy snapshot beneficiary envelope is invalid");
            }
            int level = requiredInt(item, "level");
            int rateBps = requiredInt(item, "rateBps");
            Long memberId = nullablePositiveLong(item, "memberId");
            Integer memberStatus = nullableInt(item, "memberStatus");
            boolean eligible = requiredBoolean(item, "eligible");
            EligibilityReason reason = requiredReason(item, "reason");
            if (level < 0 || level <= priorLevel || !levels.add(level)) {
                throw new IllegalStateException("Policy snapshot beneficiary levels are not canonical");
            }
            priorLevel = level;
            if (rateBps < 0 || rateBps > RATE_BASE) {
                throw new IllegalStateException("Policy snapshot beneficiary ratio is invalid");
            }
            totalRate += rateBps;
            if (totalRate > RATE_BASE) {
                throw new IllegalStateException("Policy snapshot beneficiary ratios exceed 100%");
            }
            validateEligibility(memberId, memberStatus, eligible, reason);
            result.add(new BeneficiarySlot(level, rateBps, memberId, memberStatus, eligible, reason));
        }
        BeneficiarySlot viewer = result.get(0);
        if (viewer.getLevel() != 0 || !Long.valueOf(sourceMemberId).equals(viewer.getMemberId())
                || !viewer.isEligible() || viewer.getReason() != EligibilityReason.ELIGIBLE) {
            throw new IllegalStateException("Policy snapshot viewer slot is invalid");
        }
        return result;
    }

    private void validateSnapshotDetail(List<ChainNode> chain, List<BeneficiarySlot> beneficiaries,
                                        int configuredRateBps, int eligibleRateBps) {
        int calculatedConfigured = configuredRateBps(beneficiaries);
        int calculatedEligible = eligibleRateBps(beneficiaries);
        if (configuredRateBps != calculatedConfigured || eligibleRateBps != calculatedEligible
                || configuredRateBps < 0 || configuredRateBps > RATE_BASE
                || eligibleRateBps < 0 || eligibleRateBps > configuredRateBps) {
            throw new IllegalStateException("Policy snapshot ratio totals are inconsistent");
        }
        Map<Integer, ChainNode> nodeByLevel = new HashMap<>();
        for (ChainNode node : chain) {
            nodeByLevel.put(node.getLevel(), node);
        }
        for (BeneficiarySlot slot : beneficiaries) {
            ChainNode node = nodeByLevel.get(slot.getLevel());
            if (node == null) {
                if (slot.getMemberId() != null || slot.getMemberStatus() != null || slot.isEligible()
                        || slot.getReason() != EligibilityReason.MISSING_ANCESTOR) {
                    throw new IllegalStateException("Policy snapshot missing-ancestor share is inconsistent");
                }
                continue;
            }
            if (!Objects.equals(node.getMemberId(), slot.getMemberId())
                    || !Objects.equals(node.getMemberStatus(), slot.getMemberStatus())
                    || node.isEligible() != slot.isEligible() || node.getReason() != slot.getReason()) {
                throw new IllegalStateException("Policy snapshot chain and shares disagree");
            }
        }
    }

    private void validateEligibility(Long memberId, Integer memberStatus, boolean eligible,
                                     EligibilityReason reason) {
        if (eligible) {
            if (reason != EligibilityReason.ELIGIBLE || memberId == null
                    || !CommonStatusEnum.ENABLE.getStatus().equals(memberStatus)) {
                throw new IllegalStateException("Eligible policy beneficiary is inconsistent");
            }
            return;
        }
        if (reason == EligibilityReason.ELIGIBLE) {
            throw new IllegalStateException("Ineligible policy beneficiary cannot be marked eligible");
        }
        if (reason == EligibilityReason.MISSING_ANCESTOR && memberStatus != null) {
            throw new IllegalStateException("Missing policy beneficiary cannot have a member status");
        }
        if (reason == EligibilityReason.DISABLED && (memberId == null
                || !CommonStatusEnum.DISABLE.getStatus().equals(memberStatus))) {
            throw new IllegalStateException("Disabled policy beneficiary is inconsistent");
        }
        if ((reason == EligibilityReason.INVALID_STATUS || reason == EligibilityReason.TENANT_MISMATCH
                || reason == EligibilityReason.OWNER_MISMATCH) && memberId == null) {
            throw new IllegalStateException("Invalid policy beneficiary must retain its member id");
        }
    }

    private void validateParsedEnvelope(SkitAdPolicySnapshotDO row, ParsedSnapshot parsed, Long tenantId) {
        if (parsed.schemaVersion != row.getSnapshotSchemaVersion()
                || parsed.tenantId != tenantId || parsed.tenantId != row.getTenantId()
                || parsed.tenantStatus != CommonStatusEnum.ENABLE.getStatus()
                || parsed.planId != row.getPlanId() || parsed.ruleVersion != row.getRuleVersion()
                || parsed.sourceMemberId != row.getSourceMemberId()
                || !parsed.policySnapshotAt.equals(row.getPolicySnapshotAt())) {
            throw new IllegalStateException("Policy snapshot row and JSON envelope do not match");
        }
    }

    private String canonicalJson(int schemaVersion, long tenantId, int tenantStatus,
                                 long planId, int ruleVersion, long sourceMemberId,
                                 LocalDateTime policySnapshotAt,
                                 List<ChainNode> chain, List<BeneficiarySlot> beneficiaries,
                                 int configuredRateBps, int eligibleRateBps) {
        StringBuilder json = new StringBuilder(256 + (chain.size() + beneficiaries.size()) * 128);
        json.append("{\"schemaVersion\":").append(schemaVersion)
                .append(",\"tenantId\":").append(tenantId)
                .append(",\"tenantStatus\":").append(tenantStatus)
                .append(",\"planId\":").append(planId)
                .append(",\"ruleVersion\":").append(ruleVersion)
                .append(",\"sourceMemberId\":").append(sourceMemberId)
                .append(",\"policySnapshotAt\":\"")
                .append(SNAPSHOT_TIME_FORMAT.format(policySnapshotAt))
                .append("\",\"chain\":[");
        for (int index = 0; index < chain.size(); index++) {
            ChainNode node = chain.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"level\":").append(node.getLevel())
                    .append(",\"memberId\":");
            appendNullable(json, node.getMemberId());
            json.append(",\"memberStatus\":");
            appendNullable(json, node.getMemberStatus());
            json.append(",\"eligible\":").append(node.isEligible())
                    .append(",\"reason\":\"").append(node.getReason().name()).append("\"}");
        }
        json.append("],\"beneficiaries\":[");
        for (int index = 0; index < beneficiaries.size(); index++) {
            BeneficiarySlot slot = beneficiaries.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"level\":").append(slot.getLevel())
                    .append(",\"rateBps\":").append(slot.getRateBps())
                    .append(",\"memberId\":");
            appendNullable(json, slot.getMemberId());
            json.append(",\"memberStatus\":");
            appendNullable(json, slot.getMemberStatus());
            json.append(",\"eligible\":").append(slot.isEligible())
                    .append(",\"reason\":\"").append(slot.getReason().name()).append("\"}");
        }
        return json.append("],\"configuredRateBps\":").append(configuredRateBps)
                .append(",\"eligibleRateBps\":").append(eligibleRateBps).append('}').toString();
    }

    private static void appendNullable(StringBuilder target, Number value) {
        if (value == null) {
            target.append("null");
        } else {
            target.append(value);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ObjectMapper snapshotReader() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new LinkedHashSet<>();
        Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            fields.add(iterator.next());
        }
        return fields;
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException("Policy snapshot field " + field + " is not an integer");
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalStateException("Policy snapshot field " + field + " is not a long");
        }
        long result = value.longValue();
        if (result <= 0) {
            throw new IllegalStateException("Policy snapshot field " + field + " must be positive");
        }
        return result;
    }

    private static Long nullablePositiveLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalStateException("Policy snapshot field " + field + " must be null or positive");
        }
        return value.longValue();
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException("Policy snapshot field " + field + " is not an integer");
        }
        return value.intValue();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException("Policy snapshot field " + field + " is not a boolean");
        }
        return value.booleanValue();
    }

    private static EligibilityReason requiredReason(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("Policy snapshot eligibility reason is invalid");
        }
        try {
            return EligibilityReason.valueOf(value.textValue());
        } catch (IllegalArgumentException unknownReason) {
            throw new IllegalStateException("Unknown policy snapshot eligibility reason", unknownReason);
        }
    }

    private static LocalDateTime requiredSnapshotTime(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("Policy snapshot time is invalid");
        }
        try {
            LocalDateTime result = LocalDateTime.parse(value.textValue(), SNAPSHOT_TIME_FORMAT);
            if (!SNAPSHOT_TIME_FORMAT.format(result).equals(value.textValue()) || result.getNano() != 0) {
                throw new IllegalStateException("Policy snapshot time is not canonical");
            }
            return result;
        } catch (DateTimeParseException parseFailure) {
            throw new IllegalStateException("Policy snapshot time is invalid", parseFailure);
        }
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be greater than 0");
        }
    }

    private static RuntimeException invalidRule(String detail) {
        return exception(COMMISSION_RULE_INVALID, detail);
    }

    private static final class LockedMember {
        private final Long expectedMemberId;
        private final SkitMemberDO member;

        private LockedMember(Long expectedMemberId, SkitMemberDO member) {
            this.expectedMemberId = expectedMemberId;
            this.member = member;
        }
    }

    private static final class ParsedSnapshot {
        private final int schemaVersion;
        private final long tenantId;
        private final int tenantStatus;
        private final long planId;
        private final int ruleVersion;
        private final long sourceMemberId;
        private final LocalDateTime policySnapshotAt;
        private final List<ChainNode> chain;
        private final List<BeneficiarySlot> beneficiaries;
        private final int configuredRateBps;
        private final int eligibleRateBps;

        private ParsedSnapshot(int schemaVersion, long tenantId, int tenantStatus,
                               long planId, int ruleVersion, long sourceMemberId,
                               LocalDateTime policySnapshotAt,
                               List<ChainNode> chain, List<BeneficiarySlot> beneficiaries,
                               int configuredRateBps, int eligibleRateBps) {
            this.schemaVersion = schemaVersion;
            this.tenantId = tenantId;
            this.tenantStatus = tenantStatus;
            this.planId = planId;
            this.ruleVersion = ruleVersion;
            this.sourceMemberId = sourceMemberId;
            this.policySnapshotAt = policySnapshotAt;
            this.chain = chain;
            this.beneficiaries = beneficiaries;
            this.configuredRateBps = configuredRateBps;
            this.eligibleRateBps = eligibleRateBps;
        }
    }

}
