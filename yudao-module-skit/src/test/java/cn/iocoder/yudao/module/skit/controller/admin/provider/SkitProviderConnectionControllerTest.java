package cn.iocoder.yudao.module.skit.controller.admin.provider;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PLATFORM_ADMIN_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderCallbackRouteAbandonReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderCallbackRouteCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderCallbackRouteIssueReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderCallbackRouteIssuedRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderCallbackRouteSubmittedReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderConnectionBlockReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderConnectionCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderConnectionRespVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderConnectionHealthProjection;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderConnectionReadProjection;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.provider.SkitPlatformProviderCommandExecutor;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionHealthView;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.beans.Introspector;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

@ExtendWith(MockitoExtension.class)
class SkitProviderConnectionControllerTest {

  private static final String CALLBACK_URL =
      "https://ads.example.test/app-api/skit/ad-callback/taku/"
          + "acct_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGx/impression";

  @Mock private SkitPlatformAdminGuard platformAdminGuard;
  @Mock private SkitPlatformProviderCommandExecutor commandExecutor;

  private SkitProviderConnectionController controller;

  @BeforeEach
  void setUp() {
    controller = new SkitProviderConnectionController(platformAdminGuard, commandExecutor);
  }

  @Test
  void issueCallsPlatformGuardAndImmediateCommandWithNoStoreResponse() {
    char[] password = "current-password".toCharArray();
    SkitProviderCallbackRouteIssueReqVO request = new SkitProviderCallbackRouteIssueReqVO();
    request.setCurrentPassword(password);
    request.setReason("issue the verified provider callback route");
    SkitProviderConnectionService.IssuedRoute issued =
        mock(SkitProviderConnectionService.IssuedRoute.class);
    char[] callbackUrl = CALLBACK_URL.toCharArray();
    when(issued.getRouteId()).thenReturn(22L);
    when(issued.getFingerprint()).thenReturn("0123456789abcdef");
    when(issued.consumeCallbackUrl()).thenReturn(callbackUrl);
    doAnswer(
            invocation -> {
              char[] received = invocation.getArgument(1);
              assertTrue("current-password".contentEquals(java.nio.CharBuffer.wrap(received)));
              return issued;
            })
        .when(commandExecutor)
        .issueOnce(eq(22L), any(char[].class), eq("issue the verified provider callback route"));

    ResponseEntity<CommonResult<SkitProviderCallbackRouteIssuedRespVO>> response =
        controller.issueOnce(22L, request);

    verify(platformAdminGuard).check();
    assertEquals("no-store", response.getHeaders().getCacheControl());
    assertEquals("no-cache", response.getHeaders().getFirst("Pragma"));
    assertNotNull(response.getBody());
    assertEquals(22L, response.getBody().getData().getRouteId());
    assertCleared(callbackUrl, "issued response must clear the Task 3 source buffer after cloning");
  }

  @Test
  void issuedResponseSerializesUrlOnceThenWipesItsPrivateBuffer() throws Exception {
    char[] source = CALLBACK_URL.toCharArray();
    SkitProviderCallbackRouteIssuedRespVO response =
        new SkitProviderCallbackRouteIssuedRespVO(22L, "ISSUED", "0123456789abcdef", source);
    assertCleared(source, "constructor must clear its caller-owned source");
    assertFalse(response.toString().contains("acct_"));
    assertFalse(response.toString().contains("https://"));
    assertFalse(
        Arrays.stream(Introspector.getBeanInfo(response.getClass()).getPropertyDescriptors())
            .anyMatch(property -> property.getName().toLowerCase().contains("url")));
    Field secret = response.getClass().getDeclaredField("callbackUrl");
    secret.setAccessible(true);
    char[] retained = (char[]) secret.get(response);
    assertFalse(isCleared(retained));

    ObjectMapper mapper = new ObjectMapper();
    String firstJson = mapper.writeValueAsString(response);
    JsonNode first = mapper.readTree(firstJson);
    assertEquals(CALLBACK_URL, first.path("callbackUrl").asText());
    assertCleared(retained, "serializer must wipe the private URL in finally");
    assertThrows(Exception.class, () -> mapper.writeValueAsString(response));
  }

