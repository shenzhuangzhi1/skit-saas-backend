package cn.iocoder.yudao.module.skit.dal.mysql.commission;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionRuleDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkitCommissionRuleMapper extends BaseMapperX<SkitCommissionRuleDO> {

    default List<SkitCommissionRuleDO> selectListByPlanId(Long planId) {
        return selectList(new LambdaQueryWrapperX<SkitCommissionRuleDO>()
                .eq(SkitCommissionRuleDO::getPlanId, planId)
                .orderByAsc(SkitCommissionRuleDO::getLevelNo));
    }

    @Select("SELECT * FROM `skit_commission_rule` WHERE `tenant_id`=#{tenantId} "
            + "AND `plan_id`=#{planId} AND `deleted`=b'0' FOR SHARE")
    List<SkitCommissionRuleDO> selectListByPlanIdForShare(@Param("tenantId") Long tenantId,
                                                          @Param("planId") Long planId);

}
