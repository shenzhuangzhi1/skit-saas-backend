package cn.iocoder.yudao.module.skit.dal.mysql.app;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkitAppReleaseProfileMapper extends BaseMapperX<SkitAppReleaseProfileDO> {

    default SkitAppReleaseProfileDO selectByProfileCode(String profileCode) {
        return selectOne(SkitAppReleaseProfileDO::getProfileCode, profileCode);
    }

    default SkitAppReleaseProfileDO selectByTenantId(Long tenantId) {
        return selectOne(SkitAppReleaseProfileDO::getTenantId, tenantId);
    }

}
