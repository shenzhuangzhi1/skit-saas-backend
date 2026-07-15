package cn.iocoder.yudao.module.skit.service.invite;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.invite.SkitInviteCodeRegistryDO;
import cn.iocoder.yudao.module.skit.dal.mysql.invite.SkitInviteCodeRegistryMapper;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.INVITE_CODE_EXISTS;

@Service
public class SkitInviteCodeRegistryServiceImpl implements SkitInviteCodeRegistryService {

    private static final String ACTIVE = "ACTIVE";
    private static final int MAX_CODE_LENGTH = 32;

    private final SkitInviteCodeRegistryMapper mapper;

    public SkitInviteCodeRegistryServiceImpl(SkitInviteCodeRegistryMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public ResolvedOwner resolveActive(String code) {
        String normalizedCode = normalizeNullable(code);
        if (normalizedCode == null) {
            return null;
        }
        SkitInviteCodeRegistryDO row = selectGlobal(normalizedCode);
        return row != null && ACTIVE.equals(row.getStatus()) && row.getRotatedAt() == null
                ? toResolved(row) : null;
    }

    @Override
    public boolean isClaimed(String code) {
        String normalizedCode = normalizeNullable(code);
        return normalizedCode != null && selectGlobal(normalizedCode) != null;
    }

    @Override
    public ResolvedOwner claimAgent(Long tenantId, Long agentId, String code) {
        return claim(OwnerType.AGENT, tenantId, agentId, code);
    }

    @Override
    public ResolvedOwner claimMember(Long tenantId, Long memberId, String code) {
        return claim(OwnerType.MEMBER, tenantId, memberId, code);
    }

    @Override
    public ResolvedOwner lockActive(OwnerType type, Long tenantId, Long ownerId, String code) {
        requireOuterTransaction();
        validateOwner(type, tenantId, ownerId);
        String normalizedCode = normalizeRequired(code);
        AtomicReference<SkitInviteCodeRegistryDO> result = new AtomicReference<>();
        TenantUtils.execute(tenantId, () -> result.set(mapper.selectActiveForUpdate(
                tenantId, type.name(), ownerId, normalizedCode)));
        SkitInviteCodeRegistryDO row = result.get();
        if (row == null) {
            return null;
        }
        validateExactOwner(row, type, tenantId, ownerId, normalizedCode, ACTIVE);
        return toResolved(row);
    }

    @Override
    public void rotate(ResolvedOwner owner, LocalDateTime rotatedAt) {
        requireOuterTransaction();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(rotatedAt, "rotatedAt");
        validateOwner(owner.getOwnerType(), owner.getTenantId(), owner.getOwnerId());
        if (owner.getId() == null || owner.getId() <= 0 || !ACTIVE.equals(owner.getStatus())
                || owner.getRotatedAt() != null
                || (owner.getOwnerType() == OwnerType.AGENT && owner.getMemberId() != null)
                || (owner.getOwnerType() == OwnerType.MEMBER && owner.getAgentId() != null)) {
            throw new IllegalStateException("Only an exact active invite-code owner can be rotated");
        }
        String normalizedCode = normalizeRequired(owner.getNormalizedCode() == null
                ? owner.getCode() : owner.getNormalizedCode());
        if (owner.getCode() != null && !normalizedCode.equals(normalizeRequired(owner.getCode()))) {
            throw new IllegalStateException("Invite-code owner contains inconsistent code material");
        }
        AtomicReference<Integer> updated = new AtomicReference<>();
        TenantUtils.execute(owner.getTenantId(), () -> updated.set(mapper.rotateActive(
                owner.getId(), owner.getTenantId(), owner.getOwnerType().name(), owner.getOwnerId(),
                normalizedCode, rotatedAt)));
        if (!Integer.valueOf(1).equals(updated.get())) {
            throw new IllegalStateException("Active invite-code owner changed during rotation");
        }
    }

    private ResolvedOwner claim(OwnerType type, Long tenantId, Long ownerId, String code) {
        requireOuterTransaction();
        validateOwner(type, tenantId, ownerId);
        String normalizedCode = normalizeRequired(code);
        SkitInviteCodeRegistryDO row = new SkitInviteCodeRegistryDO()
                .setCode(normalizedCode).setNormalizedCode(normalizedCode).setOwnerType(type.name())
                .setAgentId(type == OwnerType.AGENT ? ownerId : null)
                .setMemberId(type == OwnerType.MEMBER ? ownerId : null)
                .setStatus(ACTIVE);
        row.setTenantId(tenantId);
        AtomicReference<Integer> inserted = new AtomicReference<>();
        try {
            TenantUtils.execute(tenantId, () -> inserted.set(mapper.insert(row)));
        } catch (DuplicateKeyException collision) {
            throw exception(INVITE_CODE_EXISTS);
        }
        if (!Integer.valueOf(1).equals(inserted.get()) || row.getId() == null) {
            throw new IllegalStateException("Invite-code ownership was not inserted");
        }
        return toResolved(row);
    }

    private SkitInviteCodeRegistryDO selectGlobal(String normalizedCode) {
        AtomicReference<SkitInviteCodeRegistryDO> result = new AtomicReference<>();
        TenantUtils.executeIgnore(() -> result.set(mapper.selectGlobalByNormalizedCode(normalizedCode)));
        SkitInviteCodeRegistryDO row = result.get();
        if (row != null && !normalizedCode.equals(normalizeRequired(row.getNormalizedCode() == null
                ? row.getCode() : row.getNormalizedCode()))) {
            throw new IllegalStateException("Global invite-code lookup returned a mismatched registry row");
        }
        return row;
    }

    private static void validateExactOwner(SkitInviteCodeRegistryDO row, OwnerType type, Long tenantId,
                                           Long ownerId, String normalizedCode, String expectedStatus) {
        Long rowOwnerId = type == OwnerType.AGENT ? row.getAgentId() : row.getMemberId();
        Long otherOwnerId = type == OwnerType.AGENT ? row.getMemberId() : row.getAgentId();
        if (!tenantId.equals(row.getTenantId()) || !type.name().equals(row.getOwnerType())
                || !ownerId.equals(rowOwnerId) || otherOwnerId != null
                || !normalizedCode.equals(normalizeRequired(row.getNormalizedCode() == null
                        ? row.getCode() : row.getNormalizedCode()))
                || !expectedStatus.equals(row.getStatus()) || row.getRotatedAt() != null) {
            throw new IllegalStateException("Locked invite-code row does not match the requested owner tuple");
        }
    }

    private static ResolvedOwner toResolved(SkitInviteCodeRegistryDO row) {
        if (row.getId() == null || row.getId() <= 0) {
            throw new IllegalStateException("Invite-code registry identity is corrupt");
        }
        OwnerType type;
        try {
            type = OwnerType.valueOf(row.getOwnerType());
        } catch (RuntimeException invalidOwnerType) {
            throw new IllegalStateException("Invite-code registry owner type is corrupt", invalidOwnerType);
        }
        Long ownerId = type == OwnerType.AGENT ? row.getAgentId() : row.getMemberId();
        validateOwner(type, row.getTenantId(), ownerId);
        if ((type == OwnerType.AGENT && row.getMemberId() != null)
                || (type == OwnerType.MEMBER && row.getAgentId() != null)) {
            throw new IllegalStateException("Invite-code registry owner tuple is corrupt");
        }
        String normalized = normalizeRequired(row.getNormalizedCode() == null
                ? row.getCode() : row.getNormalizedCode());
        return new ResolvedOwner(row.getId(), row.getTenantId(), type, row.getAgentId(), row.getMemberId(),
                row.getCode() == null ? normalized : row.getCode().trim(), normalized,
                row.getStatus(), row.getRotatedAt());
    }

    private static void validateOwner(OwnerType type, Long tenantId, Long ownerId) {
        if (type == null || tenantId == null || tenantId <= 0 || ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException("Invite-code owner scope is invalid");
        }
    }

    private static String normalizeNullable(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.length() > MAX_CODE_LENGTH ? null : normalized;
    }

    private static String normalizeRequired(String code) {
        String normalized = normalizeNullable(code);
        if (normalized == null) {
            throw new IllegalArgumentException("Invite code must contain 1 to 32 characters");
        }
        return normalized;
    }

    private static void requireOuterTransaction() {
        String xid = TransactionContext.getXID();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                && (xid == null || xid.trim().isEmpty())) {
            throw new IllegalStateException("Invite-code mutation requires an outer transaction");
        }
    }

}
