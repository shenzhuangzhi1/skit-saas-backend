package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
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

    /** Clears only the encrypted Pangle secret and disables the provider, preserving public metadata. */
    default int clearPangleCredentials() {
        return update(new SkitAdAccountDO(), new LambdaUpdateWrapper<SkitAdAccountDO>()
                .eq(SkitAdAccountDO::getProvider, "PANGLE")
                .set(SkitAdAccountDO::getSecret, null)
                .set(SkitAdAccountDO::getStatus, CommonStatusEnum.DISABLE.getStatus()));
    }

    /** Clears both encrypted Taku credentials and disables the provider, preserving public metadata. */
    default int clearTakuCredentials() {
        return update(new SkitAdAccountDO(), new LambdaUpdateWrapper<SkitAdAccountDO>()
                .eq(SkitAdAccountDO::getProvider, "TAKU")
                .set(SkitAdAccountDO::getAppKey, null)
                .set(SkitAdAccountDO::getSecret, null)
                .set(SkitAdAccountDO::getStatus, CommonStatusEnum.DISABLE.getStatus()));
    }

}
