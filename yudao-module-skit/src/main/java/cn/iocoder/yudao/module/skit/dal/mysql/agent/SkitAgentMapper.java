package cn.iocoder.yudao.module.skit.dal.mysql.agent;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.MPJLambdaWrapperX;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentPageReqVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentPageRow;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SkitAgentMapper extends BaseMapperX<SkitAgentDO> {

    default PageResult<SkitAgentPageRow> selectPage(SkitAgentPageReqVO reqVO) {
        String keyword = StrUtil.trim(reqVO.getKeyword());
        MPJLambdaWrapperX<SkitAgentDO> query = new MPJLambdaWrapperX<SkitAgentDO>()
                .selectAs(SkitAgentDO::getId, SkitAgentPageRow::getAgentId)
                .selectAs(SkitAgentDO::getTenantId, SkitAgentPageRow::getTenantId)
                .selectAs(SkitAgentDO::getTenantCode, SkitAgentPageRow::getTenantCode)
                .selectAs(SkitAgentDO::getRootInviteCode, SkitAgentPageRow::getRootInviteCode)
                .selectAs(SkitAgentDO::getArchivedTime, SkitAgentPageRow::getArchivedTime)
                .selectAs(SkitAgentDO::getArchivedBy, SkitAgentPageRow::getArchivedBy)
                .selectAs(SkitAgentDO::getRemark, SkitAgentPageRow::getRemark)
                .selectAs(SkitAgentDO::getCreateTime, SkitAgentPageRow::getCreateTime)
                .selectAs(TenantDO::getName, SkitAgentPageRow::getName)
                .selectAs(TenantDO::getContactUserId, SkitAgentPageRow::getContactUserId)
                .selectAs(TenantDO::getContactName, SkitAgentPageRow::getContactName)
                .selectAs(TenantDO::getContactMobile, SkitAgentPageRow::getContactMobile)
                .selectAs(TenantDO::getStatus, SkitAgentPageRow::getStatus)
                .selectAs(TenantDO::getPackageId, SkitAgentPageRow::getPackageId)
                .selectAs(TenantDO::getExpireTime, SkitAgentPageRow::getExpireTime)
                .selectAs(TenantDO::getAccountCount, SkitAgentPageRow::getAccountCount)
                .innerJoin(TenantDO.class, TenantDO::getId, SkitAgentDO::getTenantId)
                .eqIfPresent(TenantDO::getStatus, reqVO.getStatus())
                .orderByDesc(SkitAgentDO::getId);
        if (StrUtil.isNotBlank(keyword)) {
            query.and(wrapper -> wrapper.like(SkitAgentDO::getTenantCode, keyword)
                    .or().like(TenantDO::getName, keyword)
                    .or().like(TenantDO::getContactName, keyword)
                    .or().like(TenantDO::getContactMobile, keyword));
        }
        return selectJoinPage(reqVO, SkitAgentPageRow.class, query);
    }

    default SkitAgentDO selectByTenantId(Long tenantId) {
        return selectOne(SkitAgentDO::getTenantId, tenantId);
    }

    @Select("SELECT * FROM `skit_agent` WHERE `tenant_id`=#{tenantId} "
            + "AND `deleted`=b'0' FOR UPDATE")
    SkitAgentDO selectByTenantIdForUpdate(@Param("tenantId") Long tenantId);

    default SkitAgentDO selectByTenantCode(String tenantCode) {
        return selectOne(SkitAgentDO::getTenantCode, tenantCode);
    }

    default SkitAgentDO selectByRootInviteCode(String inviteCode) {
        return selectOne(SkitAgentDO::getRootInviteCode, inviteCode);
    }

    default int updateArchiveState(Long tenantId, LocalDateTime archivedTime, Long archivedBy) {
        return update(new SkitAgentDO(), new LambdaUpdateWrapper<SkitAgentDO>()
                .eq(SkitAgentDO::getTenantId, tenantId)
                .set(SkitAgentDO::getArchivedTime, archivedTime)
                .set(SkitAgentDO::getArchivedBy, archivedBy));
    }

    default int clearArchiveState(Long tenantId) {
        return update(new SkitAgentDO(), new LambdaUpdateWrapper<SkitAgentDO>()
                .eq(SkitAgentDO::getTenantId, tenantId)
                .set(SkitAgentDO::getArchivedTime, null)
                .set(SkitAgentDO::getArchivedBy, null));
    }

    @Update("UPDATE `skit_agent` SET `root_invite_code`=#{newInviteCode},"
            + "`updater`='invite-rotation',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{agentId} "
            + "AND `root_invite_code`=#{oldInviteCode} AND `deleted`=b'0'")
    int updateRootInviteCode(@Param("tenantId") Long tenantId,
                             @Param("agentId") Long agentId,
                             @Param("oldInviteCode") String oldInviteCode,
                             @Param("newInviteCode") String newInviteCode);

}
