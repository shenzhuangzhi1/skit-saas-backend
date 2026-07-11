package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkitAdAccountMapper extends BaseMapperX<SkitAdAccountDO> {

    default SkitAdAccountDO selectByProvider(String provider) {
        return selectOne(SkitAdAccountDO::getProvider, provider);
    }

}
