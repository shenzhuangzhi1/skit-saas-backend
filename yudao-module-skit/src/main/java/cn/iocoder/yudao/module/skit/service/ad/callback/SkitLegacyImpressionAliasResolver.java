package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackKeyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves explicitly configured, expired tenant callback keys to one pinned active key version.
 * This compatibility path is consumed only by the Taku impression dispatcher.
 */
@Service
public class SkitLegacyImpressionAliasResolver {

    private static final int SHA_256_BYTES = 32;
    private static final int SHA_256_HEX_CHARS = SHA_256_BYTES * 2;

    private final SkitAdCallbackKeyMapper callbackKeyMapper;
    private final SkitCallbackRouteRegistryService registryService;
    private final Alias alias;

    @Autowired
    public SkitLegacyImpressionAliasResolver(
            SkitAdCallbackKeyMapper callbackKeyMapper,
            SkitCallbackRouteRegistryService registryService,
            @Value("${skit.ad.callback.legacy-impression-alias:}") String configuredAlias) {
        this(callbackKeyMapper, registryService, parseAlias(configuredAlias));
    }

    private SkitLegacyImpressionAliasResolver(
            SkitAdCallbackKeyMapper callbackKeyMapper,
            SkitCallbackRouteRegistryService registryService,
            Alias alias) {
        if (alias != null) {
            this.callbackKeyMapper = Objects.requireNonNull(callbackKeyMapper, "callbackKeyMapper");
            this.registryService = Objects.requireNonNull(registryService, "registryService");
        } else {
            this.callbackKeyMapper = callbackKeyMapper;
            this.registryService = registryService;
        }
        this.alias = alias;
    }

    static SkitLegacyImpressionAliasResolver disabled() {
        return new SkitLegacyImpressionAliasResolver(null, null, (Alias) null);
    }

    SkitCallbackRouteRegistryService.RouteLookup resolve(
            byte[] sourceKeyHash, LocalDateTime authoritativeReceivedAt) {
        if (sourceKeyHash == null || sourceKeyHash.length != SHA_256_BYTES
                || authoritativeReceivedAt == null) {
            throw rejected();
        }
        if (alias == null || !alias.sourceHash.equals(hex(sourceKeyHash))) {
            throw rejected();
        }

        byte[] targetKeyHash = decodeHex(alias.targetHash);
        AtomicReference<SkitAdCallbackKeyDO> source = new AtomicReference<>();
        AtomicReference<SkitAdCallbackKeyDO> target = new AtomicReference<>();
        try {
            TenantUtils.executeIgnore(() -> {
                source.set(callbackKeyMapper.selectByHash(sourceKeyHash));
                target.set(callbackKeyMapper.selectByHash(targetKeyHash));
            });

            SkitAdCallbackKeyDO sourceRow = source.get();
            SkitAdCallbackKeyDO targetRow = target.get();
            if (!validSource(sourceRow, authoritativeReceivedAt)
                    || !validTarget(targetRow)
                    || Objects.equals(sourceRow.getId(), targetRow.getId())
                    || !Objects.equals(sourceRow.getTenantId(), targetRow.getTenantId())
                    || !Objects.equals(sourceRow.getAdAccountId(), targetRow.getAdAccountId())
                    || sourceRow.getKeyVersion() >= targetRow.getKeyVersion()) {
                throw rejected();
            }

            SkitCallbackRouteRegistryService.RouteLookup targetRoute =
                    registryService.lookupTenantReward(targetKeyHash, authoritativeReceivedAt);
            if (targetRoute.getTenantId() != targetRow.getTenantId()
                    || targetRoute.getAdAccountId() != targetRow.getAdAccountId()
                    || targetRoute.getKeyVersion() != targetRow.getKeyVersion()
                    || !targetRoute.isActive()
                    || targetRoute.getAcceptUntil() != null) {
                throw rejected();
            }
            return targetRoute;
        } finally {
            wipeRowHash(source.get());
            wipeRowHash(target.get());
            Arrays.fill(targetKeyHash, (byte) 0);
        }
    }

    private static boolean validSource(SkitAdCallbackKeyDO row, LocalDateTime receivedAt) {
        return validIdentity(row)
                && !Boolean.TRUE.equals(row.getActive())
                && row.getRevokedAt() == null
                && row.getAcceptUntil() != null
                && receivedAt.isAfter(row.getAcceptUntil());
    }

    private static boolean validTarget(SkitAdCallbackKeyDO row) {
        return validIdentity(row)
                && Boolean.TRUE.equals(row.getActive())
                && row.getAcceptUntil() == null
                && row.getRevokedAt() == null;
    }

    private static boolean validIdentity(SkitAdCallbackKeyDO row) {
        return row != null && row.getId() != null && row.getId() > 0
                && row.getTenantId() != null && row.getTenantId() > 0
                && row.getAdAccountId() != null && row.getAdAccountId() > 0
                && row.getKeyVersion() != null && row.getKeyVersion() > 0;
    }

    private static Alias parseAlias(String configured) {
        if (configured == null || configured.trim().isEmpty()) {
            return null;
        }
        String candidate = configured.trim();
        int separator = candidate.indexOf('=');
        if (separator <= 0 || separator != candidate.lastIndexOf('=')) {
            throw new IllegalArgumentException("Legacy impression alias must be sourceHash=targetHash");
        }
        String source = normalizedHash(candidate.substring(0, separator));
        String target = normalizedHash(candidate.substring(separator + 1));
        if (source.equals(target)) {
            throw new IllegalArgumentException("Legacy impression alias source and target must differ");
        }
        return new Alias(source, target);
    }

    private static String normalizedHash(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() != SHA_256_HEX_CHARS) {
            throw new IllegalArgumentException("Legacy impression alias hash must be SHA-256 hex");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (!((current >= '0' && current <= '9') || (current >= 'a' && current <= 'f'))) {
                throw new IllegalArgumentException("Legacy impression alias hash must be SHA-256 hex");
            }
        }
        return normalized;
    }

    private static byte[] decodeHex(String value) {
        byte[] result = new byte[SHA_256_BYTES];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) ((Character.digit(value.charAt(index * 2), 16) << 4)
                    | Character.digit(value.charAt(index * 2 + 1), 16));
        }
        return result;
    }

    private static String hex(byte[] value) {
        char[] result = new char[value.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < value.length; index++) {
            int current = value[index] & 0xff;
            result[index * 2] = digits[current >>> 4];
            result[index * 2 + 1] = digits[current & 0x0f];
        }
        return new String(result);
    }

    private static void wipeRowHash(SkitAdCallbackKeyDO row) {
        if (row != null && row.getCallbackKeyHash() != null) {
            Arrays.fill(row.getCallbackKeyHash(), (byte) 0);
        }
    }

    private static SkitCallbackRouteRegistryService.CallbackRouteRejectedException rejected() {
        return new SkitCallbackRouteRegistryService.CallbackRouteRejectedException();
    }

    private static final class Alias {

        private final String sourceHash;
        private final String targetHash;

        private Alias(String sourceHash, String targetHash) {
            this.sourceHash = sourceHash;
            this.targetHash = targetHash;
        }
    }
}
