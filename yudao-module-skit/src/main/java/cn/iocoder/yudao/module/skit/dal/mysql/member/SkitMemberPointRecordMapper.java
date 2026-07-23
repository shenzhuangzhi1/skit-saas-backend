package cn.iocoder.yudao.module.skit.dal.mysql.member;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.point.SkitMemberPointRecordPageReqVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberPointRecordDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkitMemberPointRecordMapper extends BaseMapperX<SkitMemberPointRecordDO> {

    default SkitMemberPointRecordDO selectByBusiness(
            Long tenantId, Long memberId, String bizType, String bizId) {
        return selectOne(new LambdaQueryWrapperX<SkitMemberPointRecordDO>()
                .eq(SkitMemberPointRecordDO::getTenantId, tenantId)
                .eq(SkitMemberPointRecordDO::getMemberId, memberId)
                .eq(SkitMemberPointRecordDO::getBizType, bizType)
                .eq(SkitMemberPointRecordDO::getBizId, bizId));
    }

    default Long selectCountByBizType(Long tenantId, Long memberId, String bizType) {
        return selectCount(new LambdaQueryWrapperX<SkitMemberPointRecordDO>()
                .eq(SkitMemberPointRecordDO::getTenantId, tenantId)
                .eq(SkitMemberPointRecordDO::getMemberId, memberId)
                .eq(SkitMemberPointRecordDO::getBizType, bizType));
    }

    @Select("SELECT `biz_id` FROM `skit_member_point_record` "
            + "WHERE `tenant_id`=#{tenantId} AND `member_id`=#{memberId} "
            + "AND `biz_type`=#{bizType} AND `deleted`=b'0' ORDER BY `biz_id` DESC")
    List<String> selectBizIdsByType(
            @Param("tenantId") Long tenantId,
            @Param("memberId") Long memberId,
            @Param("bizType") String bizType);

    default PageResult<SkitMemberPointRecordDO> selectPage(
            Long memberId, SkitMemberPointRecordPageReqVO request) {
        LambdaQueryWrapperX<SkitMemberPointRecordDO> query =
                new LambdaQueryWrapperX<SkitMemberPointRecordDO>()
                        .eq(SkitMemberPointRecordDO::getMemberId, memberId)
                        .betweenIfPresent(SkitMemberPointRecordDO::getCreateTime, request.getCreateTime());
        if (Boolean.TRUE.equals(request.getAddStatus())) {
            query.gt(SkitMemberPointRecordDO::getPointDelta, 0);
        } else if (Boolean.FALSE.equals(request.getAddStatus())) {
            query.lt(SkitMemberPointRecordDO::getPointDelta, 0);
        }
        return selectPage(request, query.orderByDesc(SkitMemberPointRecordDO::getId));
    }

}
