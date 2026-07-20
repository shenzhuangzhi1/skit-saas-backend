package cn.iocoder.yudao.module.skit.dal.mysql.member;

import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitEntitlementGrantDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkitEntitlementGrantMapper {

    @Select("SELECT * FROM `skit_entitlement_grant` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_session_id`=#{adSessionId} AND `episode_no`=#{episodeNo} FOR UPDATE")
    SkitEntitlementGrantDO selectBySessionAndEpisodeForUpdate(@Param("tenantId") Long tenantId,
                                                              @Param("adSessionId") Long adSessionId,
                                                              @Param("episodeNo") Integer episodeNo);

    /**
     * Returns only an immutable, signed reward proof for the exact entitlement grant.
     *
     * <p>The session copy of {@code provider_show_id} is intentionally not trusted here: it may
     * have first been observed through client telemetry. The callback-inbox value is selected only
     * after it has passed signed reward processing and is bound back to the same session and grant.
     */
    @Select("SELECT `g`.`tenant_id` AS `tenantId`,`g`.`member_id` AS `memberId`,"
            + "`g`.`drama_id` AS `dramaId`,`g`.`episode_no` AS `episodeNo`,"
            + "`s`.`session_id` AS `sessionId`,`i`.`provider` AS `provider`,"
            + "`i`.`provider_show_id` AS `providerShowId` "
            + "FROM `skit_entitlement_grant` `g` "
            + "INNER JOIN `skit_content_entitlement` `e` "
            + "ON `e`.`tenant_id`=`g`.`tenant_id` AND `e`.`id`=`g`.`entitlement_id` "
            + "AND `e`.`member_id`=`g`.`member_id` AND `e`.`drama_id`=`g`.`drama_id` "
            + "AND `e`.`episode_no`=`g`.`episode_no` "
            + "INNER JOIN `skit_ad_session` `s` "
            + "ON `s`.`tenant_id`=`g`.`tenant_id` AND `s`.`id`=`g`.`ad_session_id` "
            + "AND `s`.`member_id`=`g`.`member_id` AND `s`.`drama_id`=`g`.`drama_id` "
            + "AND `g`.`episode_no` BETWEEN `s`.`episode_from` AND `s`.`episode_to` "
            + "AND HEX(`s`.`provider_transaction_id`)=HEX(`g`.`provider_transaction_id`) "
            + "INNER JOIN `skit_ad_callback_inbox` `i` "
            + "ON `i`.`tenant_id`=`s`.`tenant_id` AND `i`.`id`=`s`.`reward_callback_inbox_id` "
            + "AND `i`.`ad_session_id`=`s`.`id` AND `i`.`ad_account_id`=`s`.`ad_account_id` "
            + "AND HEX(`i`.`provider_transaction_id`)=HEX(`s`.`provider_transaction_id`) "
            + "WHERE `g`.`tenant_id`=#{tenantId} AND `g`.`member_id`=#{memberId} "
            + "AND `g`.`drama_id`=#{dramaId} AND `g`.`episode_no`=#{episodeNo} "
            + "AND `g`.`grant_result`='CREATED' AND `g`.`deleted`=b'0' "
            + "AND `e`.`status`='GRANTED' AND `e`.`deleted`=b'0' "
            + "AND `s`.`provider`='TAKU' AND `s`.`reward_verification_status`='SIGNED_VERIFIED' "
            + "AND `s`.`entitlement_status`='GRANTED' "
            + "AND `s`.`active_scope_release_reason`='ENTITLEMENT_GRANTED' "
            + "AND `s`.`deleted`=b'0' "
            + "AND `i`.`provider`='TAKU' AND `i`.`callback_type`='REWARD' "
            + "AND `i`.`evidence_provenance`='SIGNED_ILRD' "
            + "AND `i`.`authentication_level`='SIGNED_REWARD' "
            + "AND `i`.`signature_status`='VALID' "
            + "AND `i`.`delivery_integrity_status`='CANONICAL' "
            + "AND `i`.`processing_status`='SUCCEEDED' AND `i`.`deleted`=b'0' "
            + "AND `i`.`provider_show_id` IS NOT NULL AND `i`.`provider_show_id`<>'' "
            + "AND HEX(`i`.`provider_show_id`)=HEX(`s`.`provider_show_id`) "
            + "LIMIT 2")
    List<VerifiedRewardProvenanceRow> selectVerifiedRewardProvenance(
            @Param("tenantId") Long tenantId,
            @Param("memberId") Long memberId,
            @Param("dramaId") Long dramaId,
            @Param("episodeNo") Integer episodeNo);

    @Insert("INSERT INTO `skit_entitlement_grant` (tenant_id,`ad_session_id`,`entitlement_id`,"
            + "`member_id`,`drama_id`,`episode_no`,`provider_transaction_id`,`grant_result`,"
            + "`granted_at`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{adSessionId},#{entitlementId},#{memberId},#{dramaId},#{episodeNo},"
            + "#{providerTransactionId},#{grantResult},#{grantedAt},'entitlement-grant','entitlement-grant')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitEntitlementGrantDO row);

    /** Minimal mapper projection; the service validates every field before it reaches a client. */
    final class VerifiedRewardProvenanceRow {
        private Long tenantId;
        private Long memberId;
        private Long dramaId;
        private Integer episodeNo;
        private String sessionId;
        private String provider;
        private String providerShowId;

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(Long tenantId) {
            this.tenantId = tenantId;
        }

        public Long getMemberId() {
            return memberId;
        }

        public void setMemberId(Long memberId) {
            this.memberId = memberId;
        }

        public Long getDramaId() {
            return dramaId;
        }

        public void setDramaId(Long dramaId) {
            this.dramaId = dramaId;
        }

        public Integer getEpisodeNo() {
            return episodeNo;
        }

        public void setEpisodeNo(Integer episodeNo) {
            this.episodeNo = episodeNo;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getProviderShowId() {
            return providerShowId;
        }

        public void setProviderShowId(String providerShowId) {
            this.providerShowId = providerShowId;
        }
    }

}
