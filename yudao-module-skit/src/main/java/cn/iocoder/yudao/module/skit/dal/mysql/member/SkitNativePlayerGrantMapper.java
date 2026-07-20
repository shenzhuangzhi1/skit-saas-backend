package cn.iocoder.yudao.module.skit.dal.mysql.member;

import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitNativePlayerGrantDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SkitNativePlayerGrantMapper {

    @Insert("INSERT INTO `skit_native_player_grant` (tenant_id,`member_id`,`drama_id`,"
            + "`grant_token_hash`,`status`,`expires_at`,`revoked_at`,`version`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{memberId},#{dramaId},#{grantTokenHash},#{status},#{expiresAt},"
            + "#{revokedAt},#{version},'native-player-grant','native-player-grant')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitNativePlayerGrantDO row);

    @Select("SELECT * FROM `skit_native_player_grant` WHERE `grant_token_hash`=#{grantTokenHash} "
            + "AND `deleted`=b'0' LIMIT 1")
    SkitNativePlayerGrantDO selectByTokenHash(@Param("grantTokenHash") byte[] grantTokenHash);

    @Select("SELECT * FROM `skit_native_player_grant` WHERE `tenant_id`=#{tenantId} "
            + "AND `id`=#{id} AND `member_id`=#{memberId} AND `drama_id`=#{dramaId} "
            + "AND `deleted`=b'0' FOR UPDATE")
    SkitNativePlayerGrantDO selectExactForUpdate(@Param("tenantId") Long tenantId,
                                                 @Param("id") Long id,
                                                 @Param("memberId") Long memberId,
                                                 @Param("dramaId") Long dramaId);

    @Update("UPDATE `skit_native_player_grant` SET `expires_at`=#{renewedExpiresAt},"
            + "`version`=`version`+1,"
            + "`updater`='native-player-use',`update_time`=#{usedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `drama_id`=#{dramaId} AND `version`=#{expectedVersion} AND `status`='ACTIVE' "
            + "AND `revoked_at` IS NULL AND `expires_at` > #{usedAt} AND `deleted`=b'0'")
    int recordActiveUseCas(@Param("tenantId") Long tenantId,
                           @Param("id") Long id,
                           @Param("memberId") Long memberId,
                           @Param("dramaId") Long dramaId,
                           @Param("expectedVersion") Integer expectedVersion,
                           @Param("usedAt") LocalDateTime usedAt,
                           @Param("renewedExpiresAt") LocalDateTime renewedExpiresAt);

    @Update("UPDATE `skit_native_player_grant` SET `status`='REVOKED',`revoked_at`=#{revokedAt},"
            + "`version`=`version`+1,`updater`='native-player-revoke',`update_time`=#{revokedAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `drama_id`=#{dramaId} AND `version`=#{expectedVersion} AND `status`='ACTIVE' "
            + "AND `revoked_at` IS NULL AND `deleted`=b'0'")
    int revokeActiveCas(@Param("tenantId") Long tenantId,
                        @Param("id") Long id,
                        @Param("memberId") Long memberId,
                        @Param("dramaId") Long dramaId,
                        @Param("expectedVersion") Integer expectedVersion,
                        @Param("revokedAt") LocalDateTime revokedAt);

    @Update("UPDATE `skit_native_player_grant` SET `status`='EXPIRED',"
            + "`version`=`version`+1,`updater`='native-player-expire',`update_time`=#{expiredAt} "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `drama_id`=#{dramaId} AND `version`=#{expectedVersion} AND `status`='ACTIVE' "
            + "AND `revoked_at` IS NULL AND `expires_at` <= #{expiredAt} AND `deleted`=b'0'")
    int expireActiveCas(@Param("tenantId") Long tenantId,
                        @Param("id") Long id,
                        @Param("memberId") Long memberId,
                        @Param("dramaId") Long dramaId,
                        @Param("expectedVersion") Integer expectedVersion,
                        @Param("expiredAt") LocalDateTime expiredAt);

    @Update("UPDATE `skit_native_player_grant` SET `status`='REVOKED',"
            + "`revoked_at`=CURRENT_TIMESTAMP,`version`=`version`+1,"
            + "`updater`='ad-rollout',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `status`='ACTIVE' "
            + "AND `revoked_at` IS NULL AND `expires_at`>CURRENT_TIMESTAMP AND `deleted`=b'0'")
    int revokeActiveForTenantRollout(@Param("tenantId") Long tenantId);

}
