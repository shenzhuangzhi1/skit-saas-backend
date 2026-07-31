package cn.iocoder.yudao.module.system.service.tenant;

import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.dal.mysql.tenant.TenantMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomLongId;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies delegation to the MySQL-specific tenant lock queries without executing them on H2.
 */
public class TenantServiceLockDelegationTest extends BaseMockitoUnitTest {

    @InjectMocks
    private TenantServiceImpl tenantService;

    @Mock
    private TenantMapper tenantMapper;

    @Test
    public void testGetTenantForUpdate() {
        Long tenantId = randomLongId();
        TenantDO tenant = randomPojo(TenantDO.class, value -> value.setId(tenantId));
        when(tenantMapper.selectByIdForUpdate(tenantId)).thenReturn(tenant);

        TenantDO result = tenantService.getTenantForUpdate(tenantId);

        assertSame(tenant, result);
        verify(tenantMapper).selectByIdForUpdate(tenantId);
        verifyNoMoreInteractions(tenantMapper);
    }

    @Test
    public void testGetTenantForShare() {
        Long tenantId = randomLongId();
        TenantDO tenant = randomPojo(TenantDO.class, value -> value.setId(tenantId));
        when(tenantMapper.selectByIdForShare(tenantId)).thenReturn(tenant);

        TenantDO result = tenantService.getTenantForShare(tenantId);

        assertSame(tenant, result);
        verify(tenantMapper).selectByIdForShare(tenantId);
        verifyNoMoreInteractions(tenantMapper);
    }

}
