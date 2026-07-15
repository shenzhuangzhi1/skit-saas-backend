package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReportingCredentialVersionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReportingCredentialVersionMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoService;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAesGcmCredentialCryptoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitReportingCredentialServiceTest {

    private static final long TENANT_ID = 17L;
    private static final long ACCOUNT_ID = 29L;

    @Test
    void publisherKeyUsesIndependentAeadPurposeAndCannotDecryptAsRewardSecret() {
        byte[] encryptionKey = new byte[32];
        Arrays.fill(encryptionKey, (byte) 7);
        SkitAesGcmCredentialCryptoService crypto = new SkitAesGcmCredentialCryptoService(
                "report-keyring-v1", Collections.singletonMap("report-keyring-v1", encryptionKey));
        SkitAdCredentialCryptoService.Context reporting = SkitAdCredentialCryptoService.Context
                .publisherKey(TENANT_ID, ACCOUNT_ID, 1, 1);
        SkitAdCredentialCryptoService.EncryptedSecret encrypted = crypto.encrypt(reporting,
                "publisher-key".getBytes(StandardCharsets.UTF_8));

        assertEquals("publisher-key", new String(crypto.decrypt(reporting, encrypted),
                StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(
                SkitAdCredentialCryptoService.Context.rewardSecret(TENANT_ID, ACCOUNT_ID, 1, 1), encrypted));
        assertNotEquals(SkitAdCredentialCryptoService.Context.rewardSecret(
                TENANT_ID, ACCOUNT_ID, 1, 1).getPurpose(), reporting.getPurpose());
    }

    @Test
    void configureRotatesImmutableVersionAndReturnsMetadataOnly() {
        SkitAdAccountMapper accountMapper = mock(SkitAdAccountMapper.class);
        SkitAdReportingCredentialVersionMapper mapper = mock(SkitAdReportingCredentialVersionMapper.class);
        SkitAdCredentialCryptoService crypto = mock(SkitAdCredentialCryptoService.class);
        when(accountMapper.lockByTenantAndId(TENANT_ID, ACCOUNT_ID)).thenReturn(ACCOUNT_ID);
        when(mapper.selectMaxVersion(TENANT_ID, ACCOUNT_ID)).thenReturn(4);
        SkitAdReportingCredentialVersionDO active = row(4);
        when(mapper.selectActiveForUpdate(TENANT_ID, ACCOUNT_ID)).thenReturn(active);
        when(mapper.retireActiveVersion(TENANT_ID, ACCOUNT_ID, active.getId())).thenReturn(1);
        when(crypto.encrypt(any(), any())).thenReturn(new SkitAdCredentialCryptoService.EncryptedSecret(
                new byte[]{1, 2}, new byte[12], "report-keyring-v1", 1));
        when(mapper.insert(any())).thenReturn(1);
        SkitReportingCredentialService service = new SkitReportingCredentialServiceImpl(
                accountMapper, mapper, crypto);
        byte[] publisherKey = "write-only-publisher-key".getBytes(StandardCharsets.UTF_8);

        SkitReportingCredentialService.Metadata metadata =
                service.configure(TENANT_ID, ACCOUNT_ID, publisherKey);

        assertEquals(5, metadata.getVersion());
        assertTrue(metadata.isActive());
        assertFalse(metadata.toString().contains("write-only-publisher-key"));
        ArgumentCaptor<SkitAdReportingCredentialVersionDO> inserted =
                ArgumentCaptor.forClass(SkitAdReportingCredentialVersionDO.class);
        verify(mapper).insert(inserted.capture());
        assertEquals(Long.valueOf(TENANT_ID), inserted.getValue().getTenantId());
        assertEquals(Integer.valueOf(5), inserted.getValue().getCredentialVersion());
        assertFalse(inserted.getValue().toString().contains("ciphertext"));
    }

    @Test
    void activePublisherKeyIsScopedConsumedOnceAndWipedAfterUse() {
        SkitAdAccountMapper accountMapper = mock(SkitAdAccountMapper.class);
        SkitAdReportingCredentialVersionMapper mapper = mock(SkitAdReportingCredentialVersionMapper.class);
        SkitAdCredentialCryptoService crypto = mock(SkitAdCredentialCryptoService.class);
        when(mapper.selectActive(TENANT_ID, ACCOUNT_ID)).thenReturn(row(4));
        byte[] decrypted = "publisher-key".getBytes(StandardCharsets.UTF_8);
        when(crypto.decrypt(any(), any())).thenReturn(decrypted.clone());
        SkitReportingCredentialService service = new SkitReportingCredentialServiceImpl(
                accountMapper, mapper, crypto);
        AtomicReference<byte[]> borrowed = new AtomicReference<>();

        String result = service.withActivePublisherKey(TENANT_ID, ACCOUNT_ID, key -> {
            borrowed.set(key);
            return new String(key, StandardCharsets.UTF_8);
        });

        assertEquals("publisher-key", result);
        assertTrue(allZero(borrowed.get()));
        assertFalse(service.getMetadata(TENANT_ID, ACCOUNT_ID).toString().contains("publisher-key"));
    }

    @Test
    void crossTenantMapperRowFailsClosedBeforeDecryptOrWrite() {
        SkitAdAccountMapper accountMapper = mock(SkitAdAccountMapper.class);
        SkitAdReportingCredentialVersionMapper mapper = mock(SkitAdReportingCredentialVersionMapper.class);
        SkitAdCredentialCryptoService crypto = mock(SkitAdCredentialCryptoService.class);
        SkitAdReportingCredentialVersionDO foreign = row(1);
        foreign.setTenantId(TENANT_ID + 1);
        when(mapper.selectActive(TENANT_ID, ACCOUNT_ID)).thenReturn(foreign);
        SkitReportingCredentialService service = new SkitReportingCredentialServiceImpl(
                accountMapper, mapper, crypto);

        assertThrows(IllegalStateException.class,
                () -> service.withActivePublisherKey(TENANT_ID, ACCOUNT_ID, key -> null));

        verify(crypto, never()).decrypt(any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void permissionVerificationIsMonotonicAndDatabaseTimed() {
        SkitAdAccountMapper accountMapper = mock(SkitAdAccountMapper.class);
        SkitAdReportingCredentialVersionMapper mapper = mock(SkitAdReportingCredentialVersionMapper.class);
        SkitAdCredentialCryptoService crypto = mock(SkitAdCredentialCryptoService.class);
        when(mapper.markPermissionVerifiedCas(TENANT_ID, ACCOUNT_ID, 4)).thenReturn(1);
        SkitReportingCredentialService service = new SkitReportingCredentialServiceImpl(
                accountMapper, mapper, crypto);

        service.markPermissionVerified(TENANT_ID, ACCOUNT_ID, 4);

        verify(mapper).markPermissionVerifiedCas(TENANT_ID, ACCOUNT_ID, 4);
    }

    private SkitAdReportingCredentialVersionDO row(int version) {
        SkitAdReportingCredentialVersionDO row = new SkitAdReportingCredentialVersionDO()
                .setId(100L + version).setAdAccountId(ACCOUNT_ID).setCredentialVersion(version)
                .setCiphertext(new byte[]{1, 2}).setNonce(new byte[12])
                .setEncryptionKeyId("report-keyring-v1").setEnvelopeVersion(1)
                .setActive(true).setPermissionVerifiedAt(LocalDateTime.of(2026, 7, 14, 20, 0));
        row.setTenantId(TENANT_ID);
        return row;
    }

    private boolean allZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }
}
