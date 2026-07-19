package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRetentionClaimDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkitAdCallbackAttemptMapper {

    @Select("SELECT `tenant_id`,`ad_account_id`,`id` FROM `skit_ad_callback_attempt` "
            + "WHERE `received_at`<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL #{retentionDays} DAY) "
            + "ORDER BY `received_at`,`id` LIMIT #{limit}")
    List<SkitAdRetentionClaimDO> selectExpiredRetentionClaims(
            @Param("retentionDays") int retentionDays, @Param("limit") int limit);

    @Insert("INSERT INTO `skit_ad_callback_attempt` "
            + "(`tenant_id`,`callback_inbox_id`,`ad_account_id`,`ad_session_id`,`attempt_no`,"
            + "`payload_hash`,`result_code`,`received_at`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{callbackInboxId},#{adAccountId},#{adSessionId},#{attemptNo},"
            + "#{payloadHash},#{resultCode},#{receivedAt},'callback-delivery','callback-delivery')")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicitly bound and constrained to the inbox scope
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAdCallbackAttemptDO row);

    @Select("SELECT COALESCE(MAX(`attempt_no`),0) FROM `skit_ad_callback_attempt` "
            + "WHERE `tenant_id`=#{tenantId} AND `callback_inbox_id`=#{callbackInboxId}")
    Integer selectMaxAttemptNo(@Param("tenantId") Long tenantId,
                               @Param("callbackInboxId") Long callbackInboxId);

    @Delete("DELETE FROM `skit_ad_callback_attempt` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_account_id`=#{adAccountId} AND `id`=#{id} "
            + "AND `received_at`<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL #{retentionDays} DAY)")
    int deleteExpiredRetentionClaimCas(@Param("tenantId") Long tenantId,
                                       @Param("adAccountId") Long adAccountId,
                                       @Param("id") Long id,
                                       @Param("retentionDays") int retentionDays);

}
