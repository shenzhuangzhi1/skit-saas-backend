package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPlanPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPlanRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPreviewReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPreviewRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPublishReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionRuleVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionPlanDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionRuleDO;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionPlanMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionRuleMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScope;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementCommandType;
import cn.iocoder.yudao.module.skit.service.management.SkitManagementCommandExecutor;
import cn.iocoder.yudao.module.skit.service.management.SkitManagementTimezone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.COMMISSION_PLAN_VERSION_CONFLICT;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_RESOURCE_NOT_FOUND;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.COMMISSION_PLAN_ACTIVE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.COMMISSION_PLAN_ARCHIVED;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_DEFAULT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.RATE_BASE;

@Service
public class SkitCommissionManagementServiceImpl implements SkitCommissionManagementService {

    private static final ZoneOffset DATABASE_OFFSET = ZoneOffset.ofHours(8);

    private final SkitCommissionPlanMapper planMapper;
    private final SkitCommissionRuleMapper ruleMapper;
    private final SkitCommissionPreviewAllocator allocator;
    private final SkitManagementCommandExecutor commandExecutor;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public SkitCommissionManagementServiceImpl(SkitCommissionPlanMapper planMapper,
                                                SkitCommissionRuleMapper ruleMapper,
                                                SkitCommissionPreviewAllocator allocator,
                                                SkitManagementCommandExecutor commandExecutor,
                                                JdbcTemplate jdbcTemplate) {
        this(planMapper, ruleMapper, allocator, commandExecutor, jdbcTemplate,
                Clock.systemUTC());
    }

    SkitCommissionManagementServiceImpl(SkitCommissionPlanMapper planMapper,
                                         SkitCommissionRuleMapper ruleMapper,
                                         SkitCommissionPreviewAllocator allocator,
                                         SkitManagementCommandExecutor commandExecutor,
                                         JdbcTemplate jdbcTemplate, Clock clock) {
        this.planMapper = Objects.requireNonNull(planMapper, "planMapper");
        this.ruleMapper = Objects.requireNonNull(ruleMapper, "ruleMapper");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(readOnly = true)
    public SkitCommissionPlanRespVO getCurrent(long tenantId, String requestedTimezone) {
        requireTenant(tenantId);
        SkitManagementTimezone timezone = SkitManagementTimezone.of(requestedTimezone);
        LocalDateTime asOf = timezone.now(clock).withNano(0);
        SkitCommissionPlanDO plan = planMapper.selectActiveForShare(tenantId);
        if (plan == null) {
            return unconfigured(tenantId, asOf, timezone.getName());
        }
        validatePlanEnvelope(plan, tenantId);
        return plan(plan, readRules(tenantId, plan.getId()), asOf, timezone);
    }

    @Override
    @Transactional(readOnly = true)
    public SkitStablePageRespVO<SkitCommissionPlanRespVO> getHistory(
            long tenantId, SkitCommissionPlanPageReqVO query) {
        requireTenant(tenantId);
        Objects.requireNonNull(query, "query");
        SkitManagementTimezone timezone = SkitManagementTimezone.of(query.getTimezone());
        LocalDateTime now = timezone.now(clock).withNano(0);
        LocalDateTime asOf = query.getAsOf() == null || query.getAsOf().isAfter(now)
                ? now : query.getAsOf().withNano(0);
        LocalDateTime databaseAsOf = timezone.toDatabase(asOf);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `skit_commission_plan` WHERE `tenant_id`=? "
                        + "AND `published_time`<=? AND `deleted`=b'0'",
                Long.class, tenantId, databaseAsOf);
        List<SkitCommissionPlanDO> plans = jdbcTemplate.query(
                "SELECT `id`,`tenant_id`,`version`,`status`,`published_time` "
                        + "FROM `skit_commission_plan` WHERE `tenant_id`=? "
                        + "AND `published_time`<=? AND `deleted`=b'0' "
                        + "ORDER BY `version` DESC,`id` DESC LIMIT ? OFFSET ?",
                new Object[]{tenantId, databaseAsOf, query.getPageSize(),
                        Math.multiplyExact((long) query.getPageNo() - 1L,
                                query.getPageSize())},
                (rs, rowNum) -> {
                    SkitCommissionPlanDO item = SkitCommissionPlanDO.builder()
                            .id(rs.getLong("id")).version(rs.getInt("version"))
                            .status(rs.getInt("status"))
                            .publishedTime(rs.getTimestamp("published_time").toLocalDateTime())
                            .build();
                    item.setTenantId(rs.getLong("tenant_id"));
                    return item;
                });
        List<SkitCommissionPlanRespVO> rows = new ArrayList<>(plans.size());
        for (SkitCommissionPlanDO item : plans) {
            validatePlanEnvelope(item, tenantId);
            rows.add(plan(item, readRules(tenantId, item.getId()), asOf, timezone));
        }
        SkitStablePageRespVO<SkitCommissionPlanRespVO> response = new SkitStablePageRespVO<>();
        response.setTenantId(tenantId);
        response.setAsOf(asOf);
        response.setTimezone(timezone.getName());
        response.setPageNo(query.getPageNo());
        response.setPageSize(query.getPageSize());
        response.setTotal(total == null ? 0L : total);
        response.setList(rows);
        return response;
    }

