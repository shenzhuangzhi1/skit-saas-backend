package cn.iocoder.yudao.module.skit.service.content;

import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.record.SkitAdminRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitPangleDramaCatalogStoreTest {

    @Mock private SkitAdminRecordMapper recordMapper;

    @Test
    void canonicalUpsertAndLegacyRetirementShareOneRequiresNewTransaction() throws Exception {
        Transactional transactional = SkitPangleDramaCatalogStore.class
                .getMethod("replaceDrama", long.class, long.class, SkitAdminRecordDO.class)
                .getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());

        SkitAdminRecordDO canonical = canonical(162L, 1631L);
        when(recordMapper.upsertPangleDramaCatalog(162L, canonical)).thenReturn(1);
        when(recordMapper.retirePangleDramaCatalogAliases(
                162L, "1631", "pangle-1631")).thenReturn(2);

        new SkitPangleDramaCatalogStore(recordMapper)
                .replaceDrama(162L, 1631L, canonical);

        InOrder order = inOrder(recordMapper);
        order.verify(recordMapper).upsertPangleDramaCatalog(162L, canonical);
        order.verify(recordMapper).retirePangleDramaCatalogAliases(
                162L, "1631", "pangle-1631");
    }

    @Test
    void mismatchedTenantCannotMutateAnyCatalogRow() {
        SkitAdminRecordDO wrongTenant = canonical(163L, 1631L);

        assertThrows(IllegalArgumentException.class,
                () -> new SkitPangleDramaCatalogStore(recordMapper)
                        .replaceDrama(162L, 1631L, wrongTenant));

        verify(recordMapper, never()).upsertPangleDramaCatalog(anyLong(), any());
        verify(recordMapper, never()).retirePangleDramaCatalogAliases(
                anyLong(), any(), any());
    }

    private SkitAdminRecordDO canonical(long tenantId, long dramaId) {
        SkitAdminRecordDO row = SkitAdminRecordDO.builder()
                .pageKey("drama")
                .rowKey("pangle-" + dramaId)
                .recordData("{\"pangleDramaId\":" + dramaId + ",\"episodes\":88}")
                .status(0)
                .sort(0)
                .build();
        row.setTenantId(tenantId);
        row.setDeleted(false);
        return row;
    }
}
