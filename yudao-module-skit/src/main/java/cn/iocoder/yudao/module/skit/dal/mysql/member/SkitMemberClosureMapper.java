package cn.iocoder.yudao.module.skit.dal.mysql.member;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberClosureDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkitMemberClosureMapper extends BaseMapperX<SkitMemberClosureDO> {

    default List<SkitMemberClosureDO> selectAncestors(Long descendantId) {
        return selectList(new LambdaQueryWrapperX<SkitMemberClosureDO>()
                .eq(SkitMemberClosureDO::getDescendantId, descendantId)
                .orderByAsc(SkitMemberClosureDO::getDistance));
    }

    @Select("SELECT * FROM `skit_member_closure` WHERE `tenant_id`=#{tenantId} "
            + "AND `descendant_id`=#{descendantId} AND `deleted`=b'0' FOR SHARE")
    List<SkitMemberClosureDO> selectAncestorsForShare(@Param("tenantId") Long tenantId,
                                                      @Param("descendantId") Long descendantId);

}
