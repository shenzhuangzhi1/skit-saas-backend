package cn.iocoder.yudao.module.skit.dal.mysql.commission;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionPlanDO;
import org.apache.ibatis.annotations.Mapper;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.COMMISSION_PLAN_ACTIVE;

@Mapper
public interface SkitCommissionPlanMapper extends BaseMapperX<SkitCommissionPlanDO> {

    default SkitCommissionPlanDO selectActive() {
        return selectOne(new LambdaQueryWrapperX<SkitCommissionPlanDO>()
                .eq(SkitCommissionPlanDO::getStatus, COMMISSION_PLAN_ACTIVE)
                .orderByDesc(SkitCommissionPlanDO::getVersion)
                .last("LIMIT 1"));
    }

    default SkitCommissionPlanDO selectLatest() {
        return selectOne(new LambdaQueryWrapperX<SkitCommissionPlanDO>()
                .orderByDesc(SkitCommissionPlanDO::getVersion)
                .last("LIMIT 1"));
    }

}
