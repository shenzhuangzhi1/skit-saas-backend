package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberPointRecordPageReqVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberPointRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberPointRecordMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.CHECK_IN_ALREADY_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_DISABLED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_NOT_EXISTS;

@Service
@Validated
public class SkitMemberPointServiceImpl implements SkitMemberPointService {

    static final String CHECK_IN_BIZ_TYPE = "CHECK_IN";
    private static final int CHECK_IN_REWARD = 1;
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    @Resource
    private SkitMemberMapper memberMapper;
    @Resource
    private SkitMemberPointRecordMapper pointRecordMapper;
    private Clock clock = Clock.system(BEIJING_ZONE);

    public SkitMemberPointServiceImpl() {
    }

    SkitMemberPointServiceImpl(
            SkitMemberMapper memberMapper,
            SkitMemberPointRecordMapper pointRecordMapper,
            Clock clock) {
        this.memberMapper = memberMapper;
        this.pointRecordMapper = pointRecordMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CheckInResult checkIn(Long memberId) {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitMemberDO member = requireEnabledMember(
                memberMapper.selectByTenantAndIdForUpdate(tenantId, memberId));
        LocalDate today = currentBeijingDate();
        String businessId = today.toString();
        if (pointRecordMapper.selectByBusiness(
                tenantId, memberId, CHECK_IN_BIZ_TYPE, businessId) != null) {
            throw exception(CHECK_IN_ALREADY_EXISTS);
        }

        int newBalance = defaultBalance(member.getPointBalance()) + CHECK_IN_REWARD;
        SkitMemberPointRecordDO record = SkitMemberPointRecordDO.builder()
                .memberId(memberId)
                .bizType(CHECK_IN_BIZ_TYPE)
                .bizId(businessId)
                .title("签到")
                .description("签到获得 1 积分")
                .pointDelta(CHECK_IN_REWARD)
                .balanceAfter(newBalance)
                .build();
        record.setTenantId(tenantId);
        try {
            if (pointRecordMapper.insert(record) != 1) {
                throw new IllegalStateException("Member point record insert did not affect one row");
            }
        } catch (DuplicateKeyException duplicate) {
            throw exception(CHECK_IN_ALREADY_EXISTS);
        }
        if (memberMapper.updatePointBalance(tenantId, memberId, newBalance) != 1) {
            throw new IllegalStateException("Member point balance update did not affect one row");
        }

        CheckInSummary summary = buildSummary(tenantId, memberId, today, newBalance);
        CheckInResult result = new CheckInResult();
        result.setSignInDate(today);
        result.setAwardedPoints(CHECK_IN_REWARD);
        result.setPointBalance(newBalance);
        result.setContinuousDay(summary.getContinuousDay());
        result.setTotalDay(summary.getTotalDay());
        return result;
    }

    @Override
    public CheckInSummary getCheckInSummary(Long memberId) {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        SkitMemberDO member = requireEnabledMember(
                memberMapper.selectByTenantAndId(tenantId, memberId));
        return buildSummary(
                tenantId, memberId, currentBeijingDate(), defaultBalance(member.getPointBalance()));
    }

    @Override
    public PageResult<SkitMemberPointRecordDO> getPointRecordPage(
            Long memberId, SkitMemberPointRecordPageReqVO request) {
        return pointRecordMapper.selectPage(memberId, request);
    }

    private CheckInSummary buildSummary(
            Long tenantId, Long memberId, LocalDate today, int pointBalance) {
        Long total = pointRecordMapper.selectCountByBizType(
                tenantId, memberId, CHECK_IN_BIZ_TYPE);
        List<String> businessIds = pointRecordMapper.selectBizIdsByType(
                tenantId, memberId, CHECK_IN_BIZ_TYPE);
        Set<String> checkInDates = new HashSet<>(
                businessIds == null ? Collections.emptyList() : businessIds);
        boolean todaySignIn = checkInDates.contains(today.toString());

        LocalDate cursor = todaySignIn ? today : today.minusDays(1);
        int continuousDay = 0;
        while (checkInDates.contains(cursor.toString())) {
            continuousDay++;
            cursor = cursor.minusDays(1);
        }

        CheckInSummary result = new CheckInSummary();
        result.setTodaySignIn(todaySignIn);
        result.setContinuousDay(continuousDay);
        result.setTotalDay(Math.toIntExact(total == null ? 0L : total));
        result.setPointBalance(pointBalance);
        return result;
    }

    private LocalDate currentBeijingDate() {
        return LocalDate.now(clock.withZone(BEIJING_ZONE));
    }

    private static int defaultBalance(Integer balance) {
        return balance == null ? 0 : balance;
    }

    private static SkitMemberDO requireEnabledMember(SkitMemberDO member) {
        if (member == null) {
            throw exception(MEMBER_NOT_EXISTS);
        }
        if (CommonStatusEnum.isDisable(member.getStatus())) {
            throw exception(MEMBER_DISABLED);
        }
        return member;
    }

}
