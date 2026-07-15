package cn.iocoder.yudao.module.skit.dal.mysql.app;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkitAppReleaseProfileMapper extends BaseMapperX<SkitAppReleaseProfileDO> {

    default SkitAppReleaseProfileDO selectByProfileCode(String profileCode) {
        return selectOne(SkitAppReleaseProfileDO::getProfileCode, profileCode);
    }

    default SkitAppReleaseProfileDO selectByTenantId(Long tenantId) {
        return selectOne(SkitAppReleaseProfileDO::getTenantId, tenantId);
    }

    @Select("SELECT * FROM `skit_app_release_profile` WHERE `tenant_id`=#{tenantId} "
            + "AND `deleted`=b'0' FOR UPDATE")
    SkitAppReleaseProfileDO selectByTenantIdForUpdate(@Param("tenantId") Long tenantId);

    @Update("UPDATE `skit_app_release_profile` SET `min_native_version`=#{minNativeVersion},"
            + "`updater`='ad-rollout',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `channel`='production' "
            + "AND `status`=0 AND `native_protocol_version`>=1 AND `deleted`=b'0'")
    int updateMinNativeVersionForRollout(@Param("tenantId") Long tenantId,
                                         @Param("id") Long id,
                                         @Param("minNativeVersion") String minNativeVersion);

}
