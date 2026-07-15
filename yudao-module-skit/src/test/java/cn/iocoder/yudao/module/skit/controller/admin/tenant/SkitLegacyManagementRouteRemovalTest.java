package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionService;
import cn.iocoder.yudao.module.skit.service.revenue.SkitRevenueService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SkitLegacyManagementRouteRemovalTest {

    @Test
    void unauditedCommissionPublishAndLegacyLedgerRoutesCannotReappear() {
        Set<String> gets = getMappings();
        Set<String> puts = putMappings();

        assertFalse(gets.contains("/commission-rules"));
        assertFalse(puts.contains("/commission-rules"));
        assertFalse(gets.contains("/ledger/page"));
        assertFalse(hasFieldOfType(SkitCommissionService.class));
        assertFalse(hasFieldOfType(SkitRevenueService.class));
    }

    private Set<String> getMappings() {
        Set<String> result = new HashSet<>();
        for (Method method : SkitTenantBusinessController.class.getDeclaredMethods()) {
            GetMapping mapping = method.getAnnotation(GetMapping.class);
            if (mapping != null) result.addAll(Arrays.asList(mapping.value()));
        }
        return result;
    }

    private Set<String> putMappings() {
        Set<String> result = new HashSet<>();
        for (Method method : SkitTenantBusinessController.class.getDeclaredMethods()) {
            PutMapping mapping = method.getAnnotation(PutMapping.class);
            if (mapping != null) result.addAll(Arrays.asList(mapping.value()));
        }
        return result;
    }

    private boolean hasFieldOfType(Class<?> type) {
        for (Field field : SkitTenantBusinessController.class.getDeclaredFields()) {
            if (type.isAssignableFrom(field.getType())) return true;
        }
        return false;
    }
}
