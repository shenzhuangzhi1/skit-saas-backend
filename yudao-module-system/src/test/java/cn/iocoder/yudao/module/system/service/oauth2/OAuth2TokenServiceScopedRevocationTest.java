package cn.iocoder.yudao.module.system.service.oauth2;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2RefreshTokenDO;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.OAuth2AccessTokenMapper;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.OAuth2RefreshTokenMapper;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuth2TokenServiceScopedRevocationTest {

    private static final long USER_ID = 8L;
    private static final int USER_TYPE = UserTypeEnum.MEMBER.getValue();

    @InjectMocks
    private OAuth2TokenServiceImpl tokenService;
    @Mock
    private OAuth2AccessTokenMapper accessTokenMapper;
    @Mock
    private OAuth2RefreshTokenMapper refreshTokenMapper;
    @Mock
    private OAuth2AccessTokenRedisDAO accessTokenRedisDAO;

    @Test
    void scopedRevocationKeepsSameNumericMemberIdWithWrongClientOrScope() {
        OAuth2AccessTokenDO skit = token(1L, "skit-access", "skit-refresh", "default", "skit_member");
        OAuth2AccessTokenDO wrongScope = token(2L, "legacy-access", "legacy-refresh", "default", "profile");
        OAuth2AccessTokenDO wrongClient = token(3L, "other-access", "other-refresh", "other", "skit_member");
        when(accessTokenMapper.selectListByUserIdAndUserTypeAndClientId(USER_ID, USER_TYPE, "default"))
                .thenReturn(Arrays.asList(skit, wrongScope, wrongClient));
        OAuth2RefreshTokenDO skitRefresh = refresh("skit-refresh", "default", "skit_member");
        OAuth2RefreshTokenDO standaloneSkitRefresh = refresh("standalone-refresh", "default", "skit_member");
        OAuth2RefreshTokenDO wrongScopeRefresh = refresh("legacy-refresh", "default", "profile");
        OAuth2RefreshTokenDO wrongClientRefresh = refresh("other-refresh", "other", "skit_member");
        when(refreshTokenMapper.selectListByUserIdAndUserTypeAndClientId(USER_ID, USER_TYPE, "default"))
                .thenReturn(Arrays.asList(skitRefresh, standaloneSkitRefresh,
                        wrongScopeRefresh, wrongClientRefresh));

        tokenService.removeAccessToken(USER_ID, USER_TYPE, "default", "skit_member");

        verify(accessTokenMapper).deleteById(1L);
        verify(refreshTokenMapper).deleteByRefreshToken("skit-refresh");
        verify(refreshTokenMapper).deleteByRefreshToken("standalone-refresh");
        verify(accessTokenRedisDAO).delete("skit-access");
        verify(accessTokenRedisDAO).delete("skit-refresh");
        verify(accessTokenRedisDAO).delete("standalone-refresh");
        verify(accessTokenMapper, never()).deleteById(2L);
        verify(accessTokenMapper, never()).deleteById(3L);
        verify(refreshTokenMapper, never()).deleteByRefreshToken("legacy-refresh");
        verify(refreshTokenMapper, never()).deleteByRefreshToken("other-refresh");
        verify(accessTokenRedisDAO, never()).delete("legacy-access");
        verify(accessTokenRedisDAO, never()).delete("other-access");
    }

    private OAuth2AccessTokenDO token(Long id, String accessToken, String refreshToken,
                                      String clientId, String scope) {
        return new OAuth2AccessTokenDO().setId(id).setUserId(USER_ID).setUserType(USER_TYPE)
                .setAccessToken(accessToken).setRefreshToken(refreshToken).setClientId(clientId)
                .setScopes(Collections.singletonList(scope));
    }

    private OAuth2RefreshTokenDO refresh(String refreshToken, String clientId, String scope) {
        return new OAuth2RefreshTokenDO().setUserId(USER_ID).setUserType(USER_TYPE)
                .setRefreshToken(refreshToken).setClientId(clientId)
                .setScopes(Collections.singletonList(scope));
    }
}
