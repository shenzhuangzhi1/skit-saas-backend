package cn.iocoder.yudao.module.skit.service.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnoreAspect;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderConnectionHealthProjection;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderConnectionHealthMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.support.AopUtils;

class SkitProviderConnectionHealthServiceTest {

  private static final LocalDateTime FIRST = LocalDateTime.of(2026, 8, 3, 8, 0, 0);
  private static final LocalDateTime LAST = LocalDateTime.of(2026, 8, 3, 8, 5, 0);

  @AfterEach
  void clearTenant() {
    TenantContextHolder.clear();
  }

  @Test
  void mapsOnlyTheDedicatedSafeAggregateAndPreservesCallerTenantContext() {
    SkitProviderConnectionHealthMapper mapper =
        org.mockito.Mockito.mock(SkitProviderConnectionHealthMapper.class);
    when(mapper.selectSafeAggregateByConnectionId(77L))
        .thenReturn(
            new SkitProviderConnectionHealthProjection()
                .setFirstReceivedAt(FIRST)
                .setLastReceivedAt(LAST)
                .setAcceptedAttempts(9L)
                .setDuplicates(3L)
                .setConflicts(2L)
                .setFallback(1L)
                .setQuarantined(4L)
                .setDbFailures(null)
                .setDbFailureAt(null));
    SkitProviderConnectionHealthService service =
        new SkitProviderConnectionHealthServiceImpl(mapper);
    TenantContextHolder.setTenantId(773L);
    TenantContextHolder.setIgnore(false);

    SkitProviderConnectionHealthView health = service.getSafeHealth(77L);

    assertEquals(FIRST, health.getFirstReceivedAt());
    assertEquals(LAST, health.getLastReceivedAt());
    assertEquals(9L, health.getAcceptedAttempts());
    assertEquals(3L, health.getDuplicates());
    assertEquals(2L, health.getConflicts());
    assertEquals(1L, health.getFallback());
    assertEquals(4L, health.getQuarantined());
    assertNull(health.getDbFailures());
    assertNull(health.getDbFailureAt());
    assertEquals(773L, TenantContextHolder.getTenantId());
    assertFalse(TenantContextHolder.isIgnore());
    verify(mapper).selectSafeAggregateByConnectionId(77L);
  }

  @Test
  void emptyAggregateReturnsAZeroHealthObjectAndInvalidConnectionIdsFailClosed() {
    SkitProviderConnectionHealthMapper mapper =
        org.mockito.Mockito.mock(SkitProviderConnectionHealthMapper.class);
    when(mapper.selectSafeAggregateByConnectionId(88L)).thenReturn(null);
    SkitProviderConnectionHealthService service =
        new SkitProviderConnectionHealthServiceImpl(mapper);

    SkitProviderConnectionHealthView empty = service.getSafeHealth(88L);

    assertNull(empty.getFirstReceivedAt());
    assertNull(empty.getLastReceivedAt());
    assertEquals(0L, empty.getAcceptedAttempts());
    assertEquals(0L, empty.getDuplicates());
    assertEquals(0L, empty.getConflicts());
    assertEquals(0L, empty.getFallback());
    assertEquals(0L, empty.getQuarantined());
    assertNull(empty.getDbFailures());
    assertNull(empty.getDbFailureAt());
    assertThrows(IllegalArgumentException.class, () -> service.getSafeHealth(0));
    assertThrows(IllegalArgumentException.class, () -> service.getSafeHealth(-1));
  }

  @Test
  void tenantIgnoreServiceCanBeClassProxiedLikeTheProductionContext() {
    SkitProviderConnectionHealthMapper mapper =
        org.mockito.Mockito.mock(SkitProviderConnectionHealthMapper.class);
    AspectJProxyFactory proxyFactory =
        new AspectJProxyFactory(new SkitProviderConnectionHealthServiceImpl(mapper));
    proxyFactory.setProxyTargetClass(true);
    proxyFactory.addAspect(new TenantIgnoreAspect());

    Object proxy = proxyFactory.getProxy();

    assertTrue(AopUtils.isCglibProxy(proxy));
    assertTrue(proxy instanceof SkitProviderConnectionHealthService);
  }

