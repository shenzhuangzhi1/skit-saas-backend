package cn.iocoder.yudao.module.skit.dal.mysql;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitPangleRewardAttestationDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitPangleRewardAttestationMapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitPangleRewardAttestationPersistenceContractTest {

    @Test
    void sessionAndAttestationObjectsExposeOnlyTheRequiredEvidence() throws Exception {
        for (String field : Arrays.asList("pangleAdAccountId", "pangleRewardSecretVersion",
                "pangleRewardPlacementId")) {
            assertNotNull(SkitAdSessionDO.class.getDeclaredField(field));
        }
        for (String field : Arrays.asList(
                "id", "takuAdAccountId", "pangleAdAccountId", "adSessionId",
                "callbackKeyVersion", "pangleRewardSecretVersion",
                "pangleRewardPlacementId", "provider", "providerTransactionId",
                "providerUserId", "extraDataHash", "rewardName", "rewardAmount",
                "canonicalPayloadHash", "credentialFingerprint", "receivedAt")) {
            assertNotNull(SkitPangleRewardAttestationDO.class.getDeclaredField(field),
                    "missing attestation field " + field);
        }
        for (String field : Arrays.asList(
                "extraDataHash", "canonicalPayloadHash", "credentialFingerprint")) {
            Field declared = SkitPangleRewardAttestationDO.class.getDeclaredField(field);
            assertNotNull(declared.getAnnotation(JsonIgnore.class), field + " must not serialize");
        }
    }

    @Test
    void mapperIsAppendOnlyAndEveryLookupIsTenantAndAccountBound() throws Exception {
        assertFalse(BaseMapper.class.isAssignableFrom(SkitPangleRewardAttestationMapper.class));
        assertNotNull(SkitPangleRewardAttestationMapper.class
                .getMethod("insert", SkitPangleRewardAttestationDO.class)
                .getAnnotation(Insert.class));

        Method bySession = SkitPangleRewardAttestationMapper.class.getMethod(
                "selectBySession", Long.class, Long.class, Long.class);
        assertSql(bySession, "tenant_id=#{tenantid}", "taku_ad_account_id=#{takuadaccountid}",
                "ad_session_id=#{adsessionid}");

        Method byTransaction = SkitPangleRewardAttestationMapper.class.getMethod(
                "selectByTransactionId", Long.class, Long.class, String.class);
        assertSql(byTransaction, "tenant_id=#{tenantid}",
                "pangle_ad_account_id=#{pangleadaccountid}",
                "provider_transaction_id=#{providertransactionid}");
    }

    private static void assertSql(Method method, String... fragments) {
        Select annotation = method.getAnnotation(Select.class);
        assertNotNull(annotation, method.toString());
        String sql = String.join(" ", annotation.value()).toLowerCase(Locale.ROOT)
                .replace("`", "").replaceAll("\\s+", " ");
        for (String fragment : fragments) {
            assertTrue(sql.contains(fragment), () -> "missing '" + fragment + "' in " + sql);
        }
    }

}
