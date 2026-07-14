package cn.iocoder.yudao.module.skit.dal.mysql.member;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkitMemberMapper extends BaseMapperX<SkitMemberDO> {

    @Select("SELECT * FROM `skit_member` WHERE `tenant_id`=#{tenantId} AND `id`=#{id} "
            + "AND `deleted`=b'0' FOR UPDATE")
    SkitMemberDO selectByTenantAndIdForUpdate(@Param("tenantId") Long tenantId, @Param("id") Long id);

    @Select({"<script>",
            "SELECT * FROM `skit_member` WHERE `tenant_id`=#{tenantId} AND `deleted`=b'0' AND `id` IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "FOR UPDATE",
            "</script>"})
    List<SkitMemberDO> selectByTenantAndIdsForUpdate(@Param("tenantId") Long tenantId,
                                                      @Param("ids") List<Long> ids);

    default SkitMemberDO selectByMobile(String mobile) {
        return selectOne(SkitMemberDO::getMobile, mobile);
    }

    default List<SkitMemberDO> selectListByMobile(String mobile) {
        return selectList(SkitMemberDO::getMobile, mobile);
    }

    default SkitMemberDO selectByInviteCode(String inviteCode) {
        return selectOne(SkitMemberDO::getInviteCode, inviteCode);
    }

    default PageResult<SkitMemberDO> selectPage(PageParam pageParam, String keyword, Integer status) {
        LambdaQueryWrapperX<SkitMemberDO> query = new LambdaQueryWrapperX<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String normalizedKeyword = keyword.trim();
            query.and(wrapper -> wrapper.like(SkitMemberDO::getMobile, normalizedKeyword)
                    .or().like(SkitMemberDO::getNickname, normalizedKeyword)
                    .or().like(SkitMemberDO::getInviteCode, normalizedKeyword));
        }
        return selectPage(pageParam, query.eqIfPresent(SkitMemberDO::getStatus, status)
                .orderByDesc(SkitMemberDO::getId));
    }

    default PageResult<SkitMemberDO> selectChildrenPage(PageParam pageParam, Long inviterId) {
        return selectPage(pageParam, new LambdaQueryWrapperX<SkitMemberDO>()
                .eq(SkitMemberDO::getInviterId, inviterId)
                .orderByDesc(SkitMemberDO::getId));
    }

    default Long selectCountByInviterId(Long inviterId) {
        return selectCount(SkitMemberDO::getInviterId, inviterId);
    }

}
