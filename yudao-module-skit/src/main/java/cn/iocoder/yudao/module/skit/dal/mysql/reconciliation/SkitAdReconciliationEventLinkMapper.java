package cn.iocoder.yudao.module.skit.dal.mysql.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReconciliationEventLinkDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkitAdReconciliationEventLinkMapper {

    @Insert("INSERT INTO `skit_ad_reconciliation_event_link` "
            + "(`tenant_id`,`reconciliation_bucket_id`,`reconciliation_revision_id`,`revision_no`,"
            + "`event_id`,`policy_snapshot_id`,`association_status`,`actual_units`,`creator`,`updater`) "
            + "VALUES (#{tenantId},#{reconciliationBucketId},#{reconciliationRevisionId},#{revisionNo},"
            + "#{eventId},#{policySnapshotId},#{associationStatus},#{actualUnits},"
            + "'reconciliation-event-link','reconciliation-event-link')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAdReconciliationEventLinkDO row);

    @Select("SELECT * FROM `skit_ad_reconciliation_event_link` "
            + "WHERE `tenant_id`=#{tenantId} AND `reconciliation_revision_id`=#{revisionId} "
            + "AND `event_id`=#{eventId} AND `deleted`=b'0' LIMIT 1 FOR UPDATE")
    SkitAdReconciliationEventLinkDO selectCanonicalForUpdate(
            @Param("tenantId") long tenantId,
            @Param("revisionId") long revisionId,
            @Param("eventId") long eventId);
}