  @Test
  void getResponseAddsOnlyTheNineFieldSafeAggregateHealthObject() throws Exception {
    SkitProviderConnectionReadProjection connection =
        new SkitProviderConnectionReadProjection()
            .setConnectionId(11L)
            .setProvider("TAKU")
            .setAccountMode("SHARED_MASTER")
            .setConnectionState("CONFIGURING");
    SkitProviderConnectionHealthView health =
        SkitProviderConnectionHealthView.from(
            new SkitProviderConnectionHealthProjection()
                .setFirstReceivedAt(LocalDateTime.of(2026, 8, 3, 7, 0, 0))
                .setLastReceivedAt(LocalDateTime.of(2026, 8, 3, 7, 5, 0))
                .setAcceptedAttempts(8L)
                .setDuplicates(2L)
                .setConflicts(1L)
                .setFallback(3L)
                .setQuarantined(4L)
                .setDbFailures(null));
    Constructor<SkitPlatformProviderCommandExecutor.ResourceView> constructor =
        SkitPlatformProviderCommandExecutor.ResourceView.class.getDeclaredConstructor(
            SkitProviderConnectionReadProjection.class, SkitProviderConnectionHealthView.class);
    constructor.setAccessible(true);
    SkitProviderConnectionRespVO response =
        SkitProviderConnectionRespVO.from(constructor.newInstance(connection, health));

    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    String json = mapper.writeValueAsString(response);
    JsonNode healthJson = mapper.readTree(json).path("health");
    Set<String> healthFields = new HashSet<>();
    healthJson.fieldNames().forEachRemaining(healthFields::add);

    assertEquals(
        new HashSet<>(Arrays.asList(
            "firstReceivedAt",
            "lastReceivedAt",
            "acceptedAttempts",
            "duplicates",
            "conflicts",
            "fallback",
            "quarantined",
            "dbFailures",
            "dbFailureAt")),
        healthFields);
    assertEquals(8L, healthJson.path("acceptedAttempts").asLong());
    for (String forbidden :
        Arrays.asList(
            "acct_sentinel_callback_key",
            "sentinel-query",
            "203.0.113.77",
            "sentinel.package",
            "sentinel-placement",
            "sentinel-request",
            "sentinel-device",
            "payloadCiphertext",
            "inboxId",
            "attemptId",
            "providerConnectionId")) {
      assertFalse(json.contains(forbidden), forbidden);
    }
    assertEquals(
        new HashSet<>(Arrays.asList(
            "firstReceivedAt",
            "lastReceivedAt",
            "acceptedAttempts",
            "duplicates",
            "conflicts",
            "fallback",
            "quarantined",
            "dbFailures",
            "dbFailureAt")),
        Arrays.stream(SkitProviderConnectionRespVO.ProviderHealthRespVO.class.getDeclaredFields())
            .map(Field::getName)
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void platformGuardDenialStopsBeforeCommandAndConsumesRequestSecret() {
    SkitProviderCallbackRouteIssueReqVO request = new SkitProviderCallbackRouteIssueReqVO();
    request.setCurrentPassword("current-password".toCharArray());
    request.setReason("issue the verified provider callback route");
    doThrow(exception(PLATFORM_ADMIN_REQUIRED)).when(platformAdminGuard).check();

    ServiceException denial =
        assertThrows(ServiceException.class, () -> controller.issueOnce(22L, request));

    assertEquals(PLATFORM_ADMIN_REQUIRED.getCode(), denial.getCode());
    verifyNoInteractions(commandExecutor);
    assertThrows(IllegalStateException.class, request::consumeCurrentPassword);
  }

  @Test
  void allSevenEndpointsCarryClosedSuperAdminAndLoggingContracts() {
    int endpointCount = 0;
    int postCount = 0;
    for (Method method : SkitProviderConnectionController.class.getDeclaredMethods()) {
      boolean get = method.isAnnotationPresent(GetMapping.class);
      boolean post = method.isAnnotationPresent(PostMapping.class);
      if (!get && !post) {
        continue;
      }
      endpointCount++;
      PreAuthorize authorize = method.getAnnotation(PreAuthorize.class);
      assertNotNull(authorize, method.getName());
      assertEquals("@ss.hasRole('super_admin')", authorize.value(), method.getName());
      if (post) {
        postCount++;
        ApiAccessLog accessLog = method.getAnnotation(ApiAccessLog.class);
        assertNotNull(accessLog, method.getName());
        assertFalse(accessLog.requestEnable(), method.getName());
        assertFalse(accessLog.responseEnable(), method.getName());
      }
    }
    assertEquals(7, endpointCount);
    assertEquals(6, postCount);
  }

  @Test
  void everyRemainingEndpointCallsGuardBeforeItsExecutorMethod() {
    String reason = "perform verified platform provider lifecycle command";
    IllegalStateException stop = new IllegalStateException("stop after guard");
    doThrow(stop).when(commandExecutor).getConnection(11L);
    doThrow(stop)
        .when(commandExecutor)
        .createSharedMaster(any(char[].class), any(char[].class), eq(reason));
    doThrow(stop)
        .when(commandExecutor)
        .createDraftRoute(
            eq(11L),
            eq(SkitProviderConnectionService.RoutePurpose.GATE_TEST),
            any(char[].class),
            eq(reason));
    doThrow(stop)
        .when(commandExecutor)
        .abandonNeverShared(
            eq(22L),
            any(char[].class),
            eq(SkitPlatformProviderCommandExecutor.NEVER_SHARED_DECLARATION));
    doThrow(stop)
        .when(commandExecutor)
        .markSubmitted(
            eq(22L),
            any(char[].class),
            eq("ticket-safe"),
            eq("reference-safe"),
            eq("recipient-safe"),
            eq(reason));
    doThrow(stop).when(commandExecutor).block(eq(11L), any(char[].class), eq(reason));

    assertThrows(IllegalStateException.class, () -> controller.getConnection(11L));

    SkitProviderConnectionCreateReqVO create = new SkitProviderConnectionCreateReqVO();
    create.setCurrentPassword("current-password".toCharArray());
    create.setExternalAccountReference("external-reference".toCharArray());
    create.setReason(reason);
    assertThrows(IllegalStateException.class, () -> controller.createSharedMaster(create));

    SkitProviderCallbackRouteCreateReqVO draft = new SkitProviderCallbackRouteCreateReqVO();
    draft.setCurrentPassword("current-password".toCharArray());
    draft.setPurpose(SkitProviderConnectionService.RoutePurpose.GATE_TEST);
    draft.setReason(reason);
    assertThrows(IllegalStateException.class, () -> controller.createDraftRoute(11L, draft));

    SkitProviderCallbackRouteAbandonReqVO abandon = new SkitProviderCallbackRouteAbandonReqVO();
    abandon.setCurrentPassword("current-password".toCharArray());
    abandon.setNeverSharedDeclaration(SkitPlatformProviderCommandExecutor.NEVER_SHARED_DECLARATION);
    assertThrows(IllegalStateException.class, () -> controller.abandonNeverShared(22L, abandon));

    SkitProviderCallbackRouteSubmittedReqVO submitted =
        new SkitProviderCallbackRouteSubmittedReqVO();
    submitted.setCurrentPassword("current-password".toCharArray());
    submitted.setTicket("ticket-safe");
    submitted.setReference("reference-safe");
    submitted.setRecipient("recipient-safe");
    submitted.setReason(reason);
    assertThrows(IllegalStateException.class, () -> controller.markSubmitted(22L, submitted));

    SkitProviderConnectionBlockReqVO block = new SkitProviderConnectionBlockReqVO();
    block.setCurrentPassword("current-password".toCharArray());
    block.setReason(reason);
    assertThrows(IllegalStateException.class, () -> controller.block(11L, block));

    InOrder order = inOrder(platformAdminGuard, commandExecutor);
    order.verify(platformAdminGuard).check();
    order.verify(commandExecutor).getConnection(11L);
    order.verify(platformAdminGuard).check();
    order
        .verify(commandExecutor)
        .createSharedMaster(any(char[].class), any(char[].class), eq(reason));
    order.verify(platformAdminGuard).check();
    order
        .verify(commandExecutor)
        .createDraftRoute(
            eq(11L),
            eq(SkitProviderConnectionService.RoutePurpose.GATE_TEST),
            any(char[].class),
            eq(reason));
    order.verify(platformAdminGuard).check();
    order
        .verify(commandExecutor)
        .abandonNeverShared(
            eq(22L),
            any(char[].class),
            eq(SkitPlatformProviderCommandExecutor.NEVER_SHARED_DECLARATION));
    order.verify(platformAdminGuard).check();
    order
        .verify(commandExecutor)
        .markSubmitted(
            eq(22L),
            any(char[].class),
            eq("ticket-safe"),
            eq("reference-safe"),
            eq("recipient-safe"),
            eq(reason));
    order.verify(platformAdminGuard).check();
    order.verify(commandExecutor).block(eq(11L), any(char[].class), eq(reason));
  }

  private static void assertCleared(char[] value, String message) {
    assertTrue(value != null && value.length > 0, message);
    for (char character : value) {
      assertEquals('\0', character, message);
    }
  }

  private static boolean isCleared(char[] value) {
    if (value == null) {
      return true;
    }
    for (char character : value) {
      if (character != '\0') {
        return false;
      }
    }
    return true;
  }
}
