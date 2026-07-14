package cn.iocoder.yudao.module.skit.dal.mysql.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdClientEventDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkitAdClientEventMapper {

    @Insert("INSERT INTO `skit_ad_client_event` (tenant_id,`ad_session_id`,`protocol_version`,"
            + "`client_event_id`,`callback_sequence`,`event_type`,`native_state`,`sdk_request_id`,"
            + "`provider_show_id`,`network_firm_id`,`adsource_id`,`client_reward_observed`,`closed`,"
            + "`payload_hash`,`occurred_at`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{adSessionId},#{protocolVersion},#{clientEventId},#{callbackSequence},"
            + "#{eventType},#{nativeState},#{sdkRequestId},#{providerShowId},#{networkFirmId},"
            + "#{adsourceId},#{clientRewardObserved},#{closed},#{payloadHash},#{occurredAt},"
            + "'ad-client-event','ad-client-event')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCanonical(SkitAdClientEventDO row);

    @Select("SELECT * FROM `skit_ad_client_event` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_session_id`=#{adSessionId} AND `client_event_id`=#{clientEventId} "
            + "AND `deleted`=b'0' LIMIT 1")
    SkitAdClientEventDO selectByClientEventId(@Param("tenantId") Long tenantId,
                                              @Param("adSessionId") Long adSessionId,
                                              @Param("clientEventId") String clientEventId);

    @Select("SELECT * FROM `skit_ad_client_event` WHERE `tenant_id`=#{tenantId} "
            + "AND `ad_session_id`=#{adSessionId} AND `callback_sequence`=#{callbackSequence} "
            + "AND `deleted`=b'0' LIMIT 1")
    SkitAdClientEventDO selectBySequence(@Param("tenantId") Long tenantId,
                                         @Param("adSessionId") Long adSessionId,
                                         @Param("callbackSequence") Integer callbackSequence);

}
