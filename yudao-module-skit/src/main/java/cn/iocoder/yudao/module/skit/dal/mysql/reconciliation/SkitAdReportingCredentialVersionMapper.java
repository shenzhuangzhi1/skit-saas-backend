package cn.iocoder.yudao.module.skit.dal.mysql.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReportingCredentialVersionDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkitAdReportingCredentialVersionMapper {

    @Insert("INSERT INTO `skit_ad_reporting_credential_version` "
            + "(`tenant_id`,`ad_account_id`,`credential_version`,`ciphertext`,`nonce`,"
            + "`encryption_key_id`,`envelope_version`,`active`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{adAccountId},#{credentialVersion},#{ciphertext},#{nonce},"
            + "#{encryptionKeyId},#{envelopeVersion},b'1','reporting-credential','reporting-credential')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAdReportingCredentialVersionDO row);

    @Select("SELECT MAX(`credential_version`) FROM `skit_ad_reporting_credential_version` "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId}")
    Integer selectMaxVersion(@Param("tenantId") long tenantId,
                             @Param("adAccountId") long adAccountId);

    @Select("SELECT * FROM `skit_ad_reporting_credential_version` "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} "
            + "AND `active`=b'1' AND `revoked_at` IS NULL FOR UPDATE")
    SkitAdReportingCredentialVersionDO selectActiveForUpdate(@Param("tenantId") long tenantId,
                                                              @Param("adAccountId") long adAccountId);

    @Select("SELECT * FROM `skit_ad_reporting_credential_version` "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} "
            + "AND `active`=b'1' AND `revoked_at` IS NULL")
    SkitAdReportingCredentialVersionDO selectActive(@Param("tenantId") long tenantId,
                                                     @Param("adAccountId") long adAccountId);

    @Select("SELECT * FROM `skit_ad_reporting_credential_version` "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} "
            + "AND `credential_version`=#{credentialVersion} LIMIT 1")
    SkitAdReportingCredentialVersionDO selectByVersion(@Param("tenantId") long tenantId,
                                                        @Param("adAccountId") long adAccountId,
                                                        @Param("credentialVersion") int credentialVersion);

    @Update("UPDATE `skit_ad_reporting_credential_version` SET `active`=b'0',"
            + "`revoked_at`=CURRENT_TIMESTAMP,`updater`='reporting-credential-rotation',"
            + "`update_time`=CURRENT_TIMESTAMP WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `active`=b'1' AND `revoked_at` IS NULL")
    int retireActiveVersion(@Param("tenantId") long tenantId,
                            @Param("adAccountId") long adAccountId,
                            @Param("id") long id);

    @Update("UPDATE `skit_ad_reporting_credential_version` SET "
            + "`permission_verified_at`=COALESCE(`permission_verified_at`,CURRENT_TIMESTAMP),"
            + "`updater`='reporting-permission-verification',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `ad_account_id`=#{adAccountId} "
            + "AND `credential_version`=#{credentialVersion} AND `active`=b'1' "
            + "AND `revoked_at` IS NULL")
    int markPermissionVerifiedCas(@Param("tenantId") long tenantId,
                                  @Param("adAccountId") long adAccountId,
                                  @Param("credentialVersion") int credentialVersion);

}
