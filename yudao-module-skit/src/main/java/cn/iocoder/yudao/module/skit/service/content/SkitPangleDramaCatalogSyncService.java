package cn.iocoder.yudao.module.skit.service.content;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_CONTENT_CATALOG_SYNC_UNAVAILABLE;

@Service
public class SkitPangleDramaCatalogSyncService {

    private final SkitAdAccountMapper accountMapper;
    private final SkitPangleDramaCatalogStore catalogStore;
    private final PangleShortPlayClient client;
    private final ObjectMapper objectMapper;

    @Autowired
    public SkitPangleDramaCatalogSyncService(SkitAdAccountMapper accountMapper,
                                             SkitPangleDramaCatalogStore catalogStore,
                                             PangleShortPlayClient client,
                                             ObjectMapper objectMapper) {
        this.accountMapper = Objects.requireNonNull(accountMapper, "accountMapper");
        this.catalogStore = Objects.requireNonNull(catalogStore, "catalogStore");
        this.client = Objects.requireNonNull(client, "client");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * The provider call is deliberately suspended from the ad-session transaction. The resulting
     * catalog write is one tenant-scoped atomic upsert, after which session creation starts a fresh
     * transaction and revalidates the authoritative row.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void syncDrama(long tenantId, long dramaId) {
        if (tenantId <= 0 || dramaId <= 0
                || !Long.valueOf(tenantId).equals(TenantContextHolder.getRequiredTenantId())) {
            throw new IllegalArgumentException("Pangle catalog sync scope is invalid");
        }
        try {
            SkitAdAccountDO account = enabledAccount(tenantId,
                    accountMapper.selectEnabledPangleForShare(tenantId));
            PangleShortPlayClient.Drama drama = client.fetchDrama(
                    account.getAppId(), account.getSecret(), dramaId);
            SkitAdminRecordDO row = catalogRow(tenantId, drama);
            catalogStore.replaceDrama(tenantId, dramaId, row);
        } catch (RuntimeException failure) {
            throw exception(AD_CONTENT_CATALOG_SYNC_UNAVAILABLE);
        }
    }

    private SkitAdAccountDO enabledAccount(long tenantId, List<SkitAdAccountDO> matches) {
        if (matches == null || matches.size() != 1) {
            throw new IllegalStateException("Exactly one tenant Pangle account is required");
        }
        SkitAdAccountDO account = matches.get(0);
        if (account == null || account.getId() == null || account.getId() <= 0
                || !Long.valueOf(tenantId).equals(account.getTenantId())
                || !"PANGLE".equals(account.getProvider())
                || !CommonStatusEnum.ENABLE.getStatus().equals(account.getStatus())
                || Boolean.TRUE.equals(account.getDeleted())
                || account.getAppId() == null || account.getSecret() == null) {
            throw new IllegalStateException("Tenant Pangle account is invalid");
        }
        return account;
    }

    private SkitAdminRecordDO catalogRow(long tenantId, PangleShortPlayClient.Drama drama) {
        if (drama == null) {
            throw new IllegalStateException("Pangle drama metadata is unavailable");
        }
        ObjectNode data = objectMapper.createObjectNode();
        data.put("id", drama.getDramaId());
        data.put("pangleDramaId", drama.getDramaId());
        data.put("title", drama.getTitle());
        data.put("desc", drama.getDescription());
        data.put("cover", drama.getCoverImage());
        data.put("categoryId", drama.getCategoryId());
        data.put("category", drama.getCategoryName());
        data.put("episodes", drama.getTotalEpisodes());
        data.put("status", drama.getCompletionStatus() == 0 ? "已完结" : "连载中");
        data.put("publishStatus", "上架");
        data.put("completionStatus", drama.getCompletionStatus());
        data.put("createtime", drama.getCreateTime());
        data.put("freeEpisodes", 0);
        data.put("unlockSize", 1);
        data.put("provider", "PANGLE");
        data.put("catalogSource", "PANGLE_SERVER_API");
        SkitAdminRecordDO row = SkitAdminRecordDO.builder()
                .pageKey("drama")
                .rowKey("pangle-" + drama.getDramaId())
                .recordData(data.toString())
                .status(CommonStatusEnum.ENABLE.getStatus())
                .sort(0)
                .build();
        row.setTenantId(tenantId);
        row.setDeleted(false);
        return row;
    }
}
