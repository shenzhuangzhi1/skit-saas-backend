package cn.iocoder.yudao.module.skit.dal.mysql.member;

import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitContentEntitlementDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SkitContentEntitlementMapper {

    @Select("SELECT * FROM `skit_content_entitlement` WHERE `tenant_id`=#{tenantId} "
            + "AND `member_id`=#{memberId} AND `drama_id`=#{dramaId} "
            + "AND `status`='GRANTED' AND `lease_activated_at` > #{activeAfter} "
            + "AND `deleted`=b'0' ORDER BY `episode_no` ASC")
    List<SkitContentEntitlementDO> selectGrantedEpisodes(@Param("tenantId") Long tenantId,
                                                         @Param("memberId") Long memberId,
                                                         @Param("dramaId") Long dramaId,
                                                         @Param("activeAfter") LocalDateTime activeAfter);

    @Select("SELECT COUNT(*) FROM `skit_content_entitlement` WHERE `tenant_id`=#{tenantId} "
            + "AND `member_id`=#{memberId} AND `drama_id`=#{dramaId} "
            + "AND `episode_no` BETWEEN #{episodeFrom} AND #{episodeTo} "
            + "AND `status`='GRANTED' AND `lease_activated_at` > #{activeAfter} AND `deleted`=b'0'")
    Long countGrantedEpisodesInRange(@Param("tenantId") Long tenantId,
                                     @Param("memberId") Long memberId,
                                     @Param("dramaId") Long dramaId,
                                     @Param("episodeFrom") Integer episodeFrom,
                                     @Param("episodeTo") Integer episodeTo,
                                     @Param("activeAfter") LocalDateTime activeAfter);

    @Select({"<script>",
            "SELECT * FROM `skit_content_entitlement` WHERE `tenant_id`=#{tenantId}",
            "AND `member_id`=#{memberId} AND `drama_id`=#{dramaId} AND `deleted`=b'0'",
            "<choose>",
            "<when test='episodeNos != null and !episodeNos.isEmpty()'>",
            "AND `episode_no` IN",
            "<foreach collection='episodeNos' item='episodeNo' open='(' separator=',' close=')'>",
            "#{episodeNo}",
            "</foreach>",
            "</when>",
            "<otherwise>AND 1=0</otherwise>",
            "</choose>",
            "FOR UPDATE",
            "</script>"})
    List<SkitContentEntitlementDO> selectEpisodesForUpdate(@Param("tenantId") Long tenantId,
                                                           @Param("memberId") Long memberId,
                                                           @Param("dramaId") Long dramaId,
                                                           @Param("episodeNos") List<Integer> episodeNos);

    @Insert("INSERT INTO `skit_content_entitlement` (tenant_id,`member_id`,`drama_id`,`episode_no`,"
            + "`status`,`granted_at`,`lease_activated_at`,`version`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{memberId},#{dramaId},#{episodeNo},#{status},#{grantedAt},"
            + "#{leaseActivatedAt},#{version},"
            + "'content-entitlement','content-entitlement') "
            + "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertGrantedIfAbsent(SkitContentEntitlementDO row);

    /**
     * Advances the signed proof anchor and playback lease for every fresh verified reward. A
     * valid newer ad session must supersede an older close even when that close just reactivated
     * the prior lease. The callback processor holds the row lock; the exact proof/version CAS
     * prevents a stale worker from moving the projection backwards.
     */
    @Update("UPDATE `skit_content_entitlement` SET `granted_at`=#{grantedAt},"
            + "`lease_activated_at`=#{grantedAt},"
            + "`version`=`version`+1,`updater`='content-entitlement' "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `drama_id`=#{dramaId} AND `episode_no`=#{episodeNo} "
            + "AND `status`='GRANTED' AND `deleted`=b'0' AND `version`=#{expectedVersion} "
            + "AND `granted_at`=#{expectedGrantedAt} AND `granted_at` < #{grantedAt}")
    int advanceVerifiedRewardLeaseCas(@Param("tenantId") Long tenantId,
                                      @Param("id") Long id,
                                      @Param("memberId") Long memberId,
                                      @Param("dramaId") Long dramaId,
                                      @Param("episodeNo") Integer episodeNo,
                                      @Param("expectedVersion") Integer expectedVersion,
                                      @Param("expectedGrantedAt") LocalDateTime expectedGrantedAt,
                                      @Param("grantedAt") LocalDateTime grantedAt);

    /**
     * Starts a fresh playback lease when the canonical rewarded close arrives after the immutable
     * signed reward. The proof anchor remains unchanged and the exact grant is checked by service.
     */
    @Update("UPDATE `skit_content_entitlement` SET `lease_activated_at`=#{activatedAt},"
            + "`version`=`version`+1,`updater`='rewarded-close-settlement' "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `drama_id`=#{dramaId} AND `episode_no`=#{episodeNo} "
            + "AND `status`='GRANTED' AND `deleted`=b'0' AND `version`=#{expectedVersion} "
            + "AND `granted_at`=#{proofGrantedAt} AND `lease_activated_at` < #{activatedAt}")
    int activateVerifiedRewardLeaseCas(@Param("tenantId") Long tenantId,
                                       @Param("id") Long id,
                                       @Param("memberId") Long memberId,
                                       @Param("dramaId") Long dramaId,
                                       @Param("episodeNo") Integer episodeNo,
                                       @Param("expectedVersion") Integer expectedVersion,
                                       @Param("proofGrantedAt") LocalDateTime proofGrantedAt,
                                       @Param("activatedAt") LocalDateTime activatedAt);

}
