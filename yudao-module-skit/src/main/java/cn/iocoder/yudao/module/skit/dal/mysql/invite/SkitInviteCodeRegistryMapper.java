package cn.iocoder.yudao.module.skit.dal.mysql.invite;

import cn.iocoder.yudao.module.skit.dal.dataobject.invite.SkitInviteCodeRegistryDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SkitInviteCodeRegistryMapper {

    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit; this mapper is intentionally not a BaseMapper
    @Insert("INSERT INTO `skit_invite_code_registry` "
            + "(`tenant_id`,`code`,`owner_type`,`agent_id`,`member_id`,`status`,`rotated_at`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{code},#{ownerType},#{agentId},#{memberId},#{status},#{rotatedAt},"
            + "'invite-code-registry','invite-code-registry')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitInviteCodeRegistryDO row);

    @Select("SELECT * FROM `skit_invite_code_registry` "
            + "WHERE `normalized_code`=#{normalizedCode} AND `deleted`=b'0' LIMIT 1")
    SkitInviteCodeRegistryDO selectGlobalByNormalizedCode(
            @Param("normalizedCode") String normalizedCode);

    @Select("SELECT * FROM `skit_invite_code_registry` WHERE `tenant_id`=#{tenantId} "
            + "AND `normalized_code`=#{normalizedCode} AND `owner_type`=#{ownerType} "
            + "AND ((#{ownerType}='AGENT' AND `agent_id`=#{ownerId} AND `member_id` IS NULL) "
            + "OR (#{ownerType}='MEMBER' AND `member_id`=#{ownerId} AND `agent_id` IS NULL)) "
            + "AND `status`='ACTIVE' AND `rotated_at` IS NULL AND `deleted`=b'0' FOR UPDATE")
    SkitInviteCodeRegistryDO selectActiveForUpdate(@Param("tenantId") Long tenantId,
                                                   @Param("ownerType") String ownerType,
                                                   @Param("ownerId") Long ownerId,
                                                   @Param("normalizedCode") String normalizedCode);

    @Update("UPDATE `skit_invite_code_registry` SET `status`='ROTATED',`rotated_at`=#{rotatedAt},"
            + "`updater`='invite-code-rotation',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `id`=#{id} AND `tenant_id`=#{tenantId} AND `normalized_code`=#{normalizedCode} "
            + "AND `owner_type`=#{ownerType} "
            + "AND ((#{ownerType}='AGENT' AND `agent_id`=#{ownerId} AND `member_id` IS NULL) "
            + "OR (#{ownerType}='MEMBER' AND `member_id`=#{ownerId} AND `agent_id` IS NULL)) "
            + "AND `status`='ACTIVE' AND `rotated_at` IS NULL AND `deleted`=b'0'")
    int rotateActive(@Param("id") Long id,
                     @Param("tenantId") Long tenantId,
                     @Param("ownerType") String ownerType,
                     @Param("ownerId") Long ownerId,
                     @Param("normalizedCode") String normalizedCode,
                     @Param("rotatedAt") LocalDateTime rotatedAt);

}
