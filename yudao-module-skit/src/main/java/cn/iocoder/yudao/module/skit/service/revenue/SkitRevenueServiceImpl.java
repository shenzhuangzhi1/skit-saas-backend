package cn.iocoder.yudao.module.skit.service.revenue;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitCommissionLedgerDO;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitCommissionLedgerMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_MEMBER;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.LEDGER_ESTIMATED;

@Service
public class SkitRevenueServiceImpl implements SkitRevenueService {

    @Resource
    private SkitAdRevenueEventMapper eventMapper;
    @Resource
    private SkitCommissionLedgerMapper ledgerMapper;
    @Resource
    private SkitMemberMapper memberMapper;

    @Override
    public PageResult<LedgerView> getLedgerPage(PageParam pageParam, Long beneficiaryUserId, Integer beneficiaryType,
                                                 LocalDateTime[] createTime) {
        PageResult<SkitCommissionLedgerDO> page = ledgerMapper.selectPage(
                pageParam, beneficiaryUserId, beneficiaryType, createTime);
        List<LedgerView> views = new ArrayList<>();
        for (SkitCommissionLedgerDO ledger : page.getList()) {
            LedgerView view = new LedgerView();
            view.setId(ledger.getId());
            view.setEventId(ledger.getEventId());
            view.setAdRecordId(ledger.getEventId());
            SkitAdRevenueEventDO event = eventMapper.selectById(ledger.getEventId());
            if (event != null) {
                view.setSourceMemberId(event.getSourceMemberId());
                view.setSourceUserId(event.getSourceMemberId());
                SkitMemberDO source = memberMapper.selectById(event.getSourceMemberId());
                view.setSourceNickname(source == null ? null : source.getNickname());
                view.setSourceMemberName(source == null ? null : source.getNickname());
            }
            view.setBeneficiaryType(ledger.getBeneficiaryType());
            view.setBeneficiaryUserId(ledger.getBeneficiaryMemberId());
            if (BENEFICIARY_MEMBER == ledger.getBeneficiaryType()) {
                SkitMemberDO member = memberMapper.selectById(ledger.getBeneficiaryMemberId());
                view.setBeneficiaryNickname(member == null ? null : member.getNickname());
            }
            view.setLevel(ledger.getLevelNo());
            view.setRevenueAmount(ledger.getGrossAmount());
            view.setRate(BigDecimal.valueOf(ledger.getRateBps(), 2));
            view.setCommissionAmount(ledger.getAmount());
            view.setRuleVersion(ledger.getRuleVersion());
            view.setStatus(ledger.getStatus() == LEDGER_ESTIMATED ? "ESTIMATED" : "AVAILABLE");
            view.setCreateTime(ledger.getCreateTime());
            views.add(view);
        }
        return new PageResult<>(views, page.getTotal());
    }

}
