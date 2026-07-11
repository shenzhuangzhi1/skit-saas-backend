package cn.iocoder.yudao.module.skit.dal.mysql.revenue;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkitAdRevenueEventMapper extends BaseMapperX<SkitAdRevenueEventDO> {

    default SkitAdRevenueEventDO selectByProviderAndExternalEventId(String provider, String externalEventId) {
        return selectOne(new LambdaQueryWrapperX<SkitAdRevenueEventDO>()
                .eq(SkitAdRevenueEventDO::getProvider, provider)
                .eq(SkitAdRevenueEventDO::getExternalEventId, externalEventId));
    }

}
