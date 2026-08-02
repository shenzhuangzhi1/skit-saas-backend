package cn.iocoder.yudao.module.skit.service.provider;

import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PROVIDER_COMMAND_INVALID;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderCallbackRouteDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderConnectionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitPlatformProviderCommandAuditDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderConnectionReadProjection;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderCallbackRouteMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderConnectionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitPlatformProviderCommandAuditMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderConnectionReadMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SkitPlatformProviderCommandExecutorTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-03T04:05:06Z"), ZoneOffset.UTC);

  @Mock private SkitProviderConnectionService connectionService;
  @Mock private SkitCurrentAdminPasswordReauthService reauthService;
  @Mock private SkitAdProviderConnectionMapper connectionMapper;
  @Mock private SkitAdProviderCallbackRouteMapper routeMapper;
  @Mock private SkitPlatformProviderCommandAuditMapper auditMapper;
  @Mock private SkitProviderConnectionReadMapper readMapper;

  private SkitPlatformProviderCommandExecutor executor;

  @BeforeEach
  void setUp() {
    authenticate(7L, 1L, 42L);
    executor =
        new SkitPlatformProviderCommandExecutor(
            connectionService,
            reauthService,
            connectionMapper,
            routeMapper,
            auditMapper,
            readMapper,
            FIXED_CLOCK,
            () -> "trace_task6_0001");
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
    TenantContextHolder.clear();
  }

  @Test
  void issueReauthenticatesThenMutatesAndAppendsOnlySafeAuditForOriginalTenant() {
    char[] password = "current-password".toCharArray();
    SkitAdProviderCallbackRouteDO beforeRoute = route(22L, "DRAFT", null);
    SkitAdProviderCallbackRouteDO afterRoute = route(22L, "ISSUED", 91L);
    SkitAdProviderConnectionDO beforeConnection = connection("CONFIGURING");
    SkitAdProviderConnectionDO afterConnection = connection("CONFIGURING");
    SkitProviderConnectionService.IssuedRoute issued =
        org.mockito.Mockito.mock(SkitProviderConnectionService.IssuedRoute.class);
    when(routeMapper.selectById(22L)).thenReturn(beforeRoute, afterRoute);
    when(connectionMapper.selectById(11L)).thenReturn(beforeConnection, afterConnection);
    when(connectionService.issueOnce(any())).thenReturn(issued);
    when(auditMapper.insert(any())).thenReturn(1);
    TenantContextHolder.setTenantId(773L);
    TenantContextHolder.setIgnore(true);

    assertEquals(
        issued,
        executor.issueOnce(22L, password, "issue provider callback route after verification"));

    assertCleared(password);
    assertEquals(773L, TenantContextHolder.getTenantId());
    assertTrue(TenantContextHolder.isIgnore());
    InOrder order = inOrder(reauthService, routeMapper, connectionService, auditMapper);
    order.verify(reauthService).verifyCurrentUserPassword(any(char[].class));
    order.verify(routeMapper).selectById(22L);
    order.verify(connectionService).issueOnce(any());
    order.verify(routeMapper).selectById(22L);
    order.verify(auditMapper).insert(any());

    ArgumentCaptor<SkitPlatformProviderCommandAuditDO> audit =
        ArgumentCaptor.forClass(SkitPlatformProviderCommandAuditDO.class);
    verify(auditMapper).insert(audit.capture());
    SkitPlatformProviderCommandAuditDO row = audit.getValue();
    assertEquals(7L, row.getActorUserId());
    assertEquals(1L, row.getOriginalLoginTenantId());
    assertEquals("ISSUE_ROUTE_ONCE", row.getAction());
    assertEquals(11L, row.getProviderConnectionId());
    assertEquals(22L, row.getProviderCallbackRouteId());
    assertEquals(91L, row.getCallbackRouteRegistryId());
    assertEquals(LocalDateTime.of(2026, 8, 3, 4, 5, 6), row.getReauthenticatedAt());
    assertEquals("SUCCEEDED", row.getResultStatus());
    assertEquals("OK", row.getResultCode());
    assertEquals(32, row.getRequestFingerprint().length);
    assertEquals(32, row.getBeforeStateHash().length);
    assertEquals(32, row.getAfterStateHash().length);
    assertFalse(Arrays.equals(row.getBeforeStateHash(), row.getAfterStateHash()));
    assertFalse(row.toString().contains("current-password"));
    assertFalse(row.toString().contains("callbackUrl"));
    assertFalse(row.toString().contains("acct_"));
  }

  @Test
  void dangerousAndLogInjectionTextRejectAfterReauthBeforeLifecycleAndClearPassword() {
    for (String unsafe :
        Arrays.asList(
            "submitted at HTTP://provider.example",
            "reference contains AcCt_secret",
            "token AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA provided",
            "ten chars\rforged audit",
            "ten chars\u2028forged audit",
            "ten chars\u2029forged audit")) {
      char[] password = "current-password".toCharArray();
      ServiceException failure =
          assertThrows(
              ServiceException.class,
              () ->
                  executor.markSubmitted(
                      22L, password, "ticket-safe", "reference-safe", "recipient-safe", unsafe));
      assertEquals(PROVIDER_COMMAND_INVALID.getCode(), failure.getCode());
      assertCleared(password);
    }
    verify(reauthService, org.mockito.Mockito.times(6))
        .verifyCurrentUserPassword(any(char[].class));
    verifyNoInteractions(connectionService, connectionMapper, routeMapper, auditMapper);
  }

  @Test
  void exactAuditInsertCountIsMandatory() {
    char[] password = "current-password".toCharArray();
    when(routeMapper.selectById(22L))
        .thenReturn(route(22L, "DRAFT", null), route(22L, "ISSUED", 91L));
    when(connectionMapper.selectById(11L))
        .thenReturn(connection("CONFIGURING"), connection("CONFIGURING"));
    when(connectionService.issueOnce(any()))
        .thenReturn(org.mockito.Mockito.mock(SkitProviderConnectionService.IssuedRoute.class));
    when(auditMapper.insert(any())).thenReturn(0);

    assertThrows(
        IllegalStateException.class,
        () ->
            executor.issueOnce(22L, password, "issue provider callback route after verification"));

    assertCleared(password);
    verify(auditMapper).insert(any());
  }

  @Test
  void getUsesOnlyTheExplicitSafeProjection() {
    SkitProviderConnectionReadProjection projection =
        new SkitProviderConnectionReadProjection()
            .setConnectionId(11L)
            .setProvider("TAKU")
            .setAccountMode("SHARED_MASTER")
            .setConnectionState("CONFIGURING");
    when(readMapper.selectSafeByConnectionId(11L)).thenReturn(projection);

    SkitPlatformProviderCommandExecutor.ResourceView view = executor.getConnection(11L);

    assertEquals(11L, view.getConnectionId());
    assertEquals("TAKU", view.getProvider());
    verify(readMapper).selectSafeByConnectionId(11L);
    verifyNoInteractions(connectionMapper, routeMapper);
  }

  @Test
  void readProjectionSqlHasAClosedColumnAllowlist() throws Exception {
    Method method =
        SkitProviderConnectionReadMapper.class.getDeclaredMethod(
            "selectSafeByConnectionId", long.class);
    Select select = method.getAnnotation(Select.class);
    assertNotNull(select);
    String sql = String.join(" ", select.value()).toLowerCase();
    for (String forbidden :
        Arrays.asList(
            "select *",
            "connection_code",
            "external_account_ref_hash",
            "callback_origin_fingerprint",
            "callback_contract_fingerprint",
            "submission_ticket",
            "submission_reference",
            "submission_recipient",
            "created_by_user_id",
            "updated_by_user_id")) {
      assertFalse(sql.contains(forbidden), forbidden);
    }
    for (String required :
        Arrays.asList(
            "c.id as connection_id",
            "c.provider",
            "c.account_mode",
            "r.callback_key_fingerprint")) {
      assertTrue(sql.contains(required), required);
    }
  }

  @Test
  void auditMapperIsGlobalAndAppendOnlyByConstruction() throws Exception {
    assertTrue(
        SkitPlatformProviderCommandAuditMapper.class.isAnnotationPresent(TenantIgnore.class));
    InterceptorIgnore typeIgnore =
        SkitPlatformProviderCommandAuditMapper.class.getAnnotation(InterceptorIgnore.class);
    assertNotNull(typeIgnore);
    assertEquals("true", typeIgnore.tenantLine());
    assertEquals(1, SkitPlatformProviderCommandAuditMapper.class.getDeclaredMethods().length);
    Method insert =
        SkitPlatformProviderCommandAuditMapper.class.getDeclaredMethod(
            "insert", SkitPlatformProviderCommandAuditDO.class);
    assertTrue(insert.isAnnotationPresent(TenantIgnore.class));
    assertNotNull(insert.getAnnotation(InterceptorIgnore.class));
    assertEquals("insert", insert.getName());
    assertTrue(SkitPlatformProviderCommandAuditDO.class.isAnnotationPresent(TenantIgnore.class));
  }

  private static SkitAdProviderConnectionDO connection(String state) {
    return new SkitAdProviderConnectionDO()
        .setId(11L)
        .setProvider("TAKU")
        .setAccountMode("SHARED_MASTER")
        .setState(state)
        .setCreatedAt(LocalDateTime.of(2026, 8, 1, 1, 2))
        .setUpdatedAt(LocalDateTime.of(2026, 8, 2, 2, 3));
  }

  private static SkitAdProviderCallbackRouteDO route(long id, String state, Long registryId) {
    return new SkitAdProviderCallbackRouteDO()
        .setId(id)
        .setProviderConnectionId(11L)
        .setRouteVersion(1)
        .setCallbackRouteRegistryId(registryId)
        .setCallbackKeyFingerprint(registryId == null ? null : "0123456789abcdef")
        .setPurpose("GATE_TEST")
        .setState(state)
        .setRouteSlot(registryId == null ? "INACTIVE" : "PRIMARY_ACCEPTING")
        .setUpdatedAt(LocalDateTime.of(2026, 8, 2, 2, 3));
  }

  private static void authenticate(long userId, long tenantId, long visitTenantId) {
    LoginUser loginUser =
        new LoginUser().setId(userId).setTenantId(tenantId).setVisitTenantId(visitTenantId);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
  }

  private static void assertCleared(char[] value) {
    char[] expected = new char[value.length];
    assertArrayEquals(expected, value);
  }
}
