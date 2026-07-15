package cn.iocoder.yudao.module.skit.dal.mysql.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReportPullDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface SkitAdReportPullMapper {

    @Select("SELECT `p`.`report_date` FROM `skit_ad_report_pull` `p` "
            + "WHERE `p`.`tenant_id`=#{tenantId} AND `p`.`ad_account_id`=#{adAccountId} "
            + "AND `p`.`provider`='TAKU' AND `p`.`report_date`<#{beforeDate} "
            + "AND `p`.`report_timezone`=#{reportTimezone} AND `p`.`currency`=#{currency} "
            + "AND `p`.`amount_scale`=#{amountScale} AND `p`.`status`='SUCCEEDED' "
            + "AND `p`.`final_window`=b'0' AND `p`.`deleted`=b'0' "
            + "AND NOT EXISTS (SELECT 1 FROM `skit_ad_report_pull` `final_pull` "
            + "WHERE `final_pull`.`tenant_id`=`p`.`tenant_id` "
            + "AND `final_pull`.`ad_account_id`=`p`.`ad_account_id` "
            + "AND `final_pull`.`report_date`=`p`.`report_date` "
            + "AND `final_pull`.`report_timezone`=`p`.`report_timezone` "
            + "AND `final_pull`.`currency`=`p`.`currency` "
            + "AND `final_pull`.`amount_scale`=`p`.`amount_scale` "
            + "AND `final_pull`.`status`='SUCCEEDED' "
            + "AND `final_pull`.`final_window`=b'1' AND `final_pull`.`deleted`=b'0') "
            + "GROUP BY `p`.`report_date` ORDER BY `p`.`report_date` LIMIT #{limit}")
    List<LocalDate> selectPendingFinalReportDates(
            @Param("tenantId") long tenantId, @Param("adAccountId") long adAccountId,
            @Param("beforeDate") LocalDate beforeDate,
            @Param("reportTimezone") String reportTimezone,
            @Param("currency") String currency, @Param("amountScale") int amountScale,
            @Param("limit") int limit);

    @Insert("INSERT INTO `skit_ad_report_pull` (`tenant_id`,`ad_account_id`,`provider`,"
            + "`range_start`,`range_end`,`report_date`,`report_timezone`,`currency`,`amount_scale`,"
            + "`request_hash`,`credential_version`,`response_hash`,`status`,`final_window`,"
            + "`pulled_at`,`error_code`,"
            + "`creator`,`updater`) VALUES (#{tenantId},#{adAccountId},#{provider},#{rangeStart},"
            + "#{rangeEnd},#{reportDate},#{reportTimezone},#{currency},#{amountScale},#{requestHash},"
            + "#{credentialVersion},#{responseHash},#{status},#{finalWindow},#{pulledAt},#{errorCode},"
            + "'report-pull','report-pull')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAdReportPullDO row);

    @Select("SELECT * FROM `skit_ad_report_pull` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `range_start`=#{rangeStart} "
            + "AND `range_end`=#{rangeEnd} AND `request_hash`=#{requestHash} "
            + "AND `response_hash`=#{responseHash} "
            + "AND `credential_version`=#{credentialVersion} "
            + "AND `final_window`=#{finalWindow} "
            + "AND `deleted`=b'0' LIMIT 1 FOR UPDATE")
    SkitAdReportPullDO selectCanonicalForUpdate(@Param("tenantId") long tenantId,
                                                @Param("adAccountId") long adAccountId,
                                                @Param("rangeStart") LocalDateTime rangeStart,
                                                @Param("rangeEnd") LocalDateTime rangeEnd,
                                                @Param("requestHash") byte[] requestHash,
                                                @Param("responseHash") byte[] responseHash,
                                                @Param("credentialVersion") int credentialVersion,
                                                @Param("finalWindow") boolean finalWindow);

}
