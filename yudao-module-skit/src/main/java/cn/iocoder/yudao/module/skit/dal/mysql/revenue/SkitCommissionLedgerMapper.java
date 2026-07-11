package cn.iocoder.yudao.module.skit.dal.mysql.revenue;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitCommissionLedgerDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface SkitCommissionLedgerMapper extends BaseMapperX<SkitCommissionLedgerDO> {

    default PageResult<SkitCommissionLedgerDO> selectPage(PageParam pageParam, Long memberId, Integer beneficiaryType,
                                                           LocalDateTime[] createTime) {
        return selectPage(pageParam, new LambdaQueryWrapperX<SkitCommissionLedgerDO>()
                .eqIfPresent(SkitCommissionLedgerDO::getBeneficiaryMemberId, memberId)
                .eqIfPresent(SkitCommissionLedgerDO::getBeneficiaryType, beneficiaryType)
                .betweenIfPresent(SkitCommissionLedgerDO::getCreateTime, createTime)
                .orderByDesc(SkitCommissionLedgerDO::getId));
    }

}
