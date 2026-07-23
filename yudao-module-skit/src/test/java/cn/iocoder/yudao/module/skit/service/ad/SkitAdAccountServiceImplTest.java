package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ACCOUNT_CONFIG_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ACCOUNT_REPORT_SCOPE_PENDING;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_PROVIDER_INVALID;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.PROVIDER_PANGLE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.PROVIDER_TAKU;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkitAdAccountServiceImplTest {

    @InjectMocks
    private SkitAdAccountServiceImpl accountService;
    @Mock
    private SkitAdAccountMapper accountMapper;
    @Mock
    private SkitPlatformAdminGuard platformAdminGuard;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setTenant() {
        TenantContextHolder.setTenantId(17L);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void ordinarySaveTrimsMetadataAndPreservesBlankWriteOnlyCredentials() {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE).setSecret("old-pangle-secret");
        SkitAdAccountDO taku = account(PROVIDER_TAKU).setAppKey("old-taku-key").setSecret("old-taku-secret");
        mockAccounts(pangle, taku);
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleUsername(" pangle-user ");
        settings.setPangleAppSecret("  ");
        settings.setTakuAppKey("");
        settings.setTakuAppSecret(null);

        accountService.saveSettings(settings);

        assertEquals("pangle-user", pangle.getAccountName());
        assertEquals("old-pangle-secret", pangle.getSecret());
        assertEquals("old-taku-key", taku.getAppKey());
        assertEquals("old-taku-secret", taku.getSecret());
        verify(accountMapper).updateById(pangle);
        verify(accountMapper).updateById(taku);
    }

    @Test
    void ordinaryUpdatePreservesExistingTakuDisplayPlacements() throws Exception {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE);
        SkitAdAccountDO taku = account(PROVIDER_TAKU)
                .setAccountName("taku-user")
                .setAppId("taku-app")
                .setAppKey("new-taku-key")
                .setConfigData("{\"placementId\":\"taku-slot\",\"adFormat\":\"rewarded_video\","
                        + "\"checkInEntryInterstitialPlacementId\":\"checkin-old\","
                        + "\"postCheckInDramaInterstitialPlacementId\":\"drama-old\","
                        + "\"homeBannerPlacementId\":\"banner-old\"}");
        mockAccounts(pangle, taku);
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleEnabled(false);
        settings.setCheckInEntryInterstitialPlacementId(null);
        settings.setPostCheckInDramaInterstitialPlacementId(null);
        settings.setHomeBannerPlacementId(null);

        accountService.saveSettings(settings);

        assertEquals("taku-slot", objectMapper.readTree(taku.getConfigData())
                .path("placementId").asText());
        assertEquals("rewarded_video", objectMapper.readTree(taku.getConfigData())
                .path("adFormat").asText());
        assertEquals("checkin-old", objectMapper.readTree(taku.getConfigData())
                .path("checkInEntryInterstitialPlacementId").asText());
        assertEquals("drama-old", objectMapper.readTree(taku.getConfigData())
                .path("postCheckInDramaInterstitialPlacementId").asText());
        assertEquals("banner-old", objectMapper.readTree(taku.getConfigData())
                .path("homeBannerPlacementId").asText());
        verify(accountMapper, never()).hasHistoricalTakuReportFacts(anyLong(), anyLong());
    }

    @Test
    void savesAndReadsIndependentTakuDisplayPlacementsWithoutChangingRewardScope() throws Exception {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE);
        SkitAdAccountDO taku = account(PROVIDER_TAKU)
                .setAccountName("taku-user")
                .setAppId("taku-app")
                .setAppKey("new-taku-key")
                .setConfigData("{\"placementId\":\"taku-slot\",\"adFormat\":\"rewarded_video\"}");
        mockAccounts(pangle, taku);
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleEnabled(false);
        objectMapper.readerForUpdating(settings).readValue("{"
                + "\"checkInEntryInterstitialPlacementId\":\" checkin-new \","
                + "\"postCheckInDramaInterstitialPlacementId\":\" drama-new \","
                + "\"homeBannerPlacementId\":\" banner-new \"}");

        accountService.saveSettings(settings);

        JsonNode stored = objectMapper.readTree(taku.getConfigData());
        assertEquals("taku-slot", stored.path("placementId").asText());
        assertEquals("rewarded_video", stored.path("adFormat").asText());
        assertEquals("checkin-new", stored.path("checkInEntryInterstitialPlacementId").asText());
        assertEquals("drama-new", stored.path("postCheckInDramaInterstitialPlacementId").asText());
        assertEquals("banner-new", stored.path("homeBannerPlacementId").asText());
        JsonNode roundTrip = objectMapper.valueToTree(accountService.getSettings());
        assertEquals("checkin-new", roundTrip.path("checkInEntryInterstitialPlacementId").asText());
        assertEquals("drama-new", roundTrip.path("postCheckInDramaInterstitialPlacementId").asText());
        assertEquals("banner-new", roundTrip.path("homeBannerPlacementId").asText());
        verify(accountMapper, never()).hasHistoricalTakuReportFacts(anyLong(), anyLong());
    }

    @Test
    void publicConfigPublishesOnlyNonBlankTakuDisplayPlacementsAndNoCredentials() throws Exception {
        SkitAdAccountDO taku = account(PROVIDER_TAKU)
                .setAccountName("private-user")
                .setAppId("public-app")
                .setAppKey("private-app-key")
                .setSecret("private-server-secret")
                .setConfigData("{\"placementId\":\"reward-slot\",\"adFormat\":\"rewarded_video\","
                        + "\"checkInEntryInterstitialPlacementId\":\"checkin-slot\","
                        + "\"postCheckInDramaInterstitialPlacementId\":\" \","
                        + "\"homeBannerPlacementId\":\"banner-slot\"}")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        when(accountMapper.selectList()).thenReturn(Collections.singletonList(taku));

        List<SkitAdAccountService.PublicConfig> result = accountService.getEnabledPublicConfigs();

        assertEquals(1, result.size());
        JsonNode published = objectMapper.valueToTree(result.get(0));
        assertEquals("reward-slot", published.path("placementId").asText());
        assertEquals("checkin-slot",
                published.path("checkInEntryInterstitialPlacementId").asText());
        assertTrue(published.path("postCheckInDramaInterstitialPlacementId").isNull());
        assertEquals("banner-slot", published.path("homeBannerPlacementId").asText());
        String serialized = objectMapper.writeValueAsString(result.get(0));
        assertFalse(serialized.contains("private-user"));
        assertFalse(serialized.contains("private-app-key"));
        assertFalse(serialized.contains("private-server-secret"));
    }

    @Test
    void disabledTakuAccountPublishesNoPublicConfig() {
        SkitAdAccountDO taku = account(PROVIDER_TAKU)
                .setConfigData("{\"placementId\":\"reward-slot\",\"adFormat\":\"rewarded_video\","
                        + "\"checkInEntryInterstitialPlacementId\":\"checkin-slot\","
                        + "\"postCheckInDramaInterstitialPlacementId\":\"drama-slot\","
                        + "\"homeBannerPlacementId\":\"banner-slot\"}")
                .setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(accountMapper.selectList()).thenReturn(Collections.singletonList(taku));

        assertTrue(accountService.getEnabledPublicConfigs().isEmpty());
    }

    @Test
    void enabledTakuRequiresAllThreeDisplayPlacementsOnEverySave() {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE);
        SkitAdAccountDO taku = account(PROVIDER_TAKU);
        mockAccounts(pangle, taku);
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleEnabled(false);
        settings.setCheckInEntryInterstitialPlacementId(" ");

        assertServiceException(() -> accountService.saveSettings(settings),
                AD_ACCOUNT_CONFIG_INVALID,
                "TAKU 启用前必须配置签到页插屏、签到后短剧插屏和首页 Banner 广告位");

        settings.setCheckInEntryInterstitialPlacementId("checkin-slot");
        settings.setPostCheckInDramaInterstitialPlacementId(null);
        assertServiceException(() -> accountService.saveSettings(settings),
                AD_ACCOUNT_CONFIG_INVALID,
                "TAKU 启用前必须配置签到页插屏、签到后短剧插屏和首页 Banner 广告位");

        settings.setPostCheckInDramaInterstitialPlacementId("drama-slot");
        settings.setHomeBannerPlacementId("");
        assertServiceException(() -> accountService.saveSettings(settings),
                AD_ACCOUNT_CONFIG_INVALID,
                "TAKU 启用前必须配置签到页插屏、签到后短剧插屏和首页 Banner 广告位");
        verify(accountMapper, never()).updateById(taku);
    }

    @Test
    void enabledTakuRequiresRewardAndDisplayPlacementsToBeMutuallyDistinct() {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE);
        SkitAdAccountDO taku = account(PROVIDER_TAKU);
        mockAccounts(pangle, taku);
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleEnabled(false);
        settings.setCheckInEntryInterstitialPlacementId(" taku-slot ");

        assertServiceException(() -> accountService.saveSettings(settings),
                AD_ACCOUNT_CONFIG_INVALID,
                "TAKU 激励视频、签到页插屏、签到后短剧插屏和首页 Banner 必须使用不同广告位");

        settings.setCheckInEntryInterstitialPlacementId("checkin-slot");
        settings.setPostCheckInDramaInterstitialPlacementId(" banner-slot ");
        assertServiceException(() -> accountService.saveSettings(settings),
                AD_ACCOUNT_CONFIG_INVALID,
                "TAKU 激励视频、签到页插屏、签到后短剧插屏和首页 Banner 必须使用不同广告位");
        verify(accountMapper, never()).updateById(taku);
    }

    @Test
    void takuPlacementIdentifiersMustMatchTheRuntimeContract() {
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setHomeBannerPlacementId("banner slot with spaces");

        assertServiceException(() -> accountService.saveSettings(settings),
                AD_ACCOUNT_CONFIG_INVALID,
                "TAKU 展示广告位 ID 仅支持字母、数字、点、下划线、冒号和连字符");
        verifyNoInteractions(accountMapper);
    }

    @Test
    void unrelatedSaveDoesNotForceLegacyEnabledTakuToAddDisplayPlacements() {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE)
                .setAccountName("pangle-user")
                .setAppId("pangle-app")
                .setSecret("old-pangle-secret")
                .setConfigData("{\"placementId\":\"pangle-slot\",\"adFormat\":\"rewarded_video\"}")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        SkitAdAccountDO taku = account(PROVIDER_TAKU)
                .setAccountName("taku-user")
                .setAppId("taku-app")
                .setAppKey("new-taku-key")
                .setConfigData("{\"placementId\":\"taku-slot\",\"adFormat\":\"rewarded_video\"}")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        mockAccounts(pangle, taku);
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleAppSecret("rotated-pangle-secret");
        settings.setCheckInEntryInterstitialPlacementId("");
        settings.setPostCheckInDramaInterstitialPlacementId("");
        settings.setHomeBannerPlacementId("");

        accountService.saveSettings(settings);

        assertEquals("rotated-pangle-secret", pangle.getSecret());
        verify(accountMapper).updateById(pangle);
        verify(accountMapper, never()).updateById(taku);
    }

    @Test
    void legacyEnabledTakuConfigurationRemainsReadableWithoutDisplayPlacements() {
        SkitAdAccountDO legacyTaku = account(PROVIDER_TAKU)
                .setAppId("legacy-app")
                .setAppKey("legacy-key")
                .setConfigData("{\"placementId\":\"legacy-reward\","
                        + "\"adFormat\":\"rewarded_video\"}")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        when(accountMapper.selectByProvider(PROVIDER_PANGLE)).thenReturn(null);
        when(accountMapper.selectByProvider(PROVIDER_TAKU)).thenReturn(legacyTaku);

        SkitAdAccountService.Settings settings = accountService.getSettings();

        assertEquals("legacy-reward", settings.getTakuPlacementId());
        assertEquals("", settings.getCheckInEntryInterstitialPlacementId());
        assertEquals("", settings.getPostCheckInDramaInterstitialPlacementId());
        assertEquals("", settings.getHomeBannerPlacementId());
        assertTrue(settings.getTakuEnabled());
    }

    @Test
    void changingTakuReportScopeLocksTheAccountAndFailsClosedWhileSettlementIsPending() {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE);
        SkitAdAccountDO taku = account(PROVIDER_TAKU).setAppId("old-taku-app")
                .setConfigData("{\"placementId\":\"old-slot\",\"adFormat\":\"rewarded_video\"}");
        mockAccounts(pangle, taku);
        when(accountMapper.hasHistoricalTakuReportFacts(taku.getTenantId(), taku.getId()))
                .thenReturn(true);
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleEnabled(false);
        settings.setTakuAppId("new-taku-app");

        assertServiceException(() -> accountService.saveSettings(settings),
                AD_ACCOUNT_REPORT_SCOPE_PENDING);

        verify(accountMapper, times(2)).selectByProviderForUpdate(17L, PROVIDER_TAKU);
        verify(accountMapper).hasHistoricalTakuReportFacts(taku.getTenantId(), taku.getId());
        verify(accountMapper, never()).updateById(taku);
    }

    @Test
    void pendingSettlementDoesNotBlockCredentialRotationWhenTakuReportScopeIsUnchanged() {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE);
        SkitAdAccountDO taku = account(PROVIDER_TAKU).setAccountName("old-taku-user")
                .setAppId("taku-app").setAppKey("old-taku-key")
                .setConfigData("{\"placementId\":\"taku-slot\",\"adFormat\":\"rewarded_video\"}");
        mockAccounts(pangle, taku);
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleEnabled(false);
        settings.setTakuAppKey("rotated-client-key");

        accountService.saveSettings(settings);

        assertEquals("rotated-client-key", taku.getAppKey());
        verify(accountMapper, never()).hasHistoricalTakuReportFacts(anyLong(), anyLong());
        verify(accountMapper).updateById(taku);
    }

    @Test
    void rotatingPangleServerKeyDoesNotRewriteUnchangedTakuAccount() {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE)
                .setAccountName("pangle-user")
                .setAppId("pangle-app")
                .setSecret("old-pangle-secret")
                .setConfigData("{\"placementId\":\"pangle-slot\",\"adFormat\":\"rewarded_video\"}")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        SkitAdAccountDO taku = account(PROVIDER_TAKU)
                .setAccountName("taku-user")
                .setAppId("taku-app")
                .setAppKey("old-taku-key")
                .setSecret("old-taku-secret")
                .setConfigData("{\"placementId\":\"taku-slot\",\"adFormat\":\"rewarded_video\","
                        + "\"checkInEntryInterstitialPlacementId\":\"checkin-slot\","
                        + "\"postCheckInDramaInterstitialPlacementId\":\"drama-slot\","
                        + "\"homeBannerPlacementId\":\"banner-slot\"}")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
        mockAccounts(pangle, taku);
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleAppSecret("new-pangle-secret");
        settings.setTakuAppKey("");
        settings.setTakuAppSecret(null);

        accountService.saveSettings(settings);

        assertEquals("new-pangle-secret", pangle.getSecret());
        assertEquals("old-taku-key", taku.getAppKey());
        assertEquals("old-taku-secret", taku.getSecret());
        verify(accountMapper).updateById(pangle);
        verify(accountMapper, never()).updateById(taku);
    }

    @Test
    void takuAccountWithoutHistoricalFactsMayChangeItsReportScopeAfterTheLockedGuardPasses() throws Exception {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE);
        SkitAdAccountDO taku = account(PROVIDER_TAKU).setAppId("old-taku-app")
                .setConfigData("{\"placementId\":\"old-slot\",\"adFormat\":\"rewarded_video\"}");
        mockAccounts(pangle, taku);
        when(accountMapper.hasHistoricalTakuReportFacts(taku.getTenantId(), taku.getId()))
                .thenReturn(false);
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleEnabled(false);

        accountService.saveSettings(settings);

        assertEquals("taku-app", taku.getAppId());
        assertEquals("taku-slot", objectMapper.readTree(taku.getConfigData())
                .get("placementId").asText());
        verify(accountMapper).hasHistoricalTakuReportFacts(taku.getTenantId(), taku.getId());
        verify(accountMapper).updateById(taku);
    }

    @Test
    void enabledPangleRequiresCompleteEffectiveConfiguration() {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE);
        mockAccounts(pangle, account(PROVIDER_TAKU));
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleAppSecret(null);

        assertServiceException(() -> accountService.saveSettings(settings), AD_ACCOUNT_CONFIG_INVALID,
                "PANGLE 启用前必须配置 App ID 和内容接口 Server Key");

        verify(accountMapper, never()).updateById(pangle);
    }

    @Test
    void enabledPangleIgnoresLegacyAccountAndPlacementFields() throws Exception {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE);
        mockAccounts(pangle, account(PROVIDER_TAKU));
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleUsername(null);
        settings.setPanglePlacementId(null);
        settings.setTakuEnabled(false);

        accountService.saveSettings(settings);

        assertEquals("", pangle.getAccountName());
        assertEquals("", objectMapper.readTree(pangle.getConfigData()).get("placementId").asText());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), pangle.getStatus());
    }

    @Test
    void enabledTakuAllowsOptionalServerSecretButRequiresClientAppKey() {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE);
        SkitAdAccountDO taku = account(PROVIDER_TAKU);
        mockAccounts(pangle, taku);
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleEnabled(false);
        settings.setTakuUsername(null);
        settings.setTakuAppSecret(null);

        accountService.saveSettings(settings);

        assertEquals("new-taku-key", taku.getAppKey());
        assertEquals("", taku.getSecret());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), taku.getStatus());
    }

    @Test
    void disabledProvidersMayRemainIncomplete() {
        SkitAdAccountDO pangle = account(PROVIDER_PANGLE);
        SkitAdAccountDO taku = account(PROVIDER_TAKU);
        mockAccounts(pangle, taku);
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setPangleEnabled(false);
        settings.setTakuEnabled(false);

        accountService.saveSettings(settings);

        assertEquals(CommonStatusEnum.DISABLE.getStatus(), pangle.getStatus());
        assertEquals(CommonStatusEnum.DISABLE.getStatus(), taku.getStatus());
    }

    @Test
    void ensureDefaultAccountsBindsNewRowsToTheActiveTenant() {
        when(accountMapper.selectByProviderForUpdate(17L, PROVIDER_PANGLE)).thenReturn(null);
        when(accountMapper.selectByProviderForUpdate(17L, PROVIDER_TAKU)).thenReturn(null);

        accountService.ensureDefaultAccounts();

        org.mockito.ArgumentCaptor<SkitAdAccountDO> captor =
                org.mockito.ArgumentCaptor.forClass(SkitAdAccountDO.class);
        verify(accountMapper, times(2)).insert(captor.capture());
        List<SkitAdAccountDO> inserted = captor.getAllValues();
        assertEquals(2, inserted.size());
        inserted.forEach(account -> {
            assertNotNull(account.getTenantId());
            assertEquals(17L, account.getTenantId());
        });
    }

    @Test
    void saveRejectsOversizedFieldsBeforeAnyDatabaseMutation() {
        SkitAdAccountService.Settings settings = completeSettings();
        settings.setPangleUsername(String.join("", Collections.nCopies(129, "x")));

        assertServiceException(() -> accountService.saveSettings(settings), AD_ACCOUNT_CONFIG_INVALID,
                "PANGLE 账号最长 128 个字符");

        verifyNoInteractions(accountMapper);
    }

    @Test
    void explicitPangleClearRemovesOnlyPangleSecretAndForcesDisabled() {
        when(accountMapper.selectByProvider(PROVIDER_PANGLE)).thenReturn(account(PROVIDER_PANGLE));

        accountService.clearCredentials(PROVIDER_PANGLE);

        verify(accountMapper).clearPangleCredentials();
        verify(accountMapper, never()).clearTakuCredentials();
    }

    @Test
    void explicitTakuClearRemovesAppKeyAndSecretAndForcesDisabled() {
        when(accountMapper.selectByProvider(PROVIDER_TAKU)).thenReturn(account(PROVIDER_TAKU));

        accountService.clearCredentials(" taku ");

        verify(accountMapper).clearTakuCredentials();
        verify(accountMapper, never()).clearPangleCredentials();
    }

    @Test
    void explicitClearRejectsNullBlankAndUnknownProviderBeforeDatabaseAccess() {
        assertServiceException(() -> accountService.clearCredentials(null), AD_PROVIDER_INVALID);
        assertServiceException(() -> accountService.clearCredentials("   "), AD_PROVIDER_INVALID);
        assertServiceException(() -> accountService.clearCredentials("UNKNOWN"), AD_PROVIDER_INVALID);

        verifyNoInteractions(accountMapper);
    }

    private SkitAdAccountService.Settings completeSettings() {
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setPangleUsername("pangle-user");
        settings.setPangleAppId("pangle-app");
        settings.setPangleAppSecret("new-pangle-secret");
        settings.setPanglePlacementId("pangle-slot");
        settings.setPangleEnabled(true);
        settings.setTakuUsername("taku-user");
        settings.setTakuAppId("taku-app");
        settings.setTakuAppKey("new-taku-key");
        settings.setTakuPlacementId("taku-slot");
        settings.setCheckInEntryInterstitialPlacementId("checkin-slot");
        settings.setPostCheckInDramaInterstitialPlacementId("drama-slot");
        settings.setHomeBannerPlacementId("banner-slot");
        settings.setTakuEnabled(true);
        return settings;
    }

    private SkitAdAccountDO account(String provider) {
        SkitAdAccountDO account = SkitAdAccountDO.builder()
                .id(PROVIDER_PANGLE.equals(provider) ? 1L : 2L)
                .provider(provider).accountName("").appId("").appKey("").secret("")
                .configData("{\"placementId\":\"old-slot\"}")
                .status(CommonStatusEnum.DISABLE.getStatus()).build();
        account.setTenantId(17L);
        return account;
    }

    private void mockAccounts(SkitAdAccountDO pangle, SkitAdAccountDO taku) {
        lenient().when(accountMapper.selectByProvider(PROVIDER_PANGLE)).thenReturn(pangle);
        lenient().when(accountMapper.selectByProvider(PROVIDER_TAKU)).thenReturn(taku);
        lenient().when(accountMapper.selectByProviderForUpdate(17L, PROVIDER_PANGLE))
                .thenReturn(pangle);
        lenient().when(accountMapper.selectByProviderForUpdate(17L, PROVIDER_TAKU))
                .thenReturn(taku);
    }
}
