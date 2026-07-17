package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SkitAdCallbackKeyMapper {

    @Insert("INSERT INTO `skit_ad_callback_key` "
            + "(tenant_id,`ad_account_id`,`key_version`,`callback_key_hash`,`accept_until`,`revoked_at`) VALUES "
            + "(#{tenantId},#{adAccountId},#{keyVersion},#{callbackKeyHash},#{acceptUntil},#{revokedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAdCallbackKeyDO row);

    @Select("SELECT MAX(`key_version`) FROM `skit_ad_callback_key` "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId}")
    Integer selectMaxVersion(@Param("tenantId") Long tenantId,
                             @Param("adAccountId") Long adAccountId);

    @Select("SELECT * FROM `skit_ad_callback_key` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `active`=b'1' AND `revoked_at` IS NULL FOR UPDATE")
    SkitAdCallbackKeyDO selectActiveForUpdate(@Param("tenantId") Long tenantId,
                                              @Param("adAccountId") Long adAccountId);

    @Select("SELECT * FROM `skit_ad_callback_key` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `active`=b'1' AND `revoked_at` IS NULL")
    SkitAdCallbackKeyDO selectActive(@Param("tenantId") Long tenantId,
                                     @Param("adAccountId") Long adAccountId);

    @Select("SELECT * FROM `skit_ad_callback_key` WHERE `callback_key_hash`=#{callbackKeyHash} LIMIT 1")
    SkitAdCallbackKeyDO selectByHash(@Param("callbackKeyHash") byte[] callbackKeyHash);

    @Update("UPDATE `skit_ad_callback_key` SET `active`=b'0',`accept_until`=#{acceptUntil},"
            + "`updater`='credential-rotation',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `active`=b'1' AND `revoked_at` IS NULL")
    int retireActiveVersion(@Param("tenantId") Long tenantId,
                            @Param("adAccountId") Long adAccountId,
                            @Param("id") Long id,
                            @Param("acceptUntil") LocalDateTime acceptUntil);

}
