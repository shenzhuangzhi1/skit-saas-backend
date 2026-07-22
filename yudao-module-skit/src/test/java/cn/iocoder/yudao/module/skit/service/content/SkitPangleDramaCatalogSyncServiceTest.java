package cn.iocoder.yudao.module.skit.service.content;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.record.SkitAdminRecordMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitPangleDramaCatalogSyncServiceTest {

    private static final long TENANT_ID = 162L;
    private static final long DRAMA_ID = 1631L;

    @Mock private SkitAdAccountMapper accountMapper;
    @Mock private SkitPangleDramaCatalogStore catalogStore;
    @Mock private PangleShortPlayClient client;

    private ObjectMapper objectMapper;
    private SkitPangleDramaCatalogSyncService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
        objectMapper = new ObjectMapper();
        service = new SkitPangleDramaCatalogSyncService(
                accountMapper, catalogStore, client, objectMapper);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void synchronizesTheRequestedDramaIntoOnlyTheCurrentTenantCatalog() throws Exception {
        SkitAdAccountDO account = SkitAdAccountDO.builder()
                .id(71L).provider("PANGLE").appId("5850994")
                .secret("server-key-1").status(CommonStatusEnum.ENABLE.getStatus()).build();
        account.setTenantId(TENANT_ID);
        account.setDeleted(false);
        when(accountMapper.selectEnabledPangleForShare(TENANT_ID))
                .thenReturn(Collections.singletonList(account));
        when(client.fetchDrama("5850994", "server-key-1", DRAMA_ID))
                .thenReturn(new PangleShortPlayClient.Drama(
                        DRAMA_ID, "人间至味是", "简介", "https://example.test/cover.jpg",
                        12L, "都市", 88, 1_760_000_000L, 0));
        service.syncDrama(TENANT_ID, DRAMA_ID);

        ArgumentCaptor<SkitAdminRecordDO> row = ArgumentCaptor.forClass(SkitAdminRecordDO.class);
        verify(catalogStore).replaceDrama(
                org.mockito.ArgumentMatchers.eq(TENANT_ID),
                org.mockito.ArgumentMatchers.eq(DRAMA_ID), row.capture());
        assertEquals(TENANT_ID, row.getValue().getTenantId());
        assertEquals("drama", row.getValue().getPageKey());
        assertEquals("pangle-1631", row.getValue().getRowKey());
        assertEquals(0, row.getValue().getStatus());
        JsonNode data = objectMapper.readTree(row.getValue().getRecordData());
        assertEquals(DRAMA_ID, data.path("pangleDramaId").asLong());
        assertEquals(88, data.path("episodes").asInt());
        assertEquals("人间至味是", data.path("title").asText());
        assertEquals("上架", data.path("publishStatus").asText());
        assertEquals("PANGLE_SERVER_API", data.path("catalogSource").asText());
        assertEquals(0, data.path("freeEpisodes").asInt());
        assertEquals(1, data.path("unlockSize").asInt());
    }

    @Test
    void unavailableTenantServerKeyReturnsTheDedicatedCatalogErrorWithoutWriting() {
        when(accountMapper.selectEnabledPangleForShare(TENANT_ID))
                .thenReturn(Collections.emptyList());

        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.syncDrama(TENANT_ID, DRAMA_ID));

        assertEquals(1_030_007_010, failure.getCode());
        verify(client, never()).fetchDrama(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(catalogStore, never()).replaceDrama(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(SkitAdminRecordDO.class));
    }

    @Test
    void dramaOutsideTheTenantContentLibraryHasADedicatedError() {
        SkitAdAccountDO account = enabledAccount();
        when(accountMapper.selectEnabledPangleForShare(TENANT_ID))
                .thenReturn(Collections.singletonList(account));
        when(client.fetchDrama("5850994", "server-key-1", DRAMA_ID))
                .thenThrow(new PangleShortPlayClient.Failure(
                        PangleShortPlayClient.FailureReason.CONTENT_UNAVAILABLE,
                        0, "", "request-empty"));

        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.syncDrama(TENANT_ID, DRAMA_ID));

        assertEquals(1_030_007_011, failure.getCode());
        verify(catalogStore, never()).replaceDrama(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(SkitAdminRecordDO.class));
    }

    @Test
    void providerCredentialRejectionHasADedicatedError() {
        SkitAdAccountDO account = enabledAccount();
        when(accountMapper.selectEnabledPangleForShare(TENANT_ID))
                .thenReturn(Collections.singletonList(account));
        when(client.fetchDrama("5850994", "server-key-1", DRAMA_ID))
                .thenThrow(new PangleShortPlayClient.Failure(
                        PangleShortPlayClient.FailureReason.PROVIDER_REJECTED,
                        1004, "sign_invalid", "request-rejected"));

        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.syncDrama(TENANT_ID, DRAMA_ID));

        assertEquals(1_030_007_012, failure.getCode());
        verify(catalogStore, never()).replaceDrama(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(SkitAdminRecordDO.class));
    }

    private SkitAdAccountDO enabledAccount() {
        SkitAdAccountDO account = SkitAdAccountDO.builder()
                .id(71L).provider("PANGLE").appId("5850994")
                .secret("server-key-1").status(CommonStatusEnum.ENABLE.getStatus()).build();
        account.setTenantId(TENANT_ID);
        account.setDeleted(false);
        return account;
    }

    @Test
    void explicitTenantMustMatchTheAmbientMemberTenant() {
        assertThrows(IllegalArgumentException.class,
                () -> service.syncDrama(TENANT_ID + 1, DRAMA_ID));

        verify(accountMapper, never()).selectEnabledPangleForShare(
                org.mockito.ArgumentMatchers.anyLong());
    }
}
