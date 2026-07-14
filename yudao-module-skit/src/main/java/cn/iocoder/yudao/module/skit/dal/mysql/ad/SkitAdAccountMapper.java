package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Mapper
public interface SkitAdAccountMapper extends BaseMapperX<SkitAdAccountDO> {

    @Select("SELECT `id` FROM `skit_ad_account` WHERE `tenant_id`=#{tenantId} AND `id`=#{id} "
            + "AND `deleted`=b'0' FOR UPDATE")
    Long lockByTenantAndId(@Param("tenantId") Long tenantId, @Param("id") Long id);

    @Select("SELECT `id`,`tenant_id`,`provider`,`app_id`,`config_data`,`status` "
            + "FROM `skit_ad_account` WHERE `tenant_id`=#{tenantId} "
            + "AND `provider`='TAKU' AND `status`=0 AND `deleted`=b'0' FOR UPDATE")
    List<SkitAdAccountDO> selectEnabledTakuForUpdate(@Param("tenantId") Long tenantId);

    @Select("SELECT `id`,`tenant_id`,`provider`,`app_id`,`config_data`,`status` "
            + "FROM `skit_ad_account` WHERE `tenant_id`=#{tenantId} "
            + "AND `provider`='TAKU' AND `status`=0 AND `deleted`=b'0' FOR SHARE")
    List<SkitAdAccountDO> selectEnabledTakuForShare(@Param("tenantId") Long tenantId);

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
