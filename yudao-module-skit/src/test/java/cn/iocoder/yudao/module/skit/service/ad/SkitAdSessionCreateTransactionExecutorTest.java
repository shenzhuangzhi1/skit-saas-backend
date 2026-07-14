package cn.iocoder.yudao.module.skit.service.ad;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitAdSessionCreateTransactionExecutorTest {

    @Test
    void everyAttemptUsesAReadCommittedRequiresNewTransaction() throws Exception {
        Class<?> type = Class.forName(
                "cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionCreateTransactionExecutor");
        Constructor<?> constructor = type.getDeclaredConstructor(PlatformTransactionManager.class);
        constructor.setAccessible(true);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        Object executor = constructor.newInstance(transactionManager);
        Method execute = type.getDeclaredMethod("execute", Supplier.class);
        execute.setAccessible(true);
        Object expected = new Object();

        Object actual = execute.invoke(executor, (Supplier<Object>) () -> expected);

        assertSame(expected, actual);
        ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definition.getValue().getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                definition.getValue().getIsolationLevel());
        verify(transactionManager).commit(transactionStatus);
    }

}
