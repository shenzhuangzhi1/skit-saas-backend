package cn.iocoder.yudao.module.skit.dal.mysql;

import cn.iocoder.yudao.framework.mybatis.core.type.EncryptTypeHandler;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdReportScopeGuardPersistenceContractTest {

    @Test
    void reportScopeMutationUsesARowLockAndChecksOnlyTheLatestUnsettledRevisions() throws Exception {
        Method lock = SkitAdAccountMapper.class.getMethod(
                "selectByProviderForUpdate", long.class, String.class);
        String lockSql = sql(lock).toLowerCase(Locale.ROOT);
        assertTrue(lockSql.contains("for update"));
        assertTrue(lockSql.contains("provider"));

        Method pending = SkitAdAccountMapper.class.getMethod(
                "hasUnsettledTakuReportScope", long.class, long.class);
        String pendingSql = sql(pending).toLowerCase(Locale.ROOT);
        assertTrue(pendingSql.contains("skit_ad_revenue_event"));
        assertTrue(pendingSql.contains("skit_ad_reconciliation_revision"));
        assertTrue(pendingSql.contains("reconciliation_revision_id"));
        assertTrue(pendingSql.contains("final_revision"));
        assertTrue(pendingSql.contains("status") && pendingSql.contains("applied"));
        assertTrue(pendingSql.contains("not exists"));
        assertTrue(pendingSql.contains("revision_no"));
        assertTrue(pendingSql.contains("tenant_id") && pendingSql.contains("ad_account_id"));
        assertTrue(pendingSql.contains("skit_ad_report_pull"));
        assertTrue(pendingSql.contains("final_window"));
        assertTrue(pendingSql.contains("report_date"));
        assertTrue(pendingSql.contains("report_timezone"));
        assertTrue(pendingSql.contains("currency") && pendingSql.contains("amount_scale"));
    }

    @Test
    void formalConfigurationTreatsAnyHistoricalEventOrPullAsAnImmutableScopeBoundary()
            throws Exception {
        Method historical = SkitAdAccountMapper.class.getMethod(
                "hasHistoricalTakuReportFacts", long.class, long.class);
        String sql = sql(historical).toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("skit_ad_revenue_event"));
        assertTrue(sql.contains("skit_ad_report_pull"));
        assertTrue(sql.contains("tenant_id") && sql.contains("ad_account_id"));
        assertTrue(sql.contains("provider") && sql.contains("taku"));
    }

    @Test
    void providerRowLockUsesTheEncryptedEntityResultMap() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                configuration, "skit-ad-account-lock-result-map-test");
        assistant.setCurrentNamespace(SkitAdAccountMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, SkitAdAccountDO.class);
        configuration.addMapper(SkitAdAccountMapper.class);

        ResultMap resultMap = configuration.getMappedStatement(
                        SkitAdAccountMapper.class.getName() + ".selectByProviderForUpdate")
                .getResultMaps().get(0);

        assertEncryptedMapping(resultMap, "appKey");
        assertEncryptedMapping(resultMap, "secret");
    }

    private void assertEncryptedMapping(ResultMap resultMap, String property) {
        ResultMapping mapping = resultMap.getResultMappings().stream()
                .filter(candidate -> property.equals(candidate.getProperty()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing encrypted result mapping for " + property));
        assertEquals(EncryptTypeHandler.class, mapping.getTypeHandler().getClass());
    }

    private String sql(Method method) {
        Select select = method.getAnnotation(Select.class);
        assertTrue(select != null, method.getName() + " must remain explicit SQL");
        return String.join(" ", select.value()).replaceAll("\\s+", " ");
    }
}
