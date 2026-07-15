package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReportingCredentialVersionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReportingCredentialVersionMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.BiFunction;

@Service
public class SkitReportingCredentialServiceImpl implements SkitReportingCredentialService {

    private final SkitAdAccountMapper accountMapper;
    private final SkitAdReportingCredentialVersionMapper mapper;
    private final SkitAdCredentialCryptoService cryptoService;

    public SkitReportingCredentialServiceImpl(SkitAdAccountMapper accountMapper,
                                              SkitAdReportingCredentialVersionMapper mapper,
                                              SkitAdCredentialCryptoService cryptoService) {
        this.accountMapper = Objects.requireNonNull(accountMapper, "accountMapper");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.cryptoService = Objects.requireNonNull(cryptoService, "cryptoService");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Metadata configure(long tenantId, long adAccountId, byte[] publisherKey) {
        validateScope(tenantId, adAccountId);
        if (publisherKey == null || publisherKey.length == 0 || publisherKey.length > 4096) {
            throw new IllegalArgumentException("Publisher key must contain between 1 and 4096 bytes");
        }
        AtomicReference<Metadata> result = new AtomicReference<>();
        TenantUtils.execute(tenantId,
                () -> result.set(configureInsideTenant(tenantId, adAccountId, publisherKey)));
        return result.get();
    }

    private Metadata configureInsideTenant(long tenantId, long adAccountId, byte[] publisherKey) {
        Long locked = accountMapper.lockByTenantAndId(tenantId, adAccountId);
        if (!Objects.equals(locked, adAccountId)) {
            throw new IllegalStateException("Advertising account does not belong to the requested tenant");
        }
        Integer maximum = mapper.selectMaxVersion(tenantId, adAccountId);
        int current = maximum == null ? 0 : maximum;
        if (current < 0 || current == Integer.MAX_VALUE) {
            throw new IllegalStateException("Reporting credential version range is exhausted or corrupt");
        }
        int nextVersion = current + 1;
        SkitAdReportingCredentialVersionDO active = mapper.selectActiveForUpdate(tenantId, adAccountId);
        requireScope(active, tenantId, adAccountId);

        byte[] plaintext = publisherKey.clone();
        SkitAdCredentialCryptoService.EncryptedSecret encrypted;
        try {
            encrypted = cryptoService.encrypt(SkitAdCredentialCryptoService.Context.publisherKey(
                    tenantId, adAccountId, nextVersion,
                    SkitAdCredentialCryptoService.CURRENT_ENVELOPE_VERSION), plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
        if (active != null && mapper.retireActiveVersion(tenantId, adAccountId, active.getId()) != 1) {
            throw new IllegalStateException("Active reporting credential changed during rotation");
        }
        SkitAdReportingCredentialVersionDO row = new SkitAdReportingCredentialVersionDO()
                .setAdAccountId(adAccountId).setCredentialVersion(nextVersion)
                .setCiphertext(encrypted.getCiphertext()).setNonce(encrypted.getNonce())
                .setEncryptionKeyId(encrypted.getKeyId())
                .setEnvelopeVersion(encrypted.getEnvelopeVersion()).setActive(true);
        row.setTenantId(tenantId);
        if (mapper.insert(row) != 1) {
            throw new IllegalStateException("Reporting credential version was not inserted");
        }
        return metadata(row);
    }

    @Override
    public Metadata getMetadata(long tenantId, long adAccountId) {
        SkitAdReportingCredentialVersionDO row = active(tenantId, adAccountId);
        return row == null ? null : metadata(row);
    }

    @Override
    public <T> T withActivePublisherKey(long tenantId, long adAccountId, Function<byte[], T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        return withActivePublisherKeyVersion(tenantId, adAccountId,
                (version, plaintext) -> consumer.apply(plaintext));
    }

    @Override
    public <T> T withActivePublisherKeyVersion(long tenantId, long adAccountId,
                                               BiFunction<Integer, byte[], T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        SkitAdReportingCredentialVersionDO row = active(tenantId, adAccountId);
        if (row == null) {
            throw new IllegalStateException("Active reporting credential is unavailable");
        }
        SkitAdCredentialCryptoService.EncryptedSecret encrypted =
                new SkitAdCredentialCryptoService.EncryptedSecret(row.getCiphertext(), row.getNonce(),
                        row.getEncryptionKeyId(), row.getEnvelopeVersion());
        byte[] plaintext = cryptoService.decrypt(SkitAdCredentialCryptoService.Context.publisherKey(
                tenantId, adAccountId, row.getCredentialVersion(), row.getEnvelopeVersion()), encrypted);
        try {
            return consumer.apply(row.getCredentialVersion(), plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markPermissionVerified(long tenantId, long adAccountId, int credentialVersion) {
        validateScope(tenantId, adAccountId);
        if (credentialVersion <= 0
                || mapper.markPermissionVerifiedCas(tenantId, adAccountId, credentialVersion) != 1) {
            throw new IllegalStateException("Active reporting credential version was not verified");
        }
    }

    private SkitAdReportingCredentialVersionDO active(long tenantId, long adAccountId) {
        validateScope(tenantId, adAccountId);
        AtomicReference<SkitAdReportingCredentialVersionDO> result = new AtomicReference<>();
        TenantUtils.execute(tenantId, () -> result.set(mapper.selectActive(tenantId, adAccountId)));
        requireScope(result.get(), tenantId, adAccountId);
        return result.get();
    }

    private static Metadata metadata(SkitAdReportingCredentialVersionDO row) {
        return new Metadata(row.getTenantId(), row.getAdAccountId(), row.getCredentialVersion(),
                Boolean.TRUE.equals(row.getActive()), row.getPermissionVerifiedAt());
    }

    private static void requireScope(SkitAdReportingCredentialVersionDO row,
                                     long tenantId, long adAccountId) {
        if (row != null && (!Objects.equals(row.getTenantId(), tenantId)
                || !Objects.equals(row.getAdAccountId(), adAccountId))) {
            throw new IllegalStateException("Reporting credential mapper returned a cross-tenant row");
        }
    }

    private static void validateScope(long tenantId, long adAccountId) {
        if (tenantId <= 0 || adAccountId <= 0) {
            throw new IllegalArgumentException("Tenant and advertising account identifiers must be positive");
        }
    }
}
