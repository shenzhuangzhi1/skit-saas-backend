package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Mapper
public interface SkitAdAccountMapper extends BaseMapperX<SkitAdAccountDO> {

    default SkitAdAccountDO selectByProvider(String provider) {
        return selectOne(SkitAdAccountDO::getProvider, provider);
    }

    default List<SkitAdAccountDO> selectListByTenantIds(Collection<Long> tenantIds) {
        if (CollUtil.isEmpty(tenantIds)) {
            return Collections.emptyList();
        }
        return selectList(new LambdaQueryWrapperX<SkitAdAccountDO>()
                .in(SkitAdAccountDO::getTenantId, tenantIds)
                .orderByAsc(SkitAdAccountDO::getTenantId)
                .orderByAsc(SkitAdAccountDO::getId));
    }

}
