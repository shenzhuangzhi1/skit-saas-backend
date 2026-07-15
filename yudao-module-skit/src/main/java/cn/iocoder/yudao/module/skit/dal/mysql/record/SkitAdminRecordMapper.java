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

import java.util.List;

@Mapper
public interface SkitAdminRecordMapper extends BaseMapperX<SkitAdminRecordDO> {

    @Insert({"<script>",
            "INSERT INTO `skit_admin_record` (`tenant_id`,`page_key`,`row_key`,`record_data`,",
            "`status`,`sort`,`creator`,`updater`) VALUES",
            "<foreach collection='records' item='record' separator=','>",
            "(#{tenantId},#{record.pageKey},#{record.rowKey},#{record.recordData},",
            "#{record.status},#{record.sort},'page-seed','page-seed')",
            "</foreach>",
            "ON DUPLICATE KEY UPDATE `id`=`id`",
            "</script>"})
    @InterceptorIgnore(tenantLine = "true") // tenant_id is required and bound explicitly for every row
    int insertSeedBatchIfAbsent(@Param("tenantId") Long tenantId,
                                @Param("records") List<SkitAdminRecordDO> records);

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

    default List<SkitAdminRecordDO> selectListByPageKey(String pageKey) {
        return selectList(new LambdaQueryWrapperX<SkitAdminRecordDO>()
                .eq(SkitAdminRecordDO::getPageKey, pageKey)
                .orderByAsc(SkitAdminRecordDO::getSort)
                .orderByDesc(SkitAdminRecordDO::getId));
    }

}
