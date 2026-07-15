package cn.iocoder.yudao.framework.encrypt;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.asymmetric.RSA;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * API 加解密回归测试。测试密钥只在运行时生成，禁止输出或提交部署密钥。
 *
 * @author 芋道源码
 */
@SuppressWarnings("ConstantValue")
public class ApiEncryptTest {

    @Test
    public void testEncrypt_aes() {
        String key = StrUtil.repeat('a', 32);
        String body = "{\n" +
                "  \"username\": \"admin\",\n" +
                "  \"password\": \"admin123\",\n" +
                "  \"uuid\": \"3acd87a09a4f48fb9118333780e94883\",\n" +
                "  \"code\": \"1024\"\n" +
                "}";
        String encrypt = SecureUtil.aes(StrUtil.utf8Bytes(key))
                .encryptBase64(body);
        String decrypted = SecureUtil.aes(StrUtil.utf8Bytes(key)).decryptStr(encrypt);
        assertEquals(body, decrypted);
    }

    @Test
    public void testEncrypt_rsa() {
        RSA rsa = SecureUtil.rsa();
        String body = "{\n" +
                "  \"username\": \"admin\",\n" +
                "  \"password\": \"admin123\",\n" +
                "  \"uuid\": \"3acd87a09a4f48fb9118333780e94883\",\n" +
                "  \"code\": \"1024\"\n" +
                "}";
        String encrypt = SecureUtil.rsa(null, rsa.getPublicKeyBase64())
                .encryptBase64(body, KeyType.PublicKey);
        String decrypted = SecureUtil.rsa(rsa.getPrivateKeyBase64(), null)
                .decryptStr(encrypt, KeyType.PrivateKey);
        assertEquals(body, decrypted);
    }

}
