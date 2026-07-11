package cn.iocoder.yudao.module.skit.dal.mysql.member;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberClosureDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SkitMemberClosureMapper extends BaseMapperX<SkitMemberClosureDO> {

    default List<SkitMemberClosureDO> selectAncestors(Long descendantId) {
        return selectList(new LambdaQueryWrapperX<SkitMemberClosureDO>()
                .eq(SkitMemberClosureDO::getDescendantId, descendantId)
                .orderByAsc(SkitMemberClosureDO::getDistance));
    }

}
