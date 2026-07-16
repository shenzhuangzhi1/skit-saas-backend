package cn.iocoder.yudao.module.skit.service.app;

import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppBuildMaterialDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppBuildMaterialMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppReleaseProfileMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoService;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAesGcmCredentialCryptoService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitAppBuildMaterialServiceImplTest {

    private static final long TENANT_ID = 162L;
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII);

    @Mock
    private SkitAppBuildMaterialMapper materialMapper;
    @Mock
    private SkitAppReleaseProfileMapper releaseProfileMapper;
    @Mock
    private SkitAdAccountService adAccountService;

    private SkitAppBuildMaterialServiceImpl service;
    private SkitAdCredentialCryptoService crypto;
    private SkitAppReleaseProfileDO profile;

    @BeforeEach
    void setUp() {
        service = new SkitAppBuildMaterialServiceImpl();
        crypto = new SkitAesGcmCredentialCryptoService("primary", Collections.singletonMap("primary", KEY));
        ReflectionTestUtils.setField(service, "materialMapper", materialMapper);
        ReflectionTestUtils.setField(service, "releaseProfileMapper", releaseProfileMapper);
        ReflectionTestUtils.setField(service, "adAccountService", adAccountService);
        ReflectionTestUtils.setField(service, "credentialCrypto", crypto);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        profile = new SkitAppReleaseProfileDO();
        profile.setTenantId(TENANT_ID);
        profile.setNativePackage("top.example.app");
        profile.setNativeProtocolVersion(1);
        when(adAccountService.getSettings()).thenReturn(takuSettings());
    }

    @Test
    void firstSaveCreatesVersionOneAndOnlyReturnsConfigurationFlags() {
        when(releaseProfileMapper.selectByTenantIdForUpdate(TENANT_ID)).thenReturn(profile);
        when(materialMapper.selectActiveForUpdate(TENANT_ID)).thenReturn(null);
        when(materialMapper.selectLatestForUpdate(TENANT_ID)).thenReturn(null);
        when(materialMapper.insert(any(SkitAppBuildMaterialDO.class))).thenAnswer(invocation -> {
            SkitAppBuildMaterialDO row = invocation.getArgument(0);
            row.setId(9L);
            return 1;
        });

        SkitAppBuildMaterialService.MaterialView result = service.saveMaterial(command("https://api.example.com", 1));

        assertEquals(1, result.getMaterialVersion());
        assertTrue(result.getPangleSettingsConfigured());
        assertTrue(result.getSigningConfigured());
        assertTrue(result.getTakuAppKeyConfigured());
        assertFalse(result.auditCanonical().contains("store-password"));
        assertFalse(result.toString().contains("store-password"));
    }

    @Test
    void secondSaveRetiresPriorVersionAndBlankSecretsPreserveTheEnvelope() throws Exception {
        when(releaseProfileMapper.selectByTenantIdForUpdate(TENANT_ID)).thenReturn(profile);
        SkitAppBuildMaterialDO previous = storedRow(1, "https://api.example.com");
        when(materialMapper.selectActiveForUpdate(TENANT_ID)).thenReturn(previous);
        when(materialMapper.selectLatestForUpdate(TENANT_ID)).thenReturn(previous);
        when(materialMapper.retireActive(TENANT_ID, previous.getId())).thenReturn(1);
        when(materialMapper.insert(any(SkitAppBuildMaterialDO.class))).thenReturn(1);

        SkitAppBuildMaterialService.MaterialCommand command = command("https://api.example.com/v2", 2)
                .setPangleSettingsJson("").setReleaseKeystoreBase64("")
                .setStorePassword("").setKeyAlias("").setKeyPassword("");
        SkitAppBuildMaterialService.MaterialView result = service.saveMaterial(command);

        assertEquals(2, result.getMaterialVersion());
        ArgumentCaptor<SkitAppBuildMaterialDO> captor = ArgumentCaptor.forClass(SkitAppBuildMaterialDO.class);
        verify(materialMapper).insert(captor.capture());
        SkitAppBuildMaterialDO next = captor.getValue();
        assertEquals(2, next.getMaterialVersion());
        assertFalse(java.util.Arrays.equals(previous.getSecretCiphertext(), next.getSecretCiphertext()));
    }

    @Test
    void invalidUrlAndMissingSigningTupleAreRejectedBeforePersistence() {
        when(releaseProfileMapper.selectByTenantIdForUpdate(TENANT_ID)).thenReturn(profile);
        when(materialMapper.selectActiveForUpdate(TENANT_ID)).thenReturn(null);
        when(materialMapper.selectLatestForUpdate(TENANT_ID)).thenReturn(null);

        assertThrows(RuntimeException.class, () -> service.saveMaterial(command("http://api.example.com", 1)));
        assertThrows(RuntimeException.class, () -> service.saveMaterial(
                command("https://api.example.com", 1).setKeyPassword("")));
    }

    @Test
    void readsAreAlwaysScopedToTheRequestedTenant() {
        when(materialMapper.selectActive(20L)).thenReturn(null);
        when(releaseProfileMapper.selectByTenantId(20L)).thenReturn(profile);

        service.getMaterial(20L);

        verify(materialMapper).selectActive(20L);
        verify(releaseProfileMapper).selectByTenantId(20L);
        verify(materialMapper, org.mockito.Mockito.never()).selectActive(21L);
        verify(releaseProfileMapper, org.mockito.Mockito.never()).selectByTenantId(21L);
    }

    private SkitAppBuildMaterialDO storedRow(int version, String apiBaseUrl) throws Exception {
        SkitAppBuildMaterialDO row = new SkitAppBuildMaterialDO();
        row.setId(7L);
        row.setTenantId(TENANT_ID);
        row.setMaterialVersion(version);
        row.setApiBaseUrl(apiBaseUrl);
        row.setAppName("沈壮志短剧");
        row.setNativeVersionCode(1L);
        row.setNativeVersionName("2.3.0");
        row.setRuntimeReleaseNo((long) version);
        java.util.Map<String, String> secret = new java.util.LinkedHashMap<>();
        secret.put("pangleSettingsJson", pangleSettings());
        secret.put("releaseKeystoreBase64",
                Base64.getEncoder().encodeToString("keystore".getBytes(StandardCharsets.UTF_8)));
        secret.put("storePassword", "store-password");
        secret.put("keyAlias", "release");
        secret.put("keyPassword", "key-password");
        ObjectMapper objectMapper = (ObjectMapper) ReflectionTestUtils.getField(service, "objectMapper");
        byte[] plaintext = objectMapper.writeValueAsBytes(secret);
        SkitAdCredentialCryptoService.EncryptedSecret encrypted = crypto.encrypt(
                SkitAdCredentialCryptoService.Context.appBuildMaterial(TENANT_ID, version, 1), plaintext);
        row.setSecretCiphertext(encrypted.getCiphertext());
        row.setSecretNonce(encrypted.getNonce());
        row.setEncryptionKeyId(encrypted.getKeyId());
        row.setEnvelopeVersion(encrypted.getEnvelopeVersion());
        row.setActive(true);
        return row;
    }

    private SkitAdAccountService.Settings takuSettings() {
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setTakuUsername("taku");
        settings.setTakuAppId("taku-app");
        settings.setTakuPlacementId("reward");
        settings.setTakuAppKeyConfigured(true);
        return settings;
    }

    private SkitAppBuildMaterialService.MaterialCommand command(String apiBaseUrl, long runtimeReleaseNo) {
        return new SkitAppBuildMaterialService.MaterialCommand()
                .setTenantId(TENANT_ID)
                .setApiBaseUrl(apiBaseUrl)
                .setAppName("沈壮志短剧")
                .setNativeVersionCode(1L)
                .setNativeVersionName("2.3.0")
                .setRuntimeReleaseNo(runtimeReleaseNo)
                .setPangleSettingsJson(pangleSettings())
                .setReleaseKeystoreBase64(Base64.getEncoder().encodeToString("keystore".getBytes(StandardCharsets.UTF_8)))
                .setStorePassword("store-password")
                .setKeyAlias("release")
                .setKeyPassword("key-password")
                .setReason("为租户保存本地 Mac 构建资料");
    }

    private static String pangleSettings() {
        return "{\"init\":{\"site_id\":\"5850994\",\"app_id\":\"1037672\"},"
                + "\"license_config\":[{\"PackageName\":\"top.example.app\"}]}";
    }
}
