package cn.iocoder.yudao.module.skit.controller.admin.record;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordSaveReqVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementCommandType;
import cn.iocoder.yudao.module.skit.service.record.SkitAdminRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitAdminRecordControllerTest {

    @Mock
    private SkitAdminRecordService recordService;
    @Mock
    private SkitAdminTenantScopeGuard tenantScopeGuard;

    private SkitAdminRecordController controller;

    @BeforeEach
    void setUp() {
        controller = new SkitAdminRecordController();
        ReflectionTestUtils.setField(controller, "skitAdminRecordService", recordService);
        ReflectionTestUtils.setField(controller, "adminTenantScopeGuard", tenantScopeGuard);
    }

    @Test
    void targetedPageReadExecutesInsideGuardedTenant() {
        SkitAdminRecordPageReqVO request = new SkitAdminRecordPageReqVO();
        request.setPageKey("drama");
        request.setPageNo(1);
        request.setPageSize(10);
        request.setTenantId(162L);
        PageResult<SkitAdminRecordRespVO> page =
                new PageResult<>(Collections.<SkitAdminRecordRespVO>emptyList(), 0L);
        when(recordService.getRecordPage(request)).thenReturn(page);
        when(tenantScopeGuard.readTenant(eq(162L), eq(false), any()))
                .thenAnswer(invocation -> apply(invocation.getArgument(2)));

        PageResult<SkitAdminRecordRespVO> result = controller.getRecordPage(request).getData();

        assertEquals(page, result);
        verify(tenantScopeGuard).readTenant(eq(162L), eq(false), any());
        verify(recordService).getRecordPage(request);
    }

    @Test
    void targetedCreateRequiresGuardedAuditedWriteScope() {
        SkitAdminRecordSaveReqVO request = new SkitAdminRecordSaveReqVO();
        request.setTenantId(162L);
        request.setReason("同步穿山甲 SDK 真实剧单");
        request.setPageKey("drama");
        request.setRowKey("drama-1286");
        request.setRecordData(new LinkedHashMap<String, Object>());
        when(recordService.createRecord(request)).thenReturn(901L);
        when(tenantScopeGuard.writeTenant(eq(162L),
                eq(SkitManagementCommandType.ADMIN_RECORD_WRITE),
                eq("同步穿山甲 SDK 真实剧单"), any()))
                .thenAnswer(invocation -> apply(invocation.getArgument(3)));

        Long id = controller.createRecord(request).getData();

        assertEquals(901L, id);
        verify(tenantScopeGuard).writeTenant(eq(162L),
                eq(SkitManagementCommandType.ADMIN_RECORD_WRITE),
                eq("同步穿山甲 SDK 真实剧单"), any());
        verify(recordService).createRecord(request);
    }

    @Test
    void targetedDeleteRequiresGuardedAuditedWriteScope() {
        when(tenantScopeGuard.writeTenant(eq(162L),
                eq(SkitManagementCommandType.ADMIN_RECORD_WRITE),
                eq("删除目标租户短剧目录记录"), any()))
                .thenAnswer(invocation -> apply(invocation.getArgument(3)));

        Boolean result = controller.deleteRecord(
                901L, 162L, "删除目标租户短剧目录记录").getData();

        assertEquals(true, result);
        verify(tenantScopeGuard).writeTenant(eq(162L),
                eq(SkitManagementCommandType.ADMIN_RECORD_WRITE),
                eq("删除目标租户短剧目录记录"), any());
        verify(recordService).deleteRecord(901L);
    }

    @Test
    void targetedBatchDeleteRequiresGuardedAuditedWriteScope() {
        when(tenantScopeGuard.writeTenant(eq(162L),
                eq(SkitManagementCommandType.ADMIN_RECORD_WRITE),
                eq("删除目标租户短剧目录记录"), any()))
                .thenAnswer(invocation -> apply(invocation.getArgument(3)));

        Boolean result = controller.deleteRecordList(
                Arrays.asList(901L, 902L), 162L, "删除目标租户短剧目录记录").getData();

        assertEquals(true, result);
        verify(tenantScopeGuard).writeTenant(eq(162L),
                eq(SkitManagementCommandType.ADMIN_RECORD_WRITE),
                eq("删除目标租户短剧目录记录"), any());
        verify(recordService).deleteRecordList(Arrays.asList(901L, 902L));
    }

    @SuppressWarnings("unchecked")
    private static <T> T apply(Object action) {
        return ((Function<Object, T>) action).apply(null);
    }
}
