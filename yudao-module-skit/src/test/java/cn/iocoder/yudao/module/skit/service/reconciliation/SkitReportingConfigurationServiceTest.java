package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ACCOUNT_REPORT_SCOPE_PENDING;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_REPORTING_CONFIG_VERSION_CONFLICT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitReportingConfigurationServiceTest {

    private static final long TENANT_ID = 17L;
    private static final long ACCOUNT_ID = 29L;

    private SkitAdAccountMapper accountMapper;
    private SkitReportingCredentialService credentialService;
    private SkitReportingConfigurationService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
        accountMapper = mock(SkitAdAccountMapper.class);
        credentialService = mock(SkitReportingCredentialService.class);
        service = new SkitReportingConfigurationServiceImpl(
                accountMapper, credentialService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void writeOnlyPublisherKeyRotatesCredentialAndReturnsMetadataOnly() {
        SkitAdAccountDO account = account();
        when(accountMapper.selectByProviderForUpdate(TENANT_ID, "TAKU")).thenReturn(account);
        when(accountMapper.updateById(account)).thenReturn(1);
        when(credentialService.getMetadata(TENANT_ID, ACCOUNT_ID)).thenReturn(metadata(4));
        AtomicReference<byte[]> configuredPublisherKey = new AtomicReference<>();
        doAnswer(invocation -> {
            assertEquals(TENANT_ID, ((Long) invocation.getArgument(0)).longValue());
            assertEquals(ACCOUNT_ID, ((Long) invocation.getArgument(1)).longValue());
            configuredPublisherKey.set(((byte[]) invocation.getArgument(2)).clone());
            return metadata(5);
        }).when(credentialService).configure(anyLong(), anyLong(), any(byte[].class));
        SkitReportingConfigurationService.Command command = command();
        command.setPublisherKey("new-publisher");

        SkitReportingConfigurationService.View result = service.configure(command);

        assertEquals(5, result.getCredentialVersion());
        assertEquals("UTC+8", result.getReportTimezone());
        assertEquals("USD", result.getCurrency());
        assertEquals(6, result.getAmountScale());
        assertEquals("rewarded_video", result.getAdFormat());
        assertFalse(result.toString().contains("new-publisher"));
        assertArrayEquals("new-publisher".getBytes(), configuredPublisherKey.get());
        verify(accountMapper).updateById(account);
        verify(credentialService).configure(anyLong(), anyLong(), any(byte[].class));
    }

    @Test
    void anyHistoricalFinancialFactBlocksInPlaceScopeMutationEvenAfterFinalSettlement() {
        SkitAdAccountDO account = account();
        when(accountMapper.selectByProviderForUpdate(TENANT_ID, "TAKU")).thenReturn(account);
        when(credentialService.getMetadata(TENANT_ID, ACCOUNT_ID)).thenReturn(metadata(4));
        when(accountMapper.hasHistoricalTakuReportFacts(TENANT_ID, ACCOUNT_ID)).thenReturn(true);
        SkitReportingConfigurationService.Command command = command();
        command.setReportTimezone("UTC+0");

        assertServiceException(() -> service.configure(command), AD_ACCOUNT_REPORT_SCOPE_PENDING);

        verify(accountMapper, never()).updateById(any(SkitAdAccountDO.class));
        verify(credentialService, never()).configure(anyLong(), anyLong(), any());
    }

    @Test
    void optimisticCredentialVersionPreventsConcurrentOverwrite() {
        when(accountMapper.selectByProviderForUpdate(TENANT_ID, "TAKU")).thenReturn(account());
        when(credentialService.getMetadata(TENANT_ID, ACCOUNT_ID)).thenReturn(metadata(5));
        SkitReportingConfigurationService.Command command = command();
        command.setCredentialVersion(4);
        command.setPublisherKey("stale-write");

        assertServiceException(() -> service.configure(command),
                AD_REPORTING_CONFIG_VERSION_CONFLICT);

        verify(accountMapper, never()).updateById(any(SkitAdAccountDO.class));
        verify(credentialService, never()).configure(anyLong(), anyLong(), any());
    }

    @Test
    void metadataReadNeverExposesPublisherKey() {
        when(accountMapper.selectByProvider("TAKU")).thenReturn(account());
        when(credentialService.getMetadata(TENANT_ID, ACCOUNT_ID)).thenReturn(metadata(4));

        SkitReportingConfigurationService.View result = service.getConfiguration();

        assertEquals(ACCOUNT_ID, result.getAdAccountId());
        assertEquals(4, result.getCredentialVersion());
        assertFalse(result.toString().toLowerCase().contains("publisher"));
    }

    private SkitAdAccountDO account() {
        SkitAdAccountDO account = SkitAdAccountDO.builder().id(ACCOUNT_ID).provider("TAKU")
                .appId("app-id").configData("{\"placementId\":\"placement\","
                        + "\"adFormat\":\"rewarded_video\"}")
                .status(CommonStatusEnum.ENABLE.getStatus()).reportTimezone("UTC+8")
                .reportCurrency("USD").reportAmountScale(6).reportFailureCount(0).build();
        account.setTenantId(TENANT_ID);
        return account;
    }

    private SkitReportingConfigurationService.Command command() {
        return new SkitReportingConfigurationService.Command().setCredentialVersion(4)
                .setReportTimezone("UTC+8").setCurrency("USD").setAmountScale(6)
                .setAdFormat("rewarded_video");
    }

    private SkitReportingCredentialService.Metadata metadata(int version) {
        return new SkitReportingCredentialService.Metadata(TENANT_ID, ACCOUNT_ID, version,
                true, LocalDateTime.of(2026, 7, 15, 10, 0));
    }
}
