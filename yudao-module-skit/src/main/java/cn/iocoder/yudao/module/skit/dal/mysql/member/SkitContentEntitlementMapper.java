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
            + "AND `status`='GRANTED' AND `granted_at` > #{activeAfter} "
            + "AND `deleted`=b'0' ORDER BY `episode_no` ASC")
    List<SkitContentEntitlementDO> selectGrantedEpisodes(@Param("tenantId") Long tenantId,
                                                         @Param("memberId") Long memberId,
                                                         @Param("dramaId") Long dramaId,
                                                         @Param("activeAfter") LocalDateTime activeAfter);

    @Select("SELECT COUNT(*) FROM `skit_content_entitlement` WHERE `tenant_id`=#{tenantId} "
            + "AND `member_id`=#{memberId} AND `drama_id`=#{dramaId} "
            + "AND `episode_no` BETWEEN #{episodeFrom} AND #{episodeTo} "
            + "AND `status`='GRANTED' AND `granted_at` > #{activeAfter} AND `deleted`=b'0'")
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
            + "`status`,`granted_at`,`version`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{memberId},#{dramaId},#{episodeNo},#{status},#{grantedAt},#{version},"
            + "'content-entitlement','content-entitlement') "
            + "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertGrantedIfAbsent(SkitContentEntitlementDO row);

    /**
     * Advances the current five-minute entitlement lease only after its prior lease expired.
     * The callback processor holds the entitlement row lock, while this CAS prevents a stale
     * callback worker from moving the lease forward after another verified reward won the race.
     */
    @Update("UPDATE `skit_content_entitlement` SET `granted_at`=#{grantedAt},"
            + "`version`=`version`+1,`updater`='content-entitlement' "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `member_id`=#{memberId} "
            + "AND `drama_id`=#{dramaId} AND `episode_no`=#{episodeNo} "
            + "AND `status`='GRANTED' AND `deleted`=b'0' AND `version`=#{expectedVersion} "
            + "AND `granted_at` <= #{expiredAt}")
    int renewExpiredLeaseCas(@Param("tenantId") Long tenantId,
                              @Param("id") Long id,
                              @Param("memberId") Long memberId,
                              @Param("dramaId") Long dramaId,
                              @Param("episodeNo") Integer episodeNo,
                              @Param("expectedVersion") Integer expectedVersion,
                              @Param("expiredAt") LocalDateTime expiredAt,
                              @Param("grantedAt") LocalDateTime grantedAt);

}
