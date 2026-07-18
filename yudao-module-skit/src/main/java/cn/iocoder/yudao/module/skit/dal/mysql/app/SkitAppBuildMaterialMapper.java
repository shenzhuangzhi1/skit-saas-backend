package cn.iocoder.yudao.module.skit.dal.mysql.app;

import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppBuildMaterialDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
@InterceptorIgnore(tenantLine = "true") // Every statement binds tenant_id explicitly, including locking reads.
public interface SkitAppBuildMaterialMapper {

    @Select("SELECT * FROM `skit_app_build_material` WHERE `tenant_id`=#{tenantId} "
            + "AND `active`=b'1' AND `deleted`=b'0' LIMIT 1")
    SkitAppBuildMaterialDO selectActive(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM `skit_app_build_material` WHERE `tenant_id`=#{tenantId} "
            + "AND `active`=b'1' AND `deleted`=b'0' LIMIT 1 FOR UPDATE")
    SkitAppBuildMaterialDO selectActiveForUpdate(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM `skit_app_build_material` WHERE `tenant_id`=#{tenantId} "
            + "AND `deleted`=b'0' ORDER BY `material_version` DESC LIMIT 1 FOR UPDATE")
    SkitAppBuildMaterialDO selectLatestForUpdate(@Param("tenantId") Long tenantId);

    @Select("SELECT COALESCE(MAX(`material_version`),0) FROM `skit_app_build_material` "
            + "WHERE `tenant_id`=#{tenantId}")
    Integer selectMaxVersion(@Param("tenantId") Long tenantId);

    @Insert("INSERT INTO `skit_app_build_material` "
            + "(`tenant_id`,`material_version`,`api_base_url`,`app_name`,`native_version_code`,"
            + "`native_version_name`,`runtime_release_no`,`secret_ciphertext`,`secret_nonce`,"
            + "`encryption_key_id`,`envelope_version`,`active`,`verified_at`,`creator`,`updater`) VALUES "
            + "(#{tenantId},#{materialVersion},#{apiBaseUrl},#{appName},#{nativeVersionCode},"
            + "#{nativeVersionName},#{runtimeReleaseNo},#{secretCiphertext},#{secretNonce},"
            + "#{encryptionKeyId},#{envelopeVersion},b'1',#{verifiedAt},'app-build-material','app-build-material')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SkitAppBuildMaterialDO row);

    @Update("UPDATE `skit_app_build_material` SET `active`=b'0',"
            + "`updater`='app-build-material-rotation',`update_time`=CURRENT_TIMESTAMP "
            + "WHERE `tenant_id`=#{tenantId} AND `id`=#{id} AND `active`=b'1' AND `deleted`=b'0'")
    int retireActive(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