    @Override
    public SkitCommissionPreviewRespVO preview(long tenantId,
                                                SkitCommissionPreviewReqVO request) {
        requireTenant(tenantId);
        Objects.requireNonNull(request, "request");
        requireCurrency(request.getCurrency());
        SkitManagementTimezone timezone = SkitManagementTimezone.of(request.getTimezone());
        SkitCommissionPreviewAllocator.Result result = allocator.allocate(
                Objects.requireNonNull(request.getAmountUnits(), "amountUnits"), request.getRules());
        int scale = Objects.requireNonNull(request.getAmountScale(), "amountScale");
        if (scale < 0 || scale > 18) {
            throw new IllegalArgumentException("amountScale must be between 0 and 18");
        }
        SkitCommissionPreviewRespVO response = new SkitCommissionPreviewRespVO();
        response.setTenantId(tenantId);
        response.setAsOf(timezone.now(clock).withNano(0));
        response.setTimezone(timezone.getName());
        response.setCurrency(request.getCurrency());
        response.setAmountScale(scale);
        response.setGrossAmount(money(request.getAmountUnits(), scale));
        response.setGrossAmountUnits(Long.toString(request.getAmountUnits()));
        response.setTotalMemberRateBps(result.getTotalMemberRateBps());
        response.setMemberTotal(money(result.getMemberTotalUnits(), scale));
        response.setMemberTotalUnits(Long.toString(result.getMemberTotalUnits()));
        response.setAgentRateBps(result.getAgentRateBps());
        response.setAgentAmount(money(result.getAgentAmountUnits(), scale));
        response.setAgentAmountUnits(Long.toString(result.getAgentAmountUnits()));
        List<SkitCommissionPreviewRespVO.Allocation> allocations = new ArrayList<>();
        for (SkitCommissionPreviewAllocator.Allocation item : result.getAllocations()) {
            allocations.add(new SkitCommissionPreviewRespVO.Allocation()
                    .setLevelNo(item.getLevelNo()).setRateBps(item.getRateBps())
                    .setAmount(money(item.getAmountUnits(), scale))
                    .setAmountUnits(Long.toString(item.getAmountUnits())));
        }
        response.setAllocations(allocations);
        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkitCommissionPlanRespVO publish(SkitAdminTenantScope scope,
                                            SkitCommissionPublishReqVO request) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(request, "request");
        long tenantId = scope.getTargetTenantId();
        requireTenant(tenantId);
        List<SkitCommissionRuleVO> normalized = allocator.normalize(request.getRules());
        lockTenantAgent(tenantId);
        SkitCommissionPlanDO active = planMapper.selectActiveForUpdate(tenantId);
        if (active != null) {
            validatePlanEnvelope(active, tenantId);
        }
        int currentVersion = active == null ? 0 : active.getVersion();
        if (!Integer.valueOf(currentVersion).equals(request.getExpectedVersion())) {
            throw exception(COMMISSION_PLAN_VERSION_CONFLICT);
        }
        SkitManagementTimezone timezone = SkitManagementTimezone.of(MANAGEMENT_TIMEZONE_DEFAULT);
        LocalDateTime asOf = timezone.now(clock).withNano(0);
        SkitCommissionPlanRespVO before = active == null
                ? unconfigured(tenantId, asOf, timezone.getName())
                : plan(active, readRules(tenantId, active.getId()), asOf, timezone);
        String resourceId = "tenant:" + tenantId;
        return commandExecutor.execute(scope, SkitManagementCommandType.COMMISSION_PLAN_PUBLISH,
                "COMMISSION_PLAN_SET", resourceId, request.getReason(), canonical(before),
                requestCanonical(request.getExpectedVersion(), normalized), () -> {
                    if (active != null) {
                        int archived = jdbcTemplate.update(
                                "UPDATE `skit_commission_plan` SET `status`=?,`updater`=?,"
                                        + "`update_time`=? WHERE `tenant_id`=? AND `id`=? "
                                        + "AND `status`=? AND `deleted`=b'0'",
                                COMMISSION_PLAN_ARCHIVED, "user:" + scope.getOperatorUserId(),
                                databaseNow(), tenantId, active.getId(), COMMISSION_PLAN_ACTIVE);
                        if (archived != 1) {
                            throw exception(COMMISSION_PLAN_VERSION_CONFLICT);
                        }
                    }
                    Integer latestVersion = jdbcTemplate.queryForObject(
                            "SELECT COALESCE(MAX(`version`),0) FROM `skit_commission_plan` "
                                    + "WHERE `tenant_id`=? AND `deleted`=b'0'",
                            Integer.class, tenantId);
                    int nextVersion = Math.addExact(latestVersion == null ? 0 : latestVersion, 1);
                    SkitCommissionPlanDO created = SkitCommissionPlanDO.builder()
                            .version(nextVersion).status(COMMISSION_PLAN_ACTIVE)
                            .publishedTime(databaseNow()).build();
                    created.setTenantId(tenantId);
                    if (planMapper.insert(created) != 1 || created.getId() == null) {
                        throw new IllegalStateException("Commission plan was not inserted exactly once");
                    }
                    List<SkitCommissionRuleDO> entities = new ArrayList<>(normalized.size());
                    for (SkitCommissionRuleVO item : normalized) {
                        SkitCommissionRuleDO entity = SkitCommissionRuleDO.builder()
                                .planId(created.getId()).levelNo(item.getLevelNo())
                                .rateBps(item.getRateBps()).build();
                        entity.setTenantId(tenantId);
                        entities.add(entity);
                    }
                    if (!entities.isEmpty() && !ruleMapper.insertBatch(entities)) {
                        throw new IllegalStateException("Commission rules were not inserted atomically");
                    }
                    SkitCommissionPlanRespVO after = plan(created, normalized, asOf, timezone);
                    return new SkitManagementCommandExecutor.CommandResult<>(after, canonical(after));
                });
    }

