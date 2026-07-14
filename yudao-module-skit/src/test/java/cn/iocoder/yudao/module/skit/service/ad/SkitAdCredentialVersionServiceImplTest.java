package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRewardSecretVersionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackKeyMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdRewardSecretVersionMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoService;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoConfiguration;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoProperties;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAesGcmCredentialCryptoService;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitAdCredentialVersionServiceImplTest {

    private static final long TENANT_ID = 41L;
    private static final long ACCOUNT_ID = 51L;
    private static final Instant NOW = Instant.parse("2026-07-14T01:02:03Z");
    private static final byte[] CURRENT_KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII);

    @Mock
    private SkitAdAccountMapper accountMapper;
    @Mock
    private SkitAdCallbackKeyMapper callbackKeyMapper;
    @Mock
    private SkitAdRewardSecretVersionMapper rewardSecretMapper;

    private Clock clock;
    private SkitAdCredentialCryptoService crypto;

    @Test
    void resolvedSecretExposesOnlyAOneShotScopedCallbackApi() {
        assertDoesNotThrow(() -> SkitAdCredentialVersionService.ResolvedRewardSecret.class
                .getDeclaredMethod("withSecret", Function.class));
        assertThrows(NoSuchMethodException.class, () -> SkitAdCredentialVersionService.ResolvedRewardSecret.class
                .getDeclaredMethod("consumeSecret"));
    }

    @Test
    void credentialMappersDoNotExposeGenericMutationApis() {
        assertFalse(BaseMapper.class.isAssignableFrom(SkitAdCallbackKeyMapper.class));
        assertFalse(BaseMapper.class.isAssignableFrom(SkitAdRewardSecretVersionMapper.class));
    }

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        crypto = new SkitAesGcmCredentialCryptoService("primary", singletonKey("primary", CURRENT_KEY));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void callbackKeyUsesThirtyTwoRandomBytesStoresOnlyHashAndCanBeConsumedOnce() throws Exception {
        byte[] randomBytes = sequence(0);
        SkitAdCredentialVersionServiceImpl service = service(new SequenceSecureRandom(randomBytes));
        when(accountMapper.lockByTenantAndId(TENANT_ID, ACCOUNT_ID)).thenReturn(ACCOUNT_ID);
        when(callbackKeyMapper.selectMaxVersion(TENANT_ID, ACCOUNT_ID)).thenReturn(null);
        when(callbackKeyMapper.selectActiveForUpdate(TENANT_ID, ACCOUNT_ID)).thenReturn(null);
        when(callbackKeyMapper.insert(any(SkitAdCallbackKeyDO.class))).thenReturn(1);

        SkitAdCredentialVersionService.CallbackKeyIssue issued =
                service.rotateCallbackKey(TENANT_ID, ACCOUNT_ID, Duration.ofMinutes(15));

        String expectedRaw = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        ArgumentCaptor<SkitAdCallbackKeyDO> row = ArgumentCaptor.forClass(SkitAdCallbackKeyDO.class);
        verify(callbackKeyMapper).insert(row.capture());
        assertEquals(1, row.getValue().getKeyVersion());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expectedRaw.getBytes(StandardCharsets.US_ASCII)),
                row.getValue().getCallbackKeyHash());
        assertFalse(Arrays.equals(randomBytes, row.getValue().getCallbackKeyHash()));
        assertFalse(issued.toString().contains(expectedRaw));
        assertFalse(new ObjectMapper().writeValueAsString(issued).contains(expectedRaw));
        assertEquals(expectedRaw, issued.consumeCallbackKey());
        assertThrows(IllegalStateException.class, issued::consumeCallbackKey);

        InOrder order = inOrder(accountMapper, callbackKeyMapper);
        order.verify(accountMapper).lockByTenantAndId(TENANT_ID, ACCOUNT_ID);
        order.verify(callbackKeyMapper).selectMaxVersion(TENANT_ID, ACCOUNT_ID);
        order.verify(callbackKeyMapper).selectActiveForUpdate(TENANT_ID, ACCOUNT_ID);
        order.verify(callbackKeyMapper).insert(any(SkitAdCallbackKeyDO.class));
    }

    @Test
    void callbackHashCollisionRetriesWithoutReturningOrExposingAnotherTenantKey() {
        byte[] collision = sequence(0);
        byte[] replacement = sequence(32);
        SkitAdCredentialVersionServiceImpl service = service(new SequenceSecureRandom(collision, replacement));
        when(accountMapper.lockByTenantAndId(TENANT_ID, ACCOUNT_ID)).thenReturn(ACCOUNT_ID);
        when(callbackKeyMapper.selectMaxVersion(TENANT_ID, ACCOUNT_ID)).thenReturn(4);
        when(callbackKeyMapper.selectActiveForUpdate(TENANT_ID, ACCOUNT_ID)).thenReturn(callbackRow(4, true));
        when(callbackKeyMapper.retireActiveVersion(anyLong(), anyLong(), anyLong(), any(LocalDateTime.class)))
                .thenReturn(1);
        when(callbackKeyMapper.insert(any(SkitAdCallbackKeyDO.class)))
                .thenThrow(new DuplicateKeyException("global hash collision"))
                .thenReturn(1);
        SkitAdCredentialVersionService.CallbackKeyIssue issued =
                service.rotateCallbackKey(TENANT_ID, ACCOUNT_ID, Duration.ofMinutes(10));

        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(replacement), issued.consumeCallbackKey());
        assertEquals(TENANT_ID, issued.getTenantId());
        assertEquals(ACCOUNT_ID, issued.getAdAccountId());
        assertEquals(5, issued.getVersion());
        verify(callbackKeyMapper, times(2)).insert(any(SkitAdCallbackKeyDO.class));
        verify(callbackKeyMapper, never()).selectByHash(any(byte[].class));
        verify(callbackKeyMapper).retireActiveVersion(anyLong(), anyLong(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    void globalCallbackLookupReturnsOnlyServerDerivedImmutableScopeWithinAcceptanceWindow() {
        byte[] randomBytes = sequence(7);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        SkitAdCallbackKeyDO stored = new SkitAdCallbackKeyDO().setId(9L)
                .setAdAccountId(88L).setKeyVersion(3).setActive(false)
                .setAcceptUntil(LocalDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
        stored.setTenantId(77L);
        when(callbackKeyMapper.selectByHash(any(byte[].class))).thenReturn(stored);

        SkitAdCredentialVersionService.CallbackKeyResolution resolution =
                service(new SequenceSecureRandom()).resolveCallbackKey(
                        raw, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        assertEquals(77L, resolution.getTenantId());
        assertEquals(88L, resolution.getAdAccountId());
        assertEquals(3, resolution.getVersion());
        assertFalse(resolution.toString().contains(raw));
        assertThrows(IllegalStateException.class, () -> service(new SequenceSecureRandom())
                .resolveCallbackKey(raw, LocalDateTime.ofInstant(NOW.plusSeconds(61), ZoneOffset.UTC)));
    }

    @Test
    void explicitTenantAndGlobalHashScopesOverrideAndRestoreAnUntrustedCurrentContext() {
        TenantContextHolder.setTenantId(999L);
        when(accountMapper.lockByTenantAndId(TENANT_ID, ACCOUNT_ID)).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            return ACCOUNT_ID;
        });
        when(callbackKeyMapper.selectMaxVersion(TENANT_ID, ACCOUNT_ID)).thenReturn(0);
        when(callbackKeyMapper.selectActiveForUpdate(TENANT_ID, ACCOUNT_ID)).thenReturn(null);
        when(callbackKeyMapper.insert(any(SkitAdCallbackKeyDO.class))).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            return 1;
        });

        service(new SequenceSecureRandom(sequence(1)))
                .rotateCallbackKey(TENANT_ID, ACCOUNT_ID, Duration.ofMinutes(5));

        assertEquals(999L, TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());

        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(sequence(9));
        SkitAdCallbackKeyDO global = callbackRow(2, true);
        global.setTenantId(77L);
        global.setAdAccountId(88L);
        when(callbackKeyMapper.selectByHash(any(byte[].class))).thenAnswer(invocation -> {
            assertTrue(TenantContextHolder.isIgnore());
            assertEquals(999L, TenantContextHolder.getTenantId());
            return global;
        });

        SkitAdCredentialVersionService.CallbackKeyResolution resolution =
                service(new SequenceSecureRandom()).resolveCallbackKey(
                        raw, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        assertEquals(77L, resolution.getTenantId());
        assertEquals(88L, resolution.getAdAccountId());
        assertEquals(999L, TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
    }

    @Test
    void authoritativeReceiptTimePreservesPreRevocationWorkAndEnforcesInclusiveBoundaries() {
        LocalDateTime receivedAt = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(sequence(10));
        SkitAdCallbackKeyDO callback = callbackRow(3, false)
                .setAcceptUntil(receivedAt);
        when(callbackKeyMapper.selectByHash(any(byte[].class))).thenReturn(callback);
        SkitAdCredentialVersionServiceImpl delayedProcessor = service(new SequenceSecureRandom(), crypto,
                Clock.fixed(NOW.plus(Duration.ofHours(3)), ZoneOffset.UTC));

        assertEquals(3, delayedProcessor.resolveCallbackKey(raw, receivedAt).getVersion(),
                "receivedAt equal to acceptUntil remains inside the window");
        callback.setAcceptUntil(receivedAt.plusMinutes(2)).setRevokedAt(receivedAt.plusMinutes(1));
        assertEquals(3, delayedProcessor.resolveCallbackKey(raw, receivedAt).getVersion(),
                "processing after revocation still accepts evidence received before revocation");
        assertThrows(IllegalStateException.class,
                () -> delayedProcessor.resolveCallbackKey(raw, receivedAt.plusMinutes(1)),
                "receivedAt equal to revokedAt is rejected");

        byte[] plaintext = "boundary-secret".getBytes(StandardCharsets.UTF_8);
        SkitAdCredentialCryptoService.Context context =
                SkitAdCredentialCryptoService.Context.rewardSecret(TENANT_ID, ACCOUNT_ID, 4, 1);
        SkitAdCredentialCryptoService.EncryptedSecret encrypted = crypto.encrypt(context, plaintext);
        SkitAdRewardSecretVersionDO reward = rewardRow(4, false)
                .setCiphertext(encrypted.getCiphertext()).setNonce(encrypted.getNonce())
                .setEncryptionKeyId(encrypted.getKeyId()).setEnvelopeVersion(encrypted.getEnvelopeVersion())
                .setAcceptUntil(receivedAt);
        when(rewardSecretMapper.selectByVersion(TENANT_ID, ACCOUNT_ID, 4)).thenReturn(reward);
        assertArrayEquals(plaintext, delayedProcessor.resolveRewardSecret(TENANT_ID, ACCOUNT_ID, 4,
                receivedAt, receivedAt).withSecret(byte[]::clone));
        reward.setAcceptUntil(receivedAt.plusMinutes(2)).setRevokedAt(receivedAt);
        assertThrows(IllegalStateException.class, () -> delayedProcessor.resolveRewardSecret(
                TENANT_ID, ACCOUNT_ID, 4, receivedAt.plusMinutes(2), receivedAt));
    }

    @Test
    void rewardSecretRotationEncryptsBeforeRetiringPriorVersionAndResolvesOnlySnapshottedWindow()
            throws Exception {
        byte[] plaintext = "provider-reward-secret".getBytes(StandardCharsets.UTF_8);
        SkitAdCredentialVersionServiceImpl service = service(new SequenceSecureRandom(sequence(3)));
        when(accountMapper.lockByTenantAndId(TENANT_ID, ACCOUNT_ID)).thenReturn(ACCOUNT_ID);
        when(rewardSecretMapper.selectMaxVersion(TENANT_ID, ACCOUNT_ID)).thenReturn(1);
        SkitAdRewardSecretVersionDO old = rewardRow(1, true);
        when(rewardSecretMapper.selectActiveForUpdate(TENANT_ID, ACCOUNT_ID)).thenReturn(old);
        when(rewardSecretMapper.retireActiveVersion(anyLong(), anyLong(), anyLong(), any(LocalDateTime.class)))
                .thenReturn(1);
        when(rewardSecretMapper.insert(any(SkitAdRewardSecretVersionDO.class))).thenReturn(1);

        SkitAdCredentialVersionService.CredentialMetadata metadata =
                service.rotateRewardSecret(TENANT_ID, ACCOUNT_ID, plaintext, Duration.ofMinutes(20));

        ArgumentCaptor<SkitAdRewardSecretVersionDO> inserted =
                ArgumentCaptor.forClass(SkitAdRewardSecretVersionDO.class);
        verify(rewardSecretMapper).insert(inserted.capture());
        SkitAdRewardSecretVersionDO encrypted = inserted.getValue();
        assertEquals(2, metadata.getVersion());
        assertEquals("primary", encrypted.getEncryptionKeyId());
        assertEquals(12, encrypted.getNonce().length);
        assertFalse(Arrays.equals(plaintext, encrypted.getCiphertext()));
        assertFalse(encrypted.toString().contains("provider-reward-secret"));
        verify(rewardSecretMapper).retireActiveVersion(TENANT_ID, ACCOUNT_ID, old.getId(),
                LocalDateTime.ofInstant(NOW.plus(Duration.ofMinutes(20)), ZoneOffset.UTC));

        when(rewardSecretMapper.selectByVersion(TENANT_ID, ACCOUNT_ID, 2)).thenReturn(encrypted);
        SkitAdCredentialVersionService.ResolvedRewardSecret resolved = service.resolveRewardSecret(
                TENANT_ID, ACCOUNT_ID, 2,
                LocalDateTime.ofInstant(NOW.plusSeconds(30), ZoneOffset.UTC),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        java.lang.reflect.Field secretField =
                SkitAdCredentialVersionService.ResolvedRewardSecret.class.getDeclaredField("secret");
        secretField.setAccessible(true);
        byte[] internal = (byte[]) secretField.get(resolved);
        byte[][] captured = new byte[1][];
        assertArrayEquals(plaintext, resolved.withSecret(secret -> {
            captured[0] = secret;
            return secret.clone();
        }));
        assertArrayEquals(new byte[plaintext.length], captured[0],
                "the scoped callback buffer must be zeroed after use");
        assertArrayEquals(new byte[plaintext.length], internal,
                "the internal secret buffer must be zeroed after use");
        assertNull(secretField.get(resolved));
        assertThrows(IllegalStateException.class, () -> resolved.withSecret(byte[]::clone));
        assertFalse(resolved.toString().contains("provider-reward-secret"));
    }

    @Test
    void resolvedRewardSecretZeroesScopedBuffersAfterCallbackFailureAndRejectsReuse() throws Exception {
        byte[] plaintext = "failure-secret".getBytes(StandardCharsets.UTF_8);
        SkitAdCredentialVersionService.ResolvedRewardSecret resolved =
                new SkitAdCredentialVersionService.ResolvedRewardSecret(TENANT_ID, ACCOUNT_ID, 1,
                        true, null, plaintext);
        java.lang.reflect.Field secretField =
                SkitAdCredentialVersionService.ResolvedRewardSecret.class.getDeclaredField("secret");
        secretField.setAccessible(true);
        byte[] internal = (byte[]) secretField.get(resolved);
        byte[][] captured = new byte[1][];

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> resolved.withSecret(secret -> {
                    captured[0] = secret;
                    throw new IllegalArgumentException("callback failed");
                }));

        assertEquals("callback failed", failure.getMessage());
        assertArrayEquals(new byte[plaintext.length], captured[0],
                "the scoped callback buffer must be zeroed when the callback throws");
        assertArrayEquals(new byte[plaintext.length], internal,
                "the internal secret buffer must be zeroed when the callback throws");
        assertNull(secretField.get(resolved));
        assertThrows(IllegalStateException.class, () -> resolved.withSecret(byte[]::clone));
    }

    @Test
    void rewardSecretResolutionRejectsExpiredSessionRevokedVersionAndAadSwap() {
        byte[] plaintext = "secret".getBytes(StandardCharsets.UTF_8);
        SkitAdCredentialCryptoService.Context context =
                SkitAdCredentialCryptoService.Context.rewardSecret(TENANT_ID, ACCOUNT_ID, 3, 1);
        SkitAdCredentialCryptoService.EncryptedSecret encrypted = crypto.encrypt(context, plaintext);
        SkitAdRewardSecretVersionDO row = new SkitAdRewardSecretVersionDO().setId(7L)
                .setAdAccountId(ACCOUNT_ID).setSecretVersion(3).setCiphertext(encrypted.getCiphertext())
                .setNonce(encrypted.getNonce()).setEncryptionKeyId(encrypted.getKeyId())
                .setEnvelopeVersion(encrypted.getEnvelopeVersion()).setActive(false)
                .setAcceptUntil(LocalDateTime.ofInstant(NOW.plusSeconds(120), ZoneOffset.UTC));
        row.setTenantId(TENANT_ID);
        when(rewardSecretMapper.selectByVersion(TENANT_ID, ACCOUNT_ID, 3)).thenReturn(row);
        SkitAdCredentialVersionServiceImpl service = service(new SequenceSecureRandom());

        assertThrows(IllegalStateException.class, () -> service.resolveRewardSecret(TENANT_ID, ACCOUNT_ID, 3,
                LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        row.setRevokedAt(LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        assertThrows(IllegalStateException.class, () -> service.resolveRewardSecret(TENANT_ID, ACCOUNT_ID, 3,
                LocalDateTime.ofInstant(NOW.plusSeconds(30), ZoneOffset.UTC),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        row.setRevokedAt(null).setTenantId(TENANT_ID + 1);
        assertThrows(IllegalStateException.class, () -> service.resolveRewardSecret(TENANT_ID, ACCOUNT_ID, 3,
                LocalDateTime.ofInstant(NOW.plusSeconds(30), ZoneOffset.UTC),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
    }

    @Test
    void aesGcmIsNonDeterministicCapacitySafeAndFailsClosedForTamperWrongKeyAndMissingKey() {
        byte[] plaintext = new byte[2048];
        Arrays.fill(plaintext, (byte) 0x6b);
        SkitAdCredentialCryptoService.Context context =
                SkitAdCredentialCryptoService.Context.rewardSecret(TENANT_ID, ACCOUNT_ID, 1, 1);

        SkitAdCredentialCryptoService.EncryptedSecret first = crypto.encrypt(context, plaintext);
        SkitAdCredentialCryptoService.EncryptedSecret second = crypto.encrypt(context, plaintext);

        assertNotEquals(Base64.getEncoder().encodeToString(first.getNonce()),
                Base64.getEncoder().encodeToString(second.getNonce()));
        assertNotEquals(Base64.getEncoder().encodeToString(first.getCiphertext()),
                Base64.getEncoder().encodeToString(second.getCiphertext()));
        assertTrue(first.getCiphertext().length <= 4096);
        assertArrayEquals(plaintext, crypto.decrypt(context, first));

        byte[] tampered = first.getCiphertext();
        tampered[0] ^= 1;
        SkitAdCredentialCryptoService.EncryptedSecret tamperedEnvelope =
                new SkitAdCredentialCryptoService.EncryptedSecret(tampered, first.getNonce(),
                        first.getKeyId(), first.getEnvelopeVersion());
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(context, tamperedEnvelope));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(
                SkitAdCredentialCryptoService.Context.rewardSecret(TENANT_ID + 1, ACCOUNT_ID, 1, 1), first));

        byte[] wrongKey = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.US_ASCII);
        SkitAdCredentialCryptoService wrong =
                new SkitAesGcmCredentialCryptoService("primary", singletonKey("primary", wrongKey));
        assertThrows(IllegalStateException.class, () -> wrong.decrypt(context, first));
        SkitAdCredentialCryptoService missing =
                new SkitAesGcmCredentialCryptoService("primary", Collections.emptyMap());
        assertThrows(IllegalStateException.class, () -> missing.encrypt(context, plaintext));
    }

    @Test
    void missingProductionKeyFailsBeforeRewardSecretRowMutation() {
        SkitAdCredentialCryptoService missing =
                new SkitAesGcmCredentialCryptoService("primary", Collections.emptyMap());
        SkitAdCredentialVersionServiceImpl service = service(new SequenceSecureRandom(), missing);
        when(accountMapper.lockByTenantAndId(TENANT_ID, ACCOUNT_ID)).thenReturn(ACCOUNT_ID);
        when(rewardSecretMapper.selectMaxVersion(TENANT_ID, ACCOUNT_ID)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.rotateRewardSecret(TENANT_ID, ACCOUNT_ID,
                "never-stored".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(10)));

        verify(rewardSecretMapper, never()).retireActiveVersion(anyLong(), anyLong(), anyLong(),
                any(LocalDateTime.class));
        verify(rewardSecretMapper, never()).insert(any(SkitAdRewardSecretVersionDO.class));
    }

    @Test
    void cryptoConfigurationRejectsNonAsciiAndInvalidLengthsWithoutRenderingKeyValues() {
        SkitAdCredentialCryptoProperties properties = new SkitAdCredentialCryptoProperties();
        properties.setCurrentKeyId("primary");
        Map<String, String> configured = new HashMap<>();
        configured.put("primary", "not-a-valid-length");
        properties.setKeys(configured);
        assertFalse(properties.toString().contains("not-a-valid-length"));
        assertThrows(IllegalArgumentException.class,
                () -> new SkitAdCredentialCryptoConfiguration().skitAdCredentialCryptoService(properties));

        configured.put("primary", "0123456789abcde密");
        properties.setKeys(configured);
        assertThrows(IllegalArgumentException.class,
                () -> new SkitAdCredentialCryptoConfiguration().skitAdCredentialCryptoService(properties));

        properties.setKeys(Collections.emptyMap());
        SkitAdCredentialCryptoService missing =
                new SkitAdCredentialCryptoConfiguration().skitAdCredentialCryptoService(properties);
        assertThrows(IllegalStateException.class, () -> missing.encrypt(
                SkitAdCredentialCryptoService.Context.rewardSecret(TENANT_ID, ACCOUNT_ID, 1, 1),
                "secret".getBytes(StandardCharsets.UTF_8)));
        assertNull(properties.getKeys().get("primary"));
    }

    @Test
    void applicationConfigurationUsesOnlyTheDedicatedEnvironmentKeyAndNeverSerializesIt() throws Exception {
        String dedicatedKey = "dedicated-key-0123456789abcdefgh";
        String legacySharedKey = "legacy-shared-key-0123456789abcd";
        Map<String, Object> environment = new HashMap<>();
        environment.put("SKIT_AD_CREDENTIAL_KEY", dedicatedKey);
        environment.put("SKIT_AD_ENCRYPTION_KEY", legacySharedKey);

        SkitAdCredentialCryptoProperties properties = bindApplicationCredentialProperties(environment);

        assertEquals("primary", properties.getCurrentKeyId());
        assertEquals(dedicatedKey, properties.getCurrentKey());
        assertTrue(properties.getKeys().isEmpty());
        assertFalse(properties.toString().contains(dedicatedKey));
        assertFalse(properties.toString().contains(legacySharedKey));
        String json = new ObjectMapper().writeValueAsString(properties);
        assertFalse(json.contains(dedicatedKey));
        assertFalse(json.contains(legacySharedKey));

        SkitAdCredentialCryptoService configured =
                new SkitAdCredentialCryptoConfiguration().skitAdCredentialCryptoService(properties);
        SkitAdCredentialCryptoService.Context context = SkitAdCredentialCryptoService.Context.rewardSecret(
                TENANT_ID, ACCOUNT_ID, 1, SkitAdCredentialCryptoService.CURRENT_ENVELOPE_VERSION);
        byte[] plaintext = "configured-reward-secret".getBytes(StandardCharsets.UTF_8);
        SkitAdCredentialCryptoService.EncryptedSecret encrypted = configured.encrypt(context, plaintext);
        assertArrayEquals(plaintext, configured.decrypt(context, encrypted));

        Map<String, Object> missingDedicatedEnvironment = new HashMap<>();
        missingDedicatedEnvironment.put("SKIT_AD_ENCRYPTION_KEY", legacySharedKey);
        SkitAdCredentialCryptoProperties missingProperties =
                bindApplicationCredentialProperties(missingDedicatedEnvironment);
        SkitAdCredentialCryptoService missing =
                new SkitAdCredentialCryptoConfiguration().skitAdCredentialCryptoService(missingProperties);
        assertThrows(IllegalStateException.class, () -> missing.encrypt(context, plaintext));
    }

    @Test
    void applicationConfigurationBindsTheDedicatedKeyUnderItsPersistedKeyId() throws Exception {
        String dedicatedKey = "rotated-key-0123456789abcdefghij";
        Map<String, Object> environment = new HashMap<>();
        environment.put("SKIT_AD_CREDENTIAL_KEY", dedicatedKey);
        environment.put("SKIT_AD_CREDENTIAL_KEY_ID", "rotation-2026-07");

        SkitAdCredentialCryptoProperties properties = bindApplicationCredentialProperties(environment);

        assertEquals("rotation-2026-07", properties.getCurrentKeyId());
        assertEquals(dedicatedKey, properties.getCurrentKey());
        SkitAdCredentialCryptoService configured =
                new SkitAdCredentialCryptoConfiguration().skitAdCredentialCryptoService(properties);
        SkitAdCredentialCryptoService.Context context = SkitAdCredentialCryptoService.Context.rewardSecret(
                TENANT_ID, ACCOUNT_ID, 1, SkitAdCredentialCryptoService.CURRENT_ENVELOPE_VERSION);
        byte[] plaintext = "configured-rotated-key".getBytes(StandardCharsets.UTF_8);
        SkitAdCredentialCryptoService.EncryptedSecret encrypted = configured.encrypt(context, plaintext);
        assertEquals("rotation-2026-07", encrypted.getKeyId());
        assertArrayEquals(plaintext, configured.decrypt(context, encrypted));
    }

    private SkitAdCredentialVersionServiceImpl service(SecureRandom random) {
        return service(random, crypto);
    }

    private SkitAdCredentialVersionServiceImpl service(SecureRandom random,
                                                        SkitAdCredentialCryptoService suppliedCrypto) {
        return service(random, suppliedCrypto, clock);
    }

    private SkitAdCredentialVersionServiceImpl service(SecureRandom random,
                                                        SkitAdCredentialCryptoService suppliedCrypto,
                                                        Clock suppliedClock) {
        return new SkitAdCredentialVersionServiceImpl(accountMapper, callbackKeyMapper, rewardSecretMapper,
                suppliedCrypto, suppliedClock, random);
    }

    private static SkitAdCallbackKeyDO callbackRow(int version, boolean active) {
        SkitAdCallbackKeyDO row = new SkitAdCallbackKeyDO().setId((long) version)
                .setAdAccountId(ACCOUNT_ID).setKeyVersion(version).setActive(active);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private static SkitAdRewardSecretVersionDO rewardRow(int version, boolean active) {
        SkitAdRewardSecretVersionDO row = new SkitAdRewardSecretVersionDO().setId((long) version)
                .setAdAccountId(ACCOUNT_ID).setSecretVersion(version).setActive(active);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private static Map<String, byte[]> singletonKey(String keyId, byte[] key) {
        Map<String, byte[]> result = new HashMap<>();
        result.put(keyId, key.clone());
        return result;
    }

    private static SkitAdCredentialCryptoProperties bindApplicationCredentialProperties(
            Map<String, Object> environmentValues) throws IOException {
        Path applicationYaml = locateApplicationYaml();
        String yaml = new String(Files.readAllBytes(applicationYaml), StandardCharsets.UTF_8);
        int blockStart = yaml.indexOf("skit:\n  ad:\n    credential-encryption:");
        int blockEnd = yaml.indexOf("\nmybatis-plus-join:", blockStart);
        if (blockStart < 0 || blockEnd < 0) {
            throw new IllegalStateException("Could not locate the Skit credential-encryption YAML block");
        }
        String credentialYaml = yaml.substring(blockStart, blockEnd);
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("task2-test-environment", environmentValues));
        for (PropertySource<?> propertySource : new YamlPropertySourceLoader().load(
                "skit-ad-credential-test", new ByteArrayResource(
                        credentialYaml.getBytes(StandardCharsets.UTF_8)))) {
            environment.getPropertySources().addLast(propertySource);
        }
        return Binder.get(environment).bind("skit.ad.credential-encryption",
                Bindable.of(SkitAdCredentialCryptoProperties.class))
                .orElseGet(SkitAdCredentialCryptoProperties::new);
    }

    private static Path locateApplicationYaml() {
        Path workingDirectory = Paths.get("").toAbsolutePath();
        Path rootRelative = workingDirectory.resolve("yudao-server/src/main/resources/application.yaml");
        if (Files.isRegularFile(rootRelative)) {
            return rootRelative;
        }
        Path moduleRelative = workingDirectory.resolve("../yudao-server/src/main/resources/application.yaml")
                .normalize();
        if (!Files.isRegularFile(moduleRelative)) {
            throw new IllegalStateException("Could not locate yudao-server application.yaml from " + workingDirectory);
        }
        return moduleRelative;
    }

    private static byte[] sequence(int start) {
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (start + index);
        }
        return result;
    }

    private static final class SequenceSecureRandom extends SecureRandom {

        private final byte[][] values;
        private int index;

        private SequenceSecureRandom(byte[]... values) {
            this.values = values;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (index >= values.length) {
                throw new IllegalStateException("No deterministic random value remains");
            }
            byte[] value = values[index++];
            if (value.length != bytes.length) {
                throw new IllegalArgumentException("Unexpected requested random length " + bytes.length);
            }
            System.arraycopy(value, 0, bytes, 0, bytes.length);
        }
    }

}
