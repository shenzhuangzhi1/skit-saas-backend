package cn.iocoder.yudao.module.skit.dal.mysql.management;

import cn.iocoder.yudao.module.skit.dal.dataobject.management.SkitManagementCommandAuditDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkitManagementCommandAuditMapper {

    @Insert("INSERT INTO `skit_management_command_audit` "
            + "(`tenant_id`,`command_id`,`operator_user_id`,`original_tenant_id`,`target_tenant_id`,"
            + "`command_type`,`resource_type`,`resource_id`,`reason`,`before_state_hash`,"
            + "`after_state_hash`,`request_fingerprint`,`trace_id`,`result_status`,`created_at`) VALUES "
            + "(#{tenantId},#{commandId},#{operatorUserId},#{originalTenantId},#{targetTenantId},"
            + "#{commandType},#{resourceType},#{resourceId},#{reason},#{beforeStateHash},"
            + "#{afterStateHash},#{requestFingerprint},#{traceId},#{resultStatus},#{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSuccess(SkitManagementCommandAuditDO row);

    @Select("SELECT * FROM `skit_management_command_audit` WHERE `tenant_id`=#{tenantId} "
            + "AND `id`=#{id}")
    SkitManagementCommandAuditDO selectByTenantAndId(@Param("tenantId") Long tenantId,
                                                       @Param("id") Long id);

    @Select("SELECT * FROM `skit_management_command_audit` WHERE `tenant_id`=#{tenantId} "
            + "AND `command_id`=#{commandId}")
    SkitManagementCommandAuditDO selectByTenantAndCommandId(@Param("tenantId") Long tenantId,
                                                              @Param("commandId") String commandId);

}
