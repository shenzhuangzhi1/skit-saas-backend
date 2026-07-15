package cn.iocoder.yudao.framework.websocket.core.security;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.websocket.config.WebSocketProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Redis implementation that never persists the raw bearer ticket. */
public class RedisWebSocketTicketService implements WebSocketTicketService {

    public static final String REDIS_KEY_PREFIX = "yudao:websocket:ticket:";

    private static final int TICKET_BYTES = 32;
    private static final int ISSUE_ATTEMPTS = 3;
    private static final Pattern TICKET_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final ValueOperations<String, String> valueOperations;
    private final int ttlSeconds;
    private final SecureRandom secureRandom;

    public RedisWebSocketTicketService(StringRedisTemplate redisTemplate, WebSocketProperties properties) {
        this(redisTemplate, properties, new SecureRandom());
    }

    RedisWebSocketTicketService(StringRedisTemplate redisTemplate, WebSocketProperties properties,
                                SecureRandom secureRandom) {
        Objects.requireNonNull(redisTemplate, "redisTemplate");
        Objects.requireNonNull(properties, "properties");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.ttlSeconds = properties.getTicketTtlSeconds();
        if (ttlSeconds < 1 || ttlSeconds > 30) {
            throw new IllegalArgumentException("WebSocket ticket TTL must be between 1 and 30 seconds");
        }
        this.valueOperations = redisTemplate.opsForValue();
    }

    @Override
    public WebSocketTicket issue(LoginUser loginUser) {
        requirePrincipal(loginUser);
        String storedPrincipal = serializePrincipal(loginUser);
        for (int attempt = 0; attempt < ISSUE_ATTEMPTS; attempt++) {
            byte[] randomBytes = new byte[TICKET_BYTES];
            secureRandom.nextBytes(randomBytes);
            String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            Boolean stored = valueOperations.setIfAbsent(redisKey(ticket), storedPrincipal,
                    ttlSeconds, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(stored)) {
                return new WebSocketTicket(ticket, ttlSeconds);
            }
        }
        throw new IllegalStateException("Unable to issue a unique WebSocket ticket");
    }

    @Override
    @Nullable
    public LoginUser consume(String ticket, @Nullable Long requestedTenantId) {
        if (!isValidTicket(ticket)) {
            return null;
        }
        // ValueOperations#getAndDelete is a single Redis GETDEL operation, so only one handshake wins.
        String storedPrincipal = valueOperations.getAndDelete(redisKey(ticket));
        LoginUser loginUser = parsePrincipal(storedPrincipal);
        if (loginUser == null) {
            return null;
        }
        if (requestedTenantId != null && !requestedTenantId.equals(loginUser.getTenantId())) {
            return null;
        }
        return loginUser;
    }

    private static void requirePrincipal(LoginUser loginUser) {
        if (loginUser == null || loginUser.getTenantId() == null || loginUser.getTenantId() <= 0
                || loginUser.getId() == null || loginUser.getId() <= 0
                || loginUser.getUserType() == null || loginUser.getUserType() <= 0) {
            throw new IllegalArgumentException("A complete tenant-bound principal is required");
        }
    }

    private static String serializePrincipal(LoginUser loginUser) {
        return loginUser.getTenantId() + ":" + loginUser.getId() + ":" + loginUser.getUserType();
    }

    @Nullable
    private static LoginUser parsePrincipal(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            long tenantId = Long.parseLong(parts[0]);
            long userId = Long.parseLong(parts[1]);
            int userType = Integer.parseInt(parts[2]);
            if (tenantId <= 0 || userId <= 0 || userType <= 0) {
                return null;
            }
            return new LoginUser().setTenantId(tenantId).setId(userId).setUserType(userType);
        } catch (NumberFormatException invalidPrincipal) {
            return null;
        }
    }

    private static boolean isValidTicket(String ticket) {
        if (ticket == null || !TICKET_PATTERN.matcher(ticket).matches()) {
            return false;
        }
        try {
            return Base64.getUrlDecoder().decode(ticket).length == TICKET_BYTES;
        } catch (IllegalArgumentException invalidBase64) {
            return false;
        }
    }

    private static String redisKey(String ticket) {
        return REDIS_KEY_PREFIX + sha256Hex(ticket);
    }

    private static String sha256Hex(String ticket) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte part : hash) {
                hex.append(Character.forDigit((part >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(part & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

}