    private void lockTenantAgent(long tenantId) {
        try {
            Long id = jdbcTemplate.queryForObject(
                    "SELECT `id` FROM `skit_agent` WHERE `tenant_id`=? "
                            + "AND `deleted`=b'0' FOR UPDATE", Long.class, tenantId);
            if (id == null || id <= 0L) {
                throw exception(MANAGEMENT_RESOURCE_NOT_FOUND);
            }
        } catch (EmptyResultDataAccessException notFound) {
            throw exception(MANAGEMENT_RESOURCE_NOT_FOUND);
        }
    }

    private List<SkitCommissionRuleVO> readRules(long tenantId, long planId) {
        List<SkitCommissionRuleDO> rows = ruleMapper.selectListByPlanIdForShare(tenantId, planId);
        if (rows == null) return Collections.emptyList();
        List<SkitCommissionRuleVO> rules = new ArrayList<>(rows.size());
        for (SkitCommissionRuleDO row : rows) {
            if (row == null || !Long.valueOf(tenantId).equals(row.getTenantId())
                    || !Long.valueOf(planId).equals(row.getPlanId())) {
                throw new IllegalStateException("Commission rule escaped tenant-plan boundary");
            }
            rules.add(new SkitCommissionRuleVO().setLevelNo(row.getLevelNo())
                    .setRateBps(row.getRateBps()));
        }
        return allocator.normalize(rules);
    }

