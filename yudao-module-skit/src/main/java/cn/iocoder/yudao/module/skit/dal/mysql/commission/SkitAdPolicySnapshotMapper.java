package cn.iocoder.yudao.module.skit.dal.mysql.commission;

import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitAdPolicySnapshotDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkitAdPolicySnapshotMapper {

    @Insert("INSERT INTO `skit_ad_policy_snapshot` "
            + "(tenant_id,`plan_id`,`source_member_id`,`rule_version`,`snapshot_schema_version`,"
            + "`snapshot_json`,`snapshot_hash`,`policy_snapshot_at`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{planId},#{sourceMemberId},#{ruleVersion},#{snapshotSchemaVersion},"
            + "#{snapshotJson},#{snapshotHash},#{policySnapshotAt},"
            + "'policy-snapshot','policy-snapshot')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAdPolicySnapshotDO row);

    @Select("SELECT * FROM `skit_ad_policy_snapshot` WHERE `tenant_id`=#{tenantId} "
            + "AND `id`=#{id} AND `deleted`=b'0' LIMIT 1")
    SkitAdPolicySnapshotDO selectByTenantAndId(@Param("tenantId") Long tenantId,
                                               @Param("id") Long id);

}
