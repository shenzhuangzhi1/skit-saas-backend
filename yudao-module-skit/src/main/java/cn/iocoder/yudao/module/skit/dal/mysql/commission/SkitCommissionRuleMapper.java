package cn.iocoder.yudao.module.skit.dal.mysql.commission;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionRuleDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SkitCommissionRuleMapper extends BaseMapperX<SkitCommissionRuleDO> {

    default List<SkitCommissionRuleDO> selectListByPlanId(Long planId) {
        return selectList(new LambdaQueryWrapperX<SkitCommissionRuleDO>()
                .eq(SkitCommissionRuleDO::getPlanId, planId)
                .orderByAsc(SkitCommissionRuleDO::getLevelNo));
    }

}
