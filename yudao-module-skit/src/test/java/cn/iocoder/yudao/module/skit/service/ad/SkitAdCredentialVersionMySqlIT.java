package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRewardSecretVersionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackKeyMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdRewardSecretVersionMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoService;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAesGcmCredentialCryptoService;
import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkitAdCredentialVersionMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final byte[] TEST_AT_REST_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    @Test
    void concurrentFirstRotationsUseTheAccountLockForMonotonicImmutableVersions() throws Exception {
        long tenantId = 8201L;
        long accountId = 8202L;
        insertAccount(tenantId, accountId, "CONCURRENT");
        SkitAdCredentialVersionServiceImpl service = service(new SecureRandom());

        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SkitAdCredentialVersionService.CallbackKeyIssue>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("callback start signal timed out");
                    }
                    return inTransaction(() -> service.rotateCallbackKey(
                            tenantId, accountId, Duration.ofMinutes(30)));
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "callback workers did not become ready");
            start.countDown();

            Set<String> issuedKeys = new HashSet<>();
            for (Future<SkitAdCredentialVersionService.CallbackKeyIssue> future : futures) {
                issuedKeys.add(future.get(30, TimeUnit.SECONDS).consumeCallbackKey());
            }
            assertEquals(workers, issuedKeys.size());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        List<Integer> versions = jdbc().queryForList("SELECT key_version FROM skit_ad_callback_key "
                + "WHERE tenant_id=? AND ad_account_id=? ORDER BY key_version", Integer.class, tenantId, accountId);
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8), versions);
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_key "
                + "WHERE tenant_id=? AND ad_account_id=? AND active=b'1'", Integer.class, tenantId, accountId));
        assertEquals(workers - 1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_key "
                        + "WHERE tenant_id=? AND ad_account_id=? AND active=b'0' AND accept_until IS NOT NULL",
                Integer.class, tenantId, accountId));
    }

    @Test
    void realGlobalHashCollisionRetriesWithoutCrossTenantLookup() throws Exception {
        long tenantId = 8301L;
        long accountId = 8302L;
        long otherTenantId = 8303L;
        long otherAccountId = 8304L;
        insertAccount(tenantId, accountId, "COLLISION_A");
        insertAccount(otherTenantId, otherAccountId, "COLLISION_B");
        byte[] collisionMaterial = sequence(0);
        byte[] replacementMaterial = sequence(32);
        String collisionKey = Base64.getUrlEncoder().withoutPadding().encodeToString(collisionMaterial);
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) VALUES (?,?,?,?,b'1')",
                otherTenantId, otherAccountId, 1, sha256(collisionKey));

        SkitAdCredentialVersionService.CallbackKeyIssue issue = inTransaction(() -> service(
                new SequenceSecureRandom(collisionMaterial, replacementMaterial))
                .rotateCallbackKey(tenantId, accountId, Duration.ofMinutes(15)));

        String replacementKey = Base64.getUrlEncoder().withoutPadding().encodeToString(replacementMaterial);
        assertEquals(replacementKey, issue.consumeCallbackKey());
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_key "
                        + "WHERE tenant_id=? AND ad_account_id=? AND callback_key_hash=?",
                Integer.class, tenantId, accountId, sha256(replacementKey)));
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_key "
                + "WHERE callback_key_hash=?", Integer.class, sha256(collisionKey)));
    }

    @Test
    void concurrentRewardSecretRotationsRemainMonotonicAndDecryptOnlyTheirRecordedVersion() throws Exception {
        long tenantId = 8401L;
        long accountId = 8402L;
        insertAccount(tenantId, accountId, "REWARD_CONCUR");
        SkitAdCredentialVersionServiceImpl service = service(new SecureRandom());

        int workers = 5;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SkitAdCredentialVersionService.CredentialMetadata>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                final byte[] secret = ("reward-secret-" + index).getBytes(StandardCharsets.UTF_8);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("reward start signal timed out");
                    }
                    return inTransaction(() -> service.rotateRewardSecret(
                            tenantId, accountId, secret, Duration.ofMinutes(30)));
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "reward workers did not become ready");
            start.countDown();
            Set<Integer> versions = new HashSet<>();
            for (Future<SkitAdCredentialVersionService.CredentialMetadata> future : futures) {
                versions.add(future.get(30, TimeUnit.SECONDS).getVersion());
            }
            assertEquals(new HashSet<>(Arrays.asList(1, 2, 3, 4, 5)), versions);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_reward_secret_version "
                + "WHERE tenant_id=? AND ad_account_id=? AND active=b'1'", Integer.class, tenantId, accountId));
        Integer activeVersion = jdbc().queryForObject("SELECT secret_version FROM skit_ad_reward_secret_version "
                + "WHERE tenant_id=? AND ad_account_id=? AND active=b'1'", Integer.class, tenantId, accountId);
        assertTrue(activeVersion >= 1 && activeVersion <= workers);
        SkitAdCredentialVersionService.ResolvedRewardSecret resolved = service.resolveRewardSecret(
                tenantId, accountId, activeVersion, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5),
                LocalDateTime.now(ZoneOffset.UTC));
        boolean validSecret = resolved.withSecret(secret ->
                new String(secret, StandardCharsets.UTF_8).startsWith("reward-secret-"));
        assertTrue(validSecret);
    }

    @Test
    void exhaustedCallbackHashCollisionsRollBackTheRetiredActiveVersion() throws Exception {
        long tenantId = 8501L;
        long accountId = 8502L;
        insertAccount(tenantId, accountId, "CALLBACK_ROLL");
        byte[] collisionMaterial = sequence(64);
        String collisionKey = Base64.getUrlEncoder().withoutPadding().encodeToString(collisionMaterial);
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) VALUES (?,?,?,?,b'1')",
                tenantId, accountId, 1, sha256(collisionKey));

        SkitAdCredentialVersionServiceImpl service = service(
                new SequenceSecureRandom(repeat(collisionMaterial, 8)));
        assertThrows(IllegalStateException.class, () -> inTransaction(() ->
                service.rotateCallbackKey(tenantId, accountId, Duration.ofMinutes(15))));

        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_key "
                + "WHERE tenant_id=? AND ad_account_id=?", Integer.class, tenantId, accountId));
        assertEquals(1, jdbc().queryForObject("SELECT key_version FROM skit_ad_callback_key "
                + "WHERE tenant_id=? AND ad_account_id=? AND active=b'1'", Integer.class, tenantId, accountId));
        assertNull(jdbc().queryForObject("SELECT accept_until FROM skit_ad_callback_key "
                + "WHERE tenant_id=? AND ad_account_id=?", Timestamp.class, tenantId, accountId));
    }

    @Test
    void rewardInsertFailureRollsBackTheRetiredActiveVersion() {
        long tenantId = 8601L;
        long accountId = 8602L;
        insertAccount(tenantId, accountId, "REWARD_ROLLBACK");
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,?,?,?,?,1,b'1')",
                tenantId, accountId, 1, new byte[]{1}, new byte[12], "test-primary");

        SkitAdRewardSecretVersionMapper failingRewardMapper = rewardMapper();
        doReturn(0).when(failingRewardMapper).selectMaxVersion(tenantId, accountId);
        SkitAdCredentialVersionServiceImpl service = new SkitAdCredentialVersionServiceImpl(
                accountMapper(), callbackMapper(), failingRewardMapper, cryptoService(),
                Clock.systemUTC(), new SecureRandom());
        assertThrows(RuntimeException.class, () -> inTransaction(() -> service.rotateRewardSecret(
                tenantId, accountId, "replacement-secret".getBytes(StandardCharsets.UTF_8),
                Duration.ofMinutes(15))));

        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_reward_secret_version "
                + "WHERE tenant_id=? AND ad_account_id=?", Integer.class, tenantId, accountId));
        assertEquals(1, jdbc().queryForObject("SELECT secret_version FROM skit_ad_reward_secret_version "
                + "WHERE tenant_id=? AND ad_account_id=? AND active=b'1'", Integer.class, tenantId, accountId));
        assertNull(jdbc().queryForObject("SELECT accept_until FROM skit_ad_reward_secret_version "
                + "WHERE tenant_id=? AND ad_account_id=?", Timestamp.class, tenantId, accountId));
    }

    @Test
    void credentialIdentityMaterialAndRowsAreImmutableAtTheDatabaseBoundary() {
        long tenantId = 8701L;
        long accountId = 8702L;
        insertAccount(tenantId, accountId, "IMMUTABLE");
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(id,tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (8703,?,?,1,UNHEX(REPEAT('31',32)),b'1')",
                tenantId, accountId);
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(id,tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (8704,?,?,1,?,?,'test-primary',1,b'1')",
                tenantId, accountId, new byte[]{1, 2, 3}, new byte[12]);

        assertCredentialMutationRejected("credential identity and material are immutable",
                "UPDATE skit_ad_callback_key SET key_version=2,callback_key_hash=UNHEX(REPEAT('32',32)),"
                        + "deleted=b'1' WHERE id=8703");
        assertCredentialMutationRejected("credential identity and material are immutable",
                "UPDATE skit_ad_reward_secret_version SET secret_version=2,ciphertext=X'040506',"
                        + "nonce=UNHEX(REPEAT('01',12)),encryption_key_id='other',deleted=b'1' WHERE id=8704");
        assertCredentialMutationRejected("credential version rows cannot be deleted",
                "DELETE FROM skit_ad_callback_key WHERE id=8703");
        assertCredentialMutationRejected("credential version rows cannot be deleted",
                "DELETE FROM skit_ad_reward_secret_version WHERE id=8704");

        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_key SET active=b'0',"
                + "accept_until=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 15 MINUTE) WHERE id=8703"));
        assertCredentialMutationRejected("credential lifecycle is monotonic",
                "UPDATE skit_ad_callback_key SET active=b'1',accept_until=NULL WHERE id=8703");
    }

    private SkitAdCredentialVersionServiceImpl service(SecureRandom random) {
        return new SkitAdCredentialVersionServiceImpl(accountMapper(), callbackMapper(), rewardMapper(),
                cryptoService(), Clock.systemUTC(), random);
    }

    private SkitAdCredentialCryptoService cryptoService() {
        Map<String, byte[]> keys = new HashMap<>();
        keys.put("test-primary", TEST_AT_REST_KEY.clone());
        return new SkitAesGcmCredentialCryptoService("test-primary", keys);
    }

    private SkitAdAccountMapper accountMapper() {
        SkitAdAccountMapper mapper = mock(SkitAdAccountMapper.class);
        when(mapper.lockByTenantAndId(anyLong(), anyLong())).thenAnswer(invocation -> jdbc().query(
                "SELECT id FROM skit_ad_account WHERE tenant_id=? AND id=? AND deleted=b'0' FOR UPDATE",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
                invocation.getArgument(0), invocation.getArgument(1)));
        return mapper;
    }

    private SkitAdCallbackKeyMapper callbackMapper() {
        SkitAdCallbackKeyMapper mapper = mock(SkitAdCallbackKeyMapper.class);
        when(mapper.selectMaxVersion(anyLong(), anyLong())).thenAnswer(invocation -> jdbc().queryForObject(
                "SELECT MAX(key_version) FROM skit_ad_callback_key WHERE tenant_id=? AND ad_account_id=?",
                Integer.class, invocation.getArgument(0), invocation.getArgument(1)));
        when(mapper.selectActiveForUpdate(anyLong(), anyLong())).thenAnswer(invocation -> queryCallback(
                "SELECT * FROM skit_ad_callback_key WHERE tenant_id=? AND ad_account_id=? "
                        + "AND active=b'1' AND revoked_at IS NULL FOR UPDATE",
                invocation.getArgument(0), invocation.getArgument(1)));
        when(mapper.retireActiveVersion(anyLong(), anyLong(), anyLong(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> jdbc().update("UPDATE skit_ad_callback_key SET active=b'0',accept_until=? "
                                + "WHERE tenant_id=? AND ad_account_id=? AND id=? AND active=b'1' AND revoked_at IS NULL",
                        invocation.getArgument(3), invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2)));
        when(mapper.insert(any(SkitAdCallbackKeyDO.class))).thenAnswer(invocation -> {
            SkitAdCallbackKeyDO row = invocation.getArgument(0);
            return jdbc().update("INSERT INTO skit_ad_callback_key "
                            + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) VALUES (?,?,?,?,?)",
                    row.getTenantId(), row.getAdAccountId(), row.getKeyVersion(), row.getCallbackKeyHash(),
                    row.getActive());
        });
        when(mapper.selectByHash(any(byte[].class))).thenAnswer(invocation -> queryCallback(
                "SELECT * FROM skit_ad_callback_key WHERE callback_key_hash=?", invocation.getArgument(0)));
        return mapper;
    }

    private SkitAdRewardSecretVersionMapper rewardMapper() {
        SkitAdRewardSecretVersionMapper mapper = mock(SkitAdRewardSecretVersionMapper.class);
        when(mapper.selectMaxVersion(anyLong(), anyLong())).thenAnswer(invocation -> jdbc().queryForObject(
                "SELECT MAX(secret_version) FROM skit_ad_reward_secret_version "
                        + "WHERE tenant_id=? AND ad_account_id=?",
                Integer.class, invocation.getArgument(0), invocation.getArgument(1)));
        when(mapper.selectActiveForUpdate(anyLong(), anyLong())).thenAnswer(invocation -> queryReward(
                "SELECT * FROM skit_ad_reward_secret_version WHERE tenant_id=? AND ad_account_id=? "
                        + "AND active=b'1' AND revoked_at IS NULL FOR UPDATE",
                invocation.getArgument(0), invocation.getArgument(1)));
        when(mapper.retireActiveVersion(anyLong(), anyLong(), anyLong(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> jdbc().update("UPDATE skit_ad_reward_secret_version "
                                + "SET active=b'0',accept_until=? WHERE tenant_id=? AND ad_account_id=? "
                                + "AND id=? AND active=b'1' AND revoked_at IS NULL",
                        invocation.getArgument(3), invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2)));
        when(mapper.insert(any(SkitAdRewardSecretVersionDO.class))).thenAnswer(invocation -> {
            SkitAdRewardSecretVersionDO row = invocation.getArgument(0);
            return jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                            + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                            + "envelope_version,active) VALUES (?,?,?,?,?,?,?,?)",
                    row.getTenantId(), row.getAdAccountId(), row.getSecretVersion(), row.getCiphertext(),
                    row.getNonce(), row.getEncryptionKeyId(), row.getEnvelopeVersion(), row.getActive());
        });
        when(mapper.selectByVersion(anyLong(), anyLong(), any(Integer.class))).thenAnswer(invocation -> queryReward(
                "SELECT * FROM skit_ad_reward_secret_version WHERE tenant_id=? AND ad_account_id=? "
                        + "AND secret_version=?", invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2)));
        return mapper;
    }

    private SkitAdCallbackKeyDO queryCallback(String sql, Object... parameters) {
        List<SkitAdCallbackKeyDO> rows = jdbc().query(sql, (resultSet, rowNum) -> {
            SkitAdCallbackKeyDO row = new SkitAdCallbackKeyDO().setId(resultSet.getLong("id"))
                    .setAdAccountId(resultSet.getLong("ad_account_id"))
                    .setKeyVersion(resultSet.getInt("key_version"))
                    .setCallbackKeyHash(resultSet.getBytes("callback_key_hash"))
                    .setActive(resultSet.getBoolean("active"))
                    .setAcceptUntil(localDateTime(resultSet.getTimestamp("accept_until")))
                    .setRevokedAt(localDateTime(resultSet.getTimestamp("revoked_at")));
            row.setTenantId(resultSet.getLong("tenant_id"));
            return row;
        }, parameters);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SkitAdRewardSecretVersionDO queryReward(String sql, Object... parameters) {
        List<SkitAdRewardSecretVersionDO> rows = jdbc().query(sql, (resultSet, rowNum) -> {
            SkitAdRewardSecretVersionDO row = new SkitAdRewardSecretVersionDO().setId(resultSet.getLong("id"))
                    .setAdAccountId(resultSet.getLong("ad_account_id"))
                    .setSecretVersion(resultSet.getInt("secret_version"))
                    .setCiphertext(resultSet.getBytes("ciphertext")).setNonce(resultSet.getBytes("nonce"))
                    .setEncryptionKeyId(resultSet.getString("encryption_key_id"))
                    .setEnvelopeVersion(resultSet.getInt("envelope_version"))
                    .setActive(resultSet.getBoolean("active"))
                    .setAcceptUntil(localDateTime(resultSet.getTimestamp("accept_until")))
                    .setRevokedAt(localDateTime(resultSet.getTimestamp("revoked_at")));
            row.setTenantId(resultSet.getLong("tenant_id"));
            return row;
        }, parameters);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertAccount(long tenantId, long accountId, String provider) {
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,app_key,status) "
                        + "VALUES (?,?,?,?,?,?,?,1)",
                accountId, tenantId, provider, provider, provider, provider, "");
    }

    private void assertCredentialMutationRejected(String triggerMessage, String sql) {
        DataAccessException exception = assertThrows(DataAccessException.class, () -> {
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
            transaction.executeWithoutResult(status -> {
                try {
                    jdbc().update(sql);
                } finally {
                    status.setRollbackOnly();
                }
            });
        });
        assertTrue(exception.getMessage().contains(triggerMessage), exception.getMessage());
    }

    private static LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sequence(int start) {
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (start + index);
        }
        return result;
    }

    private static byte[][] repeat(byte[] value, int count) {
        byte[][] result = new byte[count][];
        for (int index = 0; index < count; index++) {
            result[index] = value.clone();
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
            byte[] value = values[index++];
            System.arraycopy(value, 0, bytes, 0, bytes.length);
        }
    }

}
