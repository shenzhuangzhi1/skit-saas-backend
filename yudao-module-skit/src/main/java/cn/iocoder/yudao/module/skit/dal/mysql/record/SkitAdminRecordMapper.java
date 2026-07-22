package cn.iocoder.yudao.module.skit.dal.mysql.record;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordPageReqVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SkitAdminRecordMapper extends BaseMapperX<SkitAdminRecordDO> {

    @Insert("INSERT INTO `skit_admin_record` (`tenant_id`,`page_key`,`row_key`,`record_data`,"
            + "`status`,`sort`,`deleted`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{record.pageKey},#{record.rowKey},#{record.recordData},"
            + "#{record.status},#{record.sort},b'0','pangle-catalog-sync','pangle-catalog-sync') "
            + "ON DUPLICATE KEY UPDATE `record_data`=VALUES(`record_data`),"
            + "`status`=VALUES(`status`),`sort`=VALUES(`sort`),`deleted`=b'0',"
            + "`updater`='pangle-catalog-sync',`update_time`=CURRENT_TIMESTAMP")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit and row_key is deterministic per provider drama
    int upsertPangleDramaCatalog(@Param("tenantId") Long tenantId,
                                 @Param("record") SkitAdminRecordDO record);

    @Update("UPDATE `skit_admin_record` SET `deleted`=b'1',`status`=2,"
            + "`updater`='pangle-catalog-sync',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `page_key`='drama' AND `deleted`=b'0' "
            + "AND (`row_key` IS NULL OR `row_key`<>#{canonicalRowKey}) AND JSON_VALID(`record_data`) "
            + "AND COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.pangleDramaId')),''),"
            + "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.dramaId')),''),"
            + "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.drama_id')),''),"
            + "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.contentId')),''),"
            + "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.nativeId')),''),"
            + "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.id')),''))=#{businessId}")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit and aliases never cross tenants
    int retirePangleDramaCatalogAliases(@Param("tenantId") Long tenantId,
                                        @Param("businessId") String businessId,
                                        @Param("canonicalRowKey") String canonicalRowKey);

    @Select("SELECT * FROM `skit_admin_record` WHERE `tenant_id`=#{tenantId} "
            + "AND `page_key`='drama' AND `deleted`=b'0' AND JSON_VALID(`record_data`) "
            + "AND COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.pangleDramaId')),''),"
            + "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.dramaId')),''),"
            + "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.drama_id')),''),"
            + "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.contentId')),''),"
            + "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.nativeId')),''),"
            + "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`record_data`,'$.id')),''))=#{businessId} "
            + "ORDER BY `id` FOR SHARE")
    @InterceptorIgnore(tenantLine = "true") // tenant_id is explicit; JSqlParser otherwise moves ORDER BY after FOR SHARE
    List<SkitAdminRecordDO> selectDramaCatalogByBusinessIdForShare(
            @Param("tenantId") Long tenantId, @Param("businessId") String businessId);

    default PageResult<SkitAdminRecordDO> selectPage(SkitAdminRecordPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SkitAdminRecordDO>()
                .eq(SkitAdminRecordDO::getPageKey, reqVO.getPageKey())
                .eqIfPresent(SkitAdminRecordDO::getStatus, reqVO.getStatus())
                .likeIfPresent(SkitAdminRecordDO::getRecordData, reqVO.getKeyword())
                .orderByAsc(SkitAdminRecordDO::getSort)
                .orderByDesc(SkitAdminRecordDO::getId));
    }

    default Long selectCountByPageKey(String pageKey) {
        return selectCount(SkitAdminRecordDO::getPageKey, pageKey);
    }

}