    private SkitCommissionPlanRespVO plan(SkitCommissionPlanDO source,
                                           List<SkitCommissionRuleVO> suppliedRules,
                                           LocalDateTime asOf,
                                           SkitManagementTimezone timezone) {
        List<SkitCommissionRuleVO> rules = allocator.normalize(suppliedRules);
        int totalRate = 0;
        for (SkitCommissionRuleVO rule : rules) totalRate += rule.getRateBps();
        SkitCommissionPlanRespVO result = new SkitCommissionPlanRespVO();
        result.setTenantId(source.getTenantId());
        result.setAsOf(asOf);
        result.setTimezone(timezone.getName());
        result.setId(source.getId());
        result.setVersion(source.getVersion());
        result.setStatus(status(source.getStatus()));
        result.setPublishedAt(timezone.fromDatabase(source.getPublishedTime()));
        result.setTotalMemberRateBps(totalRate);
        result.setAgentRateBps(RATE_BASE - totalRate);
        result.setRules(new ArrayList<>(rules));
        return result;
    }

    private SkitCommissionPlanRespVO unconfigured(long tenantId, LocalDateTime asOf,
                                                   String timezone) {
        return new SkitCommissionPlanRespVO().setTenantId(tenantId).setAsOf(asOf)
                .setTimezone(timezone).setVersion(0).setStatus("UNCONFIGURED")
                .setTotalMemberRateBps(0).setAgentRateBps(RATE_BASE)
                .setRules(Collections.emptyList());
    }

    private String status(Integer value) {
        if (Integer.valueOf(COMMISSION_PLAN_ACTIVE).equals(value)) return "ACTIVE";
        if (Integer.valueOf(COMMISSION_PLAN_ARCHIVED).equals(value)) return "ARCHIVED";
        throw new IllegalStateException("Unknown commission plan status");
    }

    private void validatePlanEnvelope(SkitCommissionPlanDO plan, long tenantId) {
        if (plan.getId() == null || plan.getId() <= 0L
                || !Long.valueOf(tenantId).equals(plan.getTenantId())
                || plan.getVersion() == null || plan.getVersion() <= 0
                || plan.getPublishedTime() == null) {
            throw new IllegalStateException("Commission plan escaped tenant boundary");
        }
        status(plan.getStatus());
    }

    private String requestCanonical(int expectedVersion, List<SkitCommissionRuleVO> rules) {
        StringBuilder value = new StringBuilder("expectedVersion=").append(expectedVersion);
        for (SkitCommissionRuleVO rule : rules) {
            value.append(";level=").append(rule.getLevelNo())
                    .append(",rateBps=").append(rule.getRateBps());
        }
        return value.toString();
    }

    private String canonical(SkitCommissionPlanRespVO plan) {
        return "tenant=" + plan.getTenantId() + ";id=" + plan.getId()
                + ";version=" + plan.getVersion() + ";status=" + plan.getStatus()
                + ";" + requestCanonical(plan.getVersion(), plan.getRules());
    }

    private LocalDateTime databaseNow() {
        return LocalDateTime.ofInstant(clock.instant(), DATABASE_OFFSET).withNano(0);
    }

    private String money(long units, int scale) {
        BigDecimal value = BigDecimal.valueOf(units, scale).stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    private void requireTenant(long tenantId) {
        if (tenantId <= 0L) throw new IllegalArgumentException("tenantId must be positive");
    }

    private void requireCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO 4217 uppercase code");
        }
    }
}
