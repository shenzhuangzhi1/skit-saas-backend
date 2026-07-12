package cn.iocoder.yudao.module.skit.service.member;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代理商 App 入口上下文。
 */
public interface SkitMemberAppContextService {

    AppContext issue(String agentCode);

    Long requireTenantId(String token);

    @Data
    @AllArgsConstructor
    class AppContext {
        private String token;
        private Long tenantId;
        private LocalDateTime expiresTime;
    }
}
