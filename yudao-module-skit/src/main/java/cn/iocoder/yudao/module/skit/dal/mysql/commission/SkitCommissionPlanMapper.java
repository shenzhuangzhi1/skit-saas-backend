package cn.iocoder.yudao.module.skit.dal.mysql.commission;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionPlanDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.COMMISSION_PLAN_ACTIVE;

@Mapper
public interface SkitCommissionPlanMapper extends BaseMapperX<SkitCommissionPlanDO> {

    default SkitCommissionPlanDO selectActive() {
        return selectOne(new LambdaQueryWrapperX<SkitCommissionPlanDO>()
                .eq(SkitCommissionPlanDO::getStatus, COMMISSION_PLAN_ACTIVE)
                .orderByDesc(SkitCommissionPlanDO::getVersion)
                .last("LIMIT 1"));
    }

    @Select("SELECT * FROM `skit_commission_plan` WHERE `tenant_id`=#{tenantId} "
            + "AND `status`=0 AND `deleted`=b'0' FOR UPDATE")
    SkitCommissionPlanDO selectActiveForUpdate(@Param("tenantId") Long tenantId);

    default SkitCommissionPlanDO selectLatest() {
        return selectOne(new LambdaQueryWrapperX<SkitCommissionPlanDO>()
                .orderByDesc(SkitCommissionPlanDO::getVersion)
                .last("LIMIT 1"));
    }

}
