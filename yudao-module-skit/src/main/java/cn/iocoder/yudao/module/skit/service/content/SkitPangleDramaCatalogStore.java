package cn.iocoder.yudao.module.skit.service.content;

import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.record.SkitAdminRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class SkitPangleDramaCatalogStore {

    private final SkitAdminRecordMapper recordMapper;

    public SkitPangleDramaCatalogStore(SkitAdminRecordMapper recordMapper) {
        this.recordMapper = Objects.requireNonNull(recordMapper, "recordMapper");
    }

    /**
     * Replaces stale tenant aliases and the canonical row in one independent transaction. The
     * provider request has already completed before this boundary is entered.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replaceDrama(long tenantId, long dramaId, SkitAdminRecordDO canonical) {
        String canonicalRowKey = "pangle-" + dramaId;
        if (tenantId <= 0 || dramaId <= 0 || canonical == null
                || !Long.valueOf(tenantId).equals(canonical.getTenantId())
                || !"drama".equals(canonical.getPageKey())
                || !canonicalRowKey.equals(canonical.getRowKey())) {
            throw new IllegalArgumentException("Pangle catalog replacement scope is invalid");
        }
        if (recordMapper.upsertPangleDramaCatalog(tenantId, canonical) <= 0) {
            throw new IllegalStateException("Pangle catalog upsert did not commit");
        }
        int retired = recordMapper.retirePangleDramaCatalogAliases(
                tenantId, Long.toString(dramaId), canonicalRowKey);
        if (retired < 0) {
            throw new IllegalStateException("Pangle catalog alias cleanup did not commit");
        }
    }
}