  @Test
  void mapperIsGloballyGuardedAndSqlCanOnlyProduceOneBoundedAggregateRow() throws Exception {
    Method method =
        SkitProviderConnectionHealthMapper.class.getMethod(
            "selectSafeAggregateByConnectionId", long.class);
    assertEquals(true, SkitProviderConnectionHealthMapper.class.isAnnotationPresent(TenantIgnore.class));
    assertEquals(true, method.isAnnotationPresent(TenantIgnore.class));
    assertEquals(
        "true",
        SkitProviderConnectionHealthMapper.class
            .getAnnotation(InterceptorIgnore.class)
            .tenantLine());
    assertEquals("true", method.getAnnotation(InterceptorIgnore.class).tenantLine());
    Select select = method.getAnnotation(Select.class);
    assertEquals(2, method.getAnnotation(Options.class).timeout());
    String sql = String.join(" ", select.value()).toLowerCase();
    for (String forbidden :
        Arrays.asList(
            "select *",
            "dedupe_key_hash",
            "material_integrity_hash",
            "wire_payload_hash",
            "payload_ciphertext",
            "payload_nonce",
            "payload_key_id",
            "remote_address_hash",
            "user_agent_hash",
            "request_header_fingerprint",
            "provider_request_id_lexical",
            "adsource_id_lexical",
            "trace_id",
            "correlation_id",
            "lease_owner")) {
      assertFalse(sql.contains(forbidden), forbidden);
    }
    assertEquals(1, occurrences(sql, "#{connectionid}"));
    assertFalse(sql.contains(" group by "));
    assertFalse(sql.contains(" order by "));
    assertFalse(sql.contains("join skit_provider_impression_inbox"));
    assertTrue(sql.contains("from skit_provider_impression_inbox qi"));
  }

  @Test
  void healthDtoJacksonAndToStringCannotExposeCallbackSentinelsOrRowObjects() throws Exception {
    SkitProviderConnectionHealthView health =
        SkitProviderConnectionHealthView.from(
            new SkitProviderConnectionHealthProjection()
                .setFirstReceivedAt(FIRST)
                .setLastReceivedAt(LAST)
                .setAcceptedAttempts(9L)
                .setDuplicates(3L)
                .setConflicts(2L)
                .setFallback(1L)
                .setQuarantined(4L)
                .setDbFailures(null));
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    String json = mapper.writeValueAsString(health);
    String rendered = health.toString();
    Set<String> fields =
        Arrays.stream(SkitProviderConnectionHealthView.class.getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());

    assertEquals(
        setOf(
            "firstReceivedAt",
            "lastReceivedAt",
            "acceptedAttempts",
            "duplicates",
            "conflicts",
            "fallback",
            "quarantined",
            "dbFailures",
            "dbFailureAt"),
        fields);
    for (String sentinel :
        Arrays.asList(
            "acct_sentinel_callback_key",
            "sentinel-query-value",
            "203.0.113.77",
            "sentinel.package",
            "sentinel-placement",
            "sentinel-request",
            "sentinel-device",
            "payloadCiphertext",
            "inboxId",
            "attemptId")) {
      assertFalse(json.contains(sentinel), sentinel);
      assertFalse(rendered.contains(sentinel), sentinel);
    }
  }

  private static int occurrences(String value, String needle) {
    int count = 0;
    int offset = 0;
    while ((offset = value.indexOf(needle, offset)) >= 0) {
      count++;
      offset += needle.length();
    }
    return count;
  }

  private static Set<String> setOf(String... values) {
    return new HashSet<>(Arrays.asList(values));
  }
}
