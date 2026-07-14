package cn.iocoder.yudao.module.skit.service.ad;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

@Component
public final class SkitAdSessionCreateTransactionExecutor {

    private final TransactionTemplate transactionTemplate;

    public SkitAdSessionCreateTransactionExecutor(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public <T> T execute(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return transactionTemplate.execute(status -> operation.get());
    }

}
