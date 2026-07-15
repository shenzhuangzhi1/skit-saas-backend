package cn.iocoder.yudao.framework.websocket.core.security;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.websocket.config.WebSocketProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisWebSocketTicketServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private WebSocketProperties properties;
    private RedisWebSocketTicketService service;

    @BeforeEach
    void setUp() {
        properties = new WebSocketProperties();
        properties.setTicketTtlSeconds(30);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new RedisWebSocketTicketService(redisTemplate, properties, new SecureRandom());
    }

    @Test
    void issueStoresOnlySha256KeyAndMinimalPrincipalForAtMostThirtySeconds() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(30L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        LoginUser loginUser = loginUser(11L, 22L, UserTypeEnum.MEMBER.getValue());

        WebSocketTicket issued = service.issue(loginUser);

        assertThat(Base64.getUrlDecoder().decode(issued.getTicket())).hasSize(32);
        assertThat(issued.getExpiresInSeconds()).isEqualTo(30);
        assertThat(issued.toString()).doesNotContain(issued.getTicket());
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(keyCaptor.capture(), valueCaptor.capture(),
                eq(30L), eq(TimeUnit.SECONDS));
        assertThat(keyCaptor.getValue())
                .isEqualTo(RedisWebSocketTicketService.REDIS_KEY_PREFIX + sha256Hex(issued.getTicket()))
                .doesNotContain(issued.getTicket());
        assertThat(valueCaptor.getValue()).isEqualTo("11:22:1");
    }

    @Test
    void consumeUsesAtomicGetAndDeleteAndRejectsReplay() {
        String ticket = validTicket((byte) 7);
        String key = RedisWebSocketTicketService.REDIS_KEY_PREFIX + sha256Hex(ticket);
        when(valueOperations.getAndDelete(key)).thenReturn("11:22:1", (String) null);

        LoginUser first = service.consume(ticket, 11L);
        LoginUser replay = service.consume(ticket, 11L);

        assertThat(first).isNotNull();
        assertThat(first.getTenantId()).isEqualTo(11L);
        assertThat(first.getId()).isEqualTo(22L);
        assertThat(first.getUserType()).isEqualTo(UserTypeEnum.MEMBER.getValue());
        assertThat(first.getInfo()).isNull();
        assertThat(first.getScopes()).isNull();
        assertThat(replay).isNull();
        verify(valueOperations, org.mockito.Mockito.times(2)).getAndDelete(key);
    }

    @Test
    void consumeFailsClosedForCrossTenantTicketAndStillBurnsIt() {
        String ticket = validTicket((byte) 8);
        String key = RedisWebSocketTicketService.REDIS_KEY_PREFIX + sha256Hex(ticket);
        when(valueOperations.getAndDelete(key)).thenReturn("11:22:1");

        LoginUser result = service.consume(ticket, 12L);

        assertThat(result).isNull();
        verify(valueOperations).getAndDelete(key);
    }

    @Test
    void malformedOrExpiredTicketFailsClosed() {
        assertThat(service.consume("not-a-256-bit-ticket", null)).isNull();
        verify(valueOperations, never()).getAndDelete(anyString());

        String expiredTicket = validTicket((byte) 9);
        when(valueOperations.getAndDelete(anyString())).thenReturn(null);
        assertThat(service.consume(expiredTicket, null)).isNull();
    }

    @Test
    void configurationCannotExceedThirtySeconds() {
        properties.setTicketTtlSeconds(31);

        assertThatThrownBy(() -> new RedisWebSocketTicketService(
                redisTemplate, properties, new SecureRandom()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LoginUser loginUser(long tenantId, long userId, int userType) {
        return new LoginUser().setTenantId(tenantId).setId(userId).setUserType(userType);
    }

    private static String validTicket(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte part : hash) {
                hex.append(String.format("%02x", part & 0xff));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

}
