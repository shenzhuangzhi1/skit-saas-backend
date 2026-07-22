package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                .setConfigData("{\"placementId\":\"taku-slot\",\"adFormat\":\"rewarded_video\"}")
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
