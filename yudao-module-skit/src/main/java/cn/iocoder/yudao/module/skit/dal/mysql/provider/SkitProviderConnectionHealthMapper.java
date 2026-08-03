package cn.iocoder.yudao.module.skit.dal.mysql.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderConnectionHealthProjection;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Global, aggregate-only provider health query. It cannot materialize Inbox or Attempt rows. */
@Mapper
@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
public interface SkitProviderConnectionHealthMapper {

  @TenantIgnore
  @InterceptorIgnore(tenantLine = "true")
  @Options(timeout = 2)
  @Select(
      "SELECT MIN(a.received_at) AS first_received_at,"
          + "MAX(a.received_at) AS last_received_at,"
          + "COALESCE(SUM(CASE WHEN a.response_decision='ACK_200' THEN 1 ELSE 0 END),0) "
          + "AS accepted_attempts,"
          + "COALESCE(SUM(CASE WHEN a.delivery_integrity_status='EQUIVALENT_DUPLICATE' "
          + "THEN 1 ELSE 0 END),0) AS duplicates,"
          + "COALESCE(SUM(CASE WHEN a.delivery_integrity_status='PAYLOAD_CONFLICT' "
          + "THEN 1 ELSE 0 END),0) AS conflicts,"
          + "COALESCE(SUM(CASE WHEN a.dedupe_scheme='FALLBACK_WIRE_V1' THEN 1 ELSE 0 END),0) "
          + "AS fallback,"
          + "COALESCE((SELECT COUNT(*) FROM skit_provider_impression_inbox qi "
          + "WHERE qi.provider_connection_id=c.id "
          + "AND qi.processing_status='QUARANTINED'),0) AS quarantined,"
          + "CAST(NULL AS SIGNED) AS db_failures,"
          + "CAST(NULL AS DATETIME) AS db_failure_at "
          + "FROM skit_ad_provider_connection c "
          + "LEFT JOIN skit_provider_callback_attempt a ON a.provider_connection_id=c.id "
          + "WHERE c.id=#{connectionId}")
  SkitProviderConnectionHealthProjection selectSafeAggregateByConnectionId(
      @Param("connectionId") long connectionId);
}
