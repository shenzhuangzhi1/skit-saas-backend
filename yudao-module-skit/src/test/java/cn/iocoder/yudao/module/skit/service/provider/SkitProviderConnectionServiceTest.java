package cn.iocoder.yudao.module.skit.service.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderCallbackRouteDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderConnectionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderCallbackRouteMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderConnectionMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitCallbackPublicUrlService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SkitProviderConnectionServiceTest {

  @Test
  void providerAccountKeyEncodesTheFirst228EntropyBitsInOrder() {
    byte[] entropy = new byte[29];
    for (byte value = 0; value < entropy.length; value++) {
      entropy[value] = value;
    }

    char[] key = SkitProviderConnectionServiceImpl.callbackKeyFromFirst228Bits(entropy);
    try {
      assertEquals("acct_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGx", new String(key));
    } finally {
      Arrays.fill(entropy, (byte) 0);
      Arrays.fill(key, '\0');
    }
  }

  @Test
  void productionGateDefaultsToDeny() {
    SkitProviderImpressionProductionGate gate = new DefaultSkitProviderImpressionProductionGate();
    assertThrows(IllegalStateException.class, () -> gate.assertProductionIssueAllowed(1L, 2L, 3L));
  }

  @Test
  void consumedAccountReferenceCannotBeReadOrReusedAfterValidationFailure() {
    SkitProviderConnectionService.CreateSharedMasterCommand command =
        new SkitProviderConnectionService.CreateSharedMasterCommand(new char[0], 7L);
    assertEquals(0, command.consumeExternalAccountReference().length);
    assertThrows(IllegalStateException.class, command::consumeExternalAccountReference);
  }

  @Test
  void externalAccountReferencesUseUtf8RatherThanLowByteTruncation() {
    byte[] nul =
        SkitProviderConnectionServiceImpl.externalAccountReferenceHash(new char[] {'\u0000'});
    byte[] macron =
        SkitProviderConnectionServiceImpl.externalAccountReferenceHash(new char[] {'\u0100'});
    try {
      assertFalse(Arrays.equals(nul, macron));
    } finally {
      Arrays.fill(nul, (byte) 0);
      Arrays.fill(macron, (byte) 0);
    }
  }

  @Test
  void rejectedSharedMasterCommandsConsumeTheirReferencesBeforeValidation() {
    SkitProviderConnectionServiceImpl service =
        new SkitProviderConnectionServiceImpl(
            mock(SkitAdProviderConnectionMapper.class),
            mock(SkitAdProviderCallbackRouteMapper.class),
            mock(SkitCallbackRouteRegistryService.class),
            new SkitCallbackPublicUrlService("https://callback.example.test/app-api"),
            new DefaultSkitProviderImpressionProductionGate());
    SkitProviderConnectionService.CreateSharedMasterCommand invalidActor =
        new SkitProviderConnectionService.CreateSharedMasterCommand("account".toCharArray(), 0L);
    SkitProviderConnectionService.CreateSharedMasterCommand malformedReference =
        new SkitProviderConnectionService.CreateSharedMasterCommand(new char[] {'\uD800'}, 7L);

    assertThrows(IllegalArgumentException.class, () -> service.createSharedMaster(invalidActor));
    assertThrows(IllegalStateException.class, invalidActor::consumeExternalAccountReference);
    assertThrows(
        IllegalArgumentException.class, () -> service.createSharedMaster(malformedReference));
    assertThrows(IllegalStateException.class, malformedReference::consumeExternalAccountReference);
  }

  @Test
  void issueWithoutTransactionSynchronizationFailsClosedBeforeReturningUrl() {
    SkitProviderConnectionServiceImpl service = issuableService();

    assertThrows(
        IllegalStateException.class,
        () -> service.issueOnce(new SkitProviderConnectionService.IssueRouteCommand(2L, 7L)));
  }

  @Test
  void precommitConsumptionIsRejectedAndRollbackDestroysTheRetainedUrl() throws Exception {
    SkitProviderConnectionServiceImpl service = issuableService();

    TransactionSynchronizationManager.initSynchronization();
    try {
      SkitProviderConnectionService.IssuedRoute issued =
          service.issueOnce(new SkitProviderConnectionService.IssueRouteCommand(2L, 7L));
      assertEquals(2L, issued.getRouteId());
      assertFalse(issued.toString().contains("acct_"));
      assertFalse(new ObjectMapper().writeValueAsString(issued).contains("acct_"));
      assertFalse(
          Arrays.stream(issued.getClass().getMethods())
              .anyMatch(
                  method ->
                      method.getName().startsWith("get")
                          && method.getName().toLowerCase().contains("url")));
      assertThrows(IllegalStateException.class, issued::consumeCallbackUrl);
      char[] retainedUrl = retainedUrlBuffer(issued);
      assertFalse(isCleared(retainedUrl));

      completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

      assertTrue(isCleared(retainedUrl));
      assertThrows(IllegalStateException.class, issued::consumeCallbackUrl);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void commitCompletionAllowsExactlyOneCallbackUrlConsumption() {
    SkitProviderConnectionServiceImpl service = issuableService();

    TransactionSynchronizationManager.initSynchronization();
    try {
      SkitProviderConnectionService.IssuedRoute issued =
          service.issueOnce(new SkitProviderConnectionService.IssueRouteCommand(2L, 7L));
      assertThrows(IllegalStateException.class, issued::consumeCallbackUrl);

      completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

      char[] url = issued.consumeCallbackUrl();
      try {
        assertFalse(isCleared(url));
      } finally {
        Arrays.fill(url, '\0');
      }
      assertThrows(IllegalStateException.class, issued::consumeCallbackUrl);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void markSubmittedRechecksTheProductionGateBeforeItsDatabaseMutation() {
    SkitAdProviderConnectionMapper connectionMapper = mock(SkitAdProviderConnectionMapper.class);
    SkitAdProviderCallbackRouteMapper routeMapper = mock(SkitAdProviderCallbackRouteMapper.class);
    SkitAdProviderConnectionDO connection =
        new SkitAdProviderConnectionDO().setId(1L).setState("CONFIGURING");
    SkitAdProviderCallbackRouteDO route =
        new SkitAdProviderCallbackRouteDO()
            .setId(2L)
            .setProviderConnectionId(1L)
            .setState("ISSUED")
            .setPurpose(SkitProviderConnectionService.RoutePurpose.PRODUCTION.name());
    when(routeMapper.selectById(2L)).thenReturn(route);
    when(routeMapper.selectByIdForUpdate(2L)).thenReturn(route);
    when(connectionMapper.selectByIdForUpdate(1L)).thenReturn(connection);
    SkitProviderImpressionProductionGate expiredGate =
        (connectionId, routeId, actorId) -> {
          throw new IllegalStateException("Production provider callback issuance is gated");
        };
    SkitProviderConnectionServiceImpl service =
        new SkitProviderConnectionServiceImpl(
            connectionMapper,
            routeMapper,
            mock(SkitCallbackRouteRegistryService.class),
            new SkitCallbackPublicUrlService("https://callback.example.test/app-api"),
            expiredGate);

    assertThrows(
        IllegalStateException.class,
        () ->
            service.markSubmitted(
                new SkitProviderConnectionService.MarkSubmittedCommand(
                    2L, 7L, "AM-42", "provider-reference", "taku-am@example.test")));

    verify(routeMapper, never())
        .submitCas(
            anyLong(),
            any(String.class),
            any(String.class),
            any(String.class),
            anyLong(),
            any(java.time.LocalDateTime.class));
  }

  private static SkitProviderConnectionServiceImpl issuableService() {
    SkitAdProviderConnectionMapper connectionMapper = mock(SkitAdProviderConnectionMapper.class);
    SkitAdProviderCallbackRouteMapper routeMapper = mock(SkitAdProviderCallbackRouteMapper.class);
    SkitCallbackRouteRegistryService registryService = mock(SkitCallbackRouteRegistryService.class);
    SkitAdProviderConnectionDO connection =
        new SkitAdProviderConnectionDO().setId(1L).setState("CONFIGURING");
    SkitAdProviderCallbackRouteDO route =
        new SkitAdProviderCallbackRouteDO()
            .setId(2L)
            .setProviderConnectionId(1L)
            .setState("DRAFT")
            .setPurpose(SkitProviderConnectionService.RoutePurpose.GATE_TEST.name());
    when(routeMapper.selectById(2L)).thenReturn(route);
    when(routeMapper.selectByIdForUpdate(2L)).thenReturn(route);
    when(connectionMapper.selectByIdForUpdate(1L)).thenReturn(connection);
    when(registryService.registerProviderRoute(any())).thenReturn(3L);
    when(routeMapper.issueCas(
            anyLong(),
            anyLong(),
            any(String.class),
            any(String.class),
            anyInt(),
            anyInt(),
            any(byte[].class),
            any(byte[].class),
            anyLong(),
            any(java.time.LocalDateTime.class)))
        .thenReturn(1);
    SkitProviderConnectionServiceImpl service =
        new SkitProviderConnectionServiceImpl(
            connectionMapper,
            routeMapper,
            registryService,
            new SkitCallbackPublicUrlService("https://callback.example.test/app-api"),
            new DefaultSkitProviderImpressionProductionGate());
    return service;
  }

  private static void completeTransaction(int status) {
    if (status == TransactionSynchronization.STATUS_COMMITTED) {
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
    }
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(synchronization -> synchronization.afterCompletion(status));
  }

  private static char[] retainedUrlBuffer(SkitProviderConnectionService.IssuedRoute issued)
      throws ReflectiveOperationException {
    Field url = issued.getClass().getDeclaredField("url");
    url.setAccessible(true);
    return (char[]) url.get(issued);
  }

  private static boolean isCleared(char[] value) {
    for (char character : value) {
      if (character != '\0') {
        return false;
      }
    }
    return true;
  }
}
