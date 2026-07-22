package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdNetworkCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitTenantAdCapabilityMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkitRewardAuthorityPolicyTest {

    private static final long TENANT_ID = 42L;
    private static final long ACCOUNT_ID = 4201L;
    private static final int NETWORK_ID = 46;
    private static final String PLACEMENT = "reward-placement";
    private static final String ADSOURCE = "source-46";
    private static final String SESSION_ID = "0123456789abcdefghijkl";
    private static final String SHOW_ID = "show-46";
    private static final String TRANSACTION_ID = "reward-transaction-46";
    private static final byte[] SECRET = "taku-reward-secret-32-bytes-value"
            .getBytes(StandardCharsets.US_ASCII);
    private static final LocalDateTime RECEIVED_AT = LocalDateTime.of(2026, 7, 22, 8, 0);

    private SkitTenantAdCapabilityMapper tenantCapabilityMapper;
    private SkitAdNetworkCapabilityMapper networkCapabilityMapper;
    private SkitRewardAuthorityPolicy policy;
    private SkitAdSessionDO session;
    private TakuRewardSignatureVerifier.SignedRewardAuthority authority;

    @BeforeEach
    void setUp() {
        tenantCapabilityMapper = mock(SkitTenantAdCapabilityMapper.class);
        networkCapabilityMapper = mock(SkitAdNetworkCapabilityMapper.class);
        policy = new SkitRewardAuthorityPolicy(tenantCapabilityMapper, networkCapabilityMapper);
        session = session();
        authority = signedAuthority(NETWORK_ID);
        when(tenantCapabilityMapper.selectByTenantForShare(TENANT_ID))
                .thenReturn(configuration("[22,46,66]"));
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, NETWORK_ID))
                .thenReturn(capability());
    }

    @Test
    void signedSelectedVerifiedDynamicNetworkIsAuthorized() {
        SkitRewardAuthorityPolicy.Decision decision = policy.authorize(
                SkitRewardAuthorityPolicy.Context.ingress(
                        TENANT_ID, ACCOUNT_ID, session, authority, NETWORK_ID, RECEIVED_AT));

        assertTrue(decision.isAuthorized());
        assertEquals(NETWORK_ID, decision.getNetworkFirmId());
    }

    @Test
    void unverifiedCrossTenantCrossAccountOrUnselectedCapabilityIsRejected() {
        SkitAdNetworkCapabilityDO unverified = capability().setVerifiedAt(null);
        SkitAdNetworkCapabilityDO wrongTenant = capability();
        wrongTenant.setTenantId(TENANT_ID + 1);
        SkitAdNetworkCapabilityDO[] rejected = {
                unverified,
                wrongTenant,
                capability().setAdAccountId(ACCOUNT_ID + 1)
        };
        for (SkitAdNetworkCapabilityDO candidate : rejected) {
            when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, NETWORK_ID))
                    .thenReturn(candidate);
            assertFalse(policy.authorize(SkitRewardAuthorityPolicy.Context.ingress(
                    TENANT_ID, ACCOUNT_ID, session, authority, NETWORK_ID, RECEIVED_AT)).isAuthorized());
        }

        when(tenantCapabilityMapper.selectByTenantForShare(TENANT_ID))
                .thenReturn(configuration("[22,66]"));
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, NETWORK_ID))
                .thenReturn(capability());
        SkitRewardAuthorityPolicy.Decision unselected = policy.authorize(
                SkitRewardAuthorityPolicy.Context.ingress(
                        TENANT_ID, ACCOUNT_ID, session, authority, NETWORK_ID, RECEIVED_AT));
        assertFalse(unselected.isAuthorized());
        assertEquals("SIGNED_NETWORK_NOT_SELECTED", unselected.getErrorCode());
    }

    @Test
    void topLevelNetworkCannotSpoofSignedIlrdAuthority() {
        SkitRewardAuthorityPolicy.Decision decision = policy.authorize(
                SkitRewardAuthorityPolicy.Context.ingress(
                        TENANT_ID, ACCOUNT_ID, session, authority, 66, RECEIVED_AT));

        assertFalse(decision.isAuthorized());
        assertEquals("TOP_LEVEL_NETWORK_MISMATCH", decision.getErrorCode());
    }

    @Test
    void placementShowSessionDramaAndExactEpisodeRemainBound() {
        assertFalse(authorize(session().setPlacementId("other-placement")).isAuthorized());
        assertFalse(authorize(session().setProviderShowId("other-show")).isAuthorized());
        assertFalse(authorize(session().setSessionId("zyxwvutsrqponmlkjihgfe")).isAuthorized());
        assertFalse(authorize(session().setDramaId(null)).isAuthorized());
        assertFalse(authorize(session().setEpisodeTo(4)).isAuthorized());
        assertFalse(authorize(session().setUnlockScope("drama:801:episode:4")).isAuthorized());
    }

    @Test
    void asynchronousEnvelopeMustMatchTheSameSignedAuthority() {
        SkitAdCallbackInboxDO inbox = inbox();
        assertTrue(policy.authorize(SkitRewardAuthorityPolicy.Context.processing(
                inbox, session, authority, NETWORK_ID)).isAuthorized());

        inbox.setAdsourceId("spoofed-source");
        SkitRewardAuthorityPolicy.Decision rejected = policy.authorize(
                SkitRewardAuthorityPolicy.Context.processing(inbox, session, authority, NETWORK_ID));
        assertFalse(rejected.isAuthorized());
        assertEquals("SIGNED_REWARD_AUTHORITY_MISMATCH", rejected.getErrorCode());
    }

    private SkitRewardAuthorityPolicy.Decision authorize(SkitAdSessionDO candidate) {
        return policy.authorize(SkitRewardAuthorityPolicy.Context.ingress(
                TENANT_ID, ACCOUNT_ID, candidate, authority, NETWORK_ID, RECEIVED_AT));
    }

    private SkitTenantAdCapabilityDO configuration(String networks) {
        SkitTenantAdCapabilityDO row = new SkitTenantAdCapabilityDO().setId(7L)
                .setAdAccountId(ACCOUNT_ID).setRolloutState("SHADOW_TEST_USERS")
                .setDedicatedUnlockPlacementId(PLACEMENT).setUnlockNetworkFirmIdsJson(networks)
                .setReadinessVersion(3);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitAdNetworkCapabilityDO capability() {
        SkitAdNetworkCapabilityDO row = new SkitAdNetworkCapabilityDO().setId(9L)
                .setAdAccountId(ACCOUNT_ID).setNetworkFirmId(NETWORK_ID)
                .setRewardAuthority("SIGNED_REWARD").setSupportsUserId(true)
                .setSupportsCustomData(true).setSupportsStableTransaction(true)
                .setSupportsImpressionRevenue(true).setSupportsReporting(true)
                .setEnabled(true).setVerifiedAt(RECEIVED_AT.minusDays(1));
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitAdSessionDO session() {
        SkitAdSessionDO row = new SkitAdSessionDO().setId(55L).setSessionId(SESSION_ID)
                .setAdAccountId(ACCOUNT_ID).setProvider("TAKU").setPlacementId(PLACEMENT)
                .setProviderShowId(SHOW_ID).setBusinessType("EPISODE_UNLOCK")
                .setDramaId(801L).setEpisodeFrom(3).setEpisodeTo(3)
                .setUnlockScope("drama:801:episode:3");
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitAdCallbackInboxDO inbox() {
        SkitAdCallbackInboxDO row = new SkitAdCallbackInboxDO().setId(91L)
                .setAdAccountId(ACCOUNT_ID).setAdSessionId(55L)
                .setProviderTransactionId(TRANSACTION_ID).setProviderShowId(SHOW_ID)
                .setPlacementId(PLACEMENT).setAdsourceId(ADSOURCE).setNetworkFirmId(NETWORK_ID)
                .setReceivedAt(RECEIVED_AT);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private TakuRewardSignatureVerifier.SignedRewardAuthority signedAuthority(int networkFirmId) {
        String ilrd = "{\"network_firm_id\":" + networkFirmId
                + ",\"adsource_id\":\"" + ADSOURCE + "\",\"id\":\"" + SHOW_ID
                + "\",\"adunit_id\":\"" + PLACEMENT + "\",\"show_custom_ext\":\""
                + SESSION_ID + "\"}";
        String preimage = "trans_id=" + TRANSACTION_ID + "&placement_id=" + PLACEMENT
                + "&adsource_id=" + ADSOURCE + "&reward_amount=1&reward_name=coin&sec_key="
                + new String(SECRET, StandardCharsets.US_ASCII) + "&ilrd=" + ilrd;
        String rawQuery = "user_id=member&trans_id=" + TRANSACTION_ID + "&placement_id=" + PLACEMENT
                + "&adsource_id=" + ADSOURCE + "&reward_amount=1&reward_name=coin&extra_data=data"
                + "&network_firm_id=" + networkFirmId + "&sign=" + md5(preimage)
                + "&ilrd=" + encode(ilrd);
        TakuRewardCallback callback = new TakuCallbackCanonicalizer().canonicalizeReward(rawQuery);
        TakuRewardSignatureVerifier.VerificationResult verified =
                new TakuRewardSignatureVerifier(new ObjectMapper()).verify(callback, SECRET);
        assertTrue(verified.hasSignedRewardAuthority());
        return verified.getAuthority();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
