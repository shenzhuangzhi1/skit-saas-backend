package cn.iocoder.yudao.module.skit.dal.mysql.record;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordPageReqVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SkitAdminRecordMapper extends BaseMapperX<SkitAdminRecordDO> {

    default PageResult<SkitAdminRecordDO> selectPage(SkitAdminRecordPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SkitAdminRecordDO>()
                .eq(SkitAdminRecordDO::getPageKey, reqVO.getPageKey())
                .eqIfPresent(SkitAdminRecordDO::getStatus, reqVO.getStatus())
                .likeIfPresent(SkitAdminRecordDO::getRecordData, reqVO.getKeyword())
                .orderByAsc(SkitAdminRecordDO::getSort)
                .orderByDesc(SkitAdminRecordDO::getId));
    }

    default Long selectCountByPageKey(String pageKey) {
        return selectCount(SkitAdminRecordDO::getPageKey, pageKey);
    }

    default List<SkitAdminRecordDO> selectListByPageKey(String pageKey) {
        return selectList(new LambdaQueryWrapperX<SkitAdminRecordDO>()
                .eq(SkitAdminRecordDO::getPageKey, pageKey)
                .orderByAsc(SkitAdminRecordDO::getSort)
                .orderByDesc(SkitAdminRecordDO::getId));
    }

}
