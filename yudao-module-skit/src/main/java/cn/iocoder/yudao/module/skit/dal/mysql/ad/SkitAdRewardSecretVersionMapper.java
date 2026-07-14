package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRewardSecretVersionDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SkitAdRewardSecretVersionMapper {

    @Insert("INSERT INTO `skit_ad_reward_secret_version` "
            + "(`ad_account_id`,`secret_version`,`ciphertext`,`nonce`,`encryption_key_id`,"
            + "`envelope_version`,`accept_until`,`revoked_at`) VALUES "
            + "(#{adAccountId},#{secretVersion},#{ciphertext},#{nonce},#{encryptionKeyId},"
            + "#{envelopeVersion},#{acceptUntil},#{revokedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAdRewardSecretVersionDO row);

    @Select("SELECT MAX(`secret_version`) FROM `skit_ad_reward_secret_version` "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId}")
    Integer selectMaxVersion(@Param("tenantId") Long tenantId,
                             @Param("adAccountId") Long adAccountId);

    @Select("SELECT * FROM `skit_ad_reward_secret_version` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `active`=b'1' AND `revoked_at` IS NULL FOR UPDATE")
    SkitAdRewardSecretVersionDO selectActiveForUpdate(@Param("tenantId") Long tenantId,
                                                      @Param("adAccountId") Long adAccountId);

    @Select("SELECT * FROM `skit_ad_reward_secret_version` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `active`=b'1' AND `revoked_at` IS NULL")
    SkitAdRewardSecretVersionDO selectActive(@Param("tenantId") Long tenantId,
                                             @Param("adAccountId") Long adAccountId);

    @Select("SELECT * FROM `skit_ad_reward_secret_version` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `secret_version`=#{version} LIMIT 1")
    SkitAdRewardSecretVersionDO selectByVersion(@Param("tenantId") Long tenantId,
                                                @Param("adAccountId") Long adAccountId,
                                                @Param("version") Integer version);

    @Update("UPDATE `skit_ad_reward_secret_version` SET `active`=b'0',`accept_until`=#{acceptUntil},"
            + "`updater`='credential-rotation',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `active`=b'1' AND `revoked_at` IS NULL")
    int retireActiveVersion(@Param("tenantId") Long tenantId,
                            @Param("adAccountId") Long adAccountId,
                            @Param("id") Long id,
                            @Param("acceptUntil") LocalDateTime acceptUntil);

}
