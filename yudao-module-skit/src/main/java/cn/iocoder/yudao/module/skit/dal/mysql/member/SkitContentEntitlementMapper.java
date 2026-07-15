package cn.iocoder.yudao.module.skit.dal.mysql.member;

import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitContentEntitlementDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkitContentEntitlementMapper {

    @Select("SELECT * FROM `skit_content_entitlement` WHERE `tenant_id`=#{tenantId} "
            + "AND `member_id`=#{memberId} AND `drama_id`=#{dramaId} "
            + "AND `status`='GRANTED' AND `deleted`=b'0' ORDER BY `episode_no` ASC")
    List<SkitContentEntitlementDO> selectGrantedEpisodes(@Param("tenantId") Long tenantId,
                                                         @Param("memberId") Long memberId,
                                                         @Param("dramaId") Long dramaId);

    @Select("SELECT COUNT(*) FROM `skit_content_entitlement` WHERE `tenant_id`=#{tenantId} "
            + "AND `member_id`=#{memberId} AND `drama_id`=#{dramaId} "
            + "AND `episode_no` BETWEEN #{episodeFrom} AND #{episodeTo} "
            + "AND `status`='GRANTED' AND `deleted`=b'0'")
    Long countGrantedEpisodesInRange(@Param("tenantId") Long tenantId,
                                     @Param("memberId") Long memberId,
                                     @Param("dramaId") Long dramaId,
                                     @Param("episodeFrom") Integer episodeFrom,
                                     @Param("episodeTo") Integer episodeTo);

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

}
