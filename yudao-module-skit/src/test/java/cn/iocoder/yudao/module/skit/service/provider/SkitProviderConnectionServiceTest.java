package cn.iocoder.yudao.module.skit.service.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderCallbackRouteDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdProviderConnectionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderCallbackRouteMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdProviderConnectionMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitCallbackPublicUrlService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    assertThrows(
        IllegalStateException.class,
        () -> service.issueOnce(new SkitProviderConnectionService.IssueRouteCommand(2L, 7L)));
  }

  @Test
  void rollbackCompletionDestroysAnIssuedUrlBeforeItCanBeConsumed() throws Exception {
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
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
      assertThrows(IllegalStateException.class, issued::consumeCallbackUrl);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }
}
