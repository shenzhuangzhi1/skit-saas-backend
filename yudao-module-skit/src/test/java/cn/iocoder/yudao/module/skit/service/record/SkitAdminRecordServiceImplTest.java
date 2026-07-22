package cn.iocoder.yudao.module.skit.service.record;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitDashboardSummaryRespVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.record.SkitAdminRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitAdminRecordServiceImplTest {

    private static final String UNKNOWN_PAGE_KEY = "codexProbe";

    @Mock
    private SkitAdminRecordMapper recordMapper;

    private SkitAdminRecordServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SkitAdminRecordServiceImpl();
        ReflectionTestUtils.setField(service, "skitAdminRecordMapper", recordMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
    }

    @Test
    void unknownPageReadReturnsPersistedRowsOnlyWithoutAutoSeeding() {
        SkitAdminRecordPageReqVO request = new SkitAdminRecordPageReqVO();
        request.setPageKey(UNKNOWN_PAGE_KEY);
        request.setPageNo(1);
        request.setPageSize(10);
        when(recordMapper.selectPage(request))
                .thenReturn(new PageResult<>(Collections.<SkitAdminRecordDO>emptyList(), 0L));

        PageResult<?> result = service.getRecordPage(request);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getList().isEmpty());
        verify(recordMapper).selectPage(request);
    }

    @Test
    void dramaCatalogReadNeverInventsDemoRows() {
        SkitAdminRecordPageReqVO request = new SkitAdminRecordPageReqVO();
        request.setPageKey("drama");
        request.setPageNo(1);
        request.setPageSize(10);
        when(recordMapper.selectPage(request))
                .thenReturn(new PageResult<>(Collections.<SkitAdminRecordDO>emptyList(), 0L));

        PageResult<?> result = service.getRecordPage(request);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getList().isEmpty());
        verify(recordMapper, never()).selectCountByPageKey("drama");
        verify(recordMapper).selectPage(request);
    }

    @Test
    void knownManagementPageReadNeverSeedsOrInflatesPersistedRows() {
        SkitAdminRecordPageReqVO request = new SkitAdminRecordPageReqVO();
        request.setPageKey("withdraw");
        request.setPageNo(1);
        request.setPageSize(10);
        when(recordMapper.selectPage(request))
                .thenReturn(new PageResult<>(Collections.<SkitAdminRecordDO>emptyList(), 0L));

        PageResult<?> result = service.getRecordPage(request);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getList().isEmpty());
        verify(recordMapper, never()).selectCountByPageKey("withdraw");
        verify(recordMapper).selectPage(request);
    }

    @Test
    void managementPageReadFailureIsVisibleInsteadOfReturningFabricatedRows() {
        SkitAdminRecordPageReqVO request = new SkitAdminRecordPageReqVO();
        request.setPageKey("operationLog");
        request.setPageNo(1);
        request.setPageSize(10);
        when(recordMapper.selectPage(request)).thenThrow(new IllegalStateException("database unavailable"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.getRecordPage(request));

        assertEquals("database unavailable", failure.getMessage());
        verify(recordMapper).selectPage(request);
    }

    @Test
    void legacyDashboardReadNeverSeedsOrInventsMoney() {
        when(recordMapper.selectCountByPageKey("user")).thenReturn(8L);
        when(recordMapper.selectCountByPageKey("adRecord")).thenReturn(13L);

        SkitDashboardSummaryRespVO result = service.getDashboardSummary();

        assertEquals(8L, result.getTotalMembers());
        assertEquals(13L, result.getTotalAdCount());
        assertEquals(BigDecimal.ZERO, result.getTotalRevenue());
        assertEquals(BigDecimal.ZERO, result.getTotalProfit());
        assertEquals(0L, result.getTodayAdCount());
        assertEquals(BigDecimal.ZERO, result.getTodayRevenue());
        assertEquals(BigDecimal.ZERO, result.getRewardExchange());
        verify(recordMapper, times(1)).selectCountByPageKey("user");
        verify(recordMapper, times(1)).selectCountByPageKey("adRecord");
    }

}
