package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

import javax.annotation.security.PermitAll;
import java.lang.reflect.Method;
import java.util.Collections;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.PROVIDER_TAKU;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitMemberAdConfigControllerTest {

    @InjectMocks
    private SkitMemberAdConfigController controller;
    @Mock
    private SkitAdAccountService adAccountService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void anonymousConfigPublishesIndependentTakuDisplayPlacementsWithoutCredentials() throws Exception {
        Method endpoint = SkitMemberAdConfigController.class.getDeclaredMethod("getAdConfig");
        assertNull(endpoint.getAnnotation(PreAuthorize.class),
                "首页 Banner 配置必须允许当前白标租户的匿名访客读取");
        assertNotNull(endpoint.getAnnotation(PermitAll.class),
                "框架默认要求认证，匿名广告配置必须显式声明 PermitAll");
        SkitAdAccountService.PublicConfig taku = objectMapper.readValue("{"
                + "\"provider\":\"TAKU\","
                + "\"appId\":\"public-app\","
                + "\"placementId\":\"reward-slot\","
                + "\"checkInEntryInterstitialPlacementId\":\"checkin-slot\","
                + "\"postCheckInDramaInterstitialPlacementId\":\"drama-slot\","
                + "\"homeBannerPlacementId\":\"banner-slot\","
                + "\"enabled\":true,"
                + "\"whiteLabelRequired\":true}",
                SkitAdAccountService.PublicConfig.class);
        when(adAccountService.getEnabledPublicConfigs())
                .thenReturn(Collections.singletonList(taku));

        JsonNode response = objectMapper.valueToTree(controller.getAdConfig().getData());

        assertEquals(PROVIDER_TAKU, response.path("provider").asText());
        assertEquals("reward-slot", response.path("takuPlacementId").asText());
        assertEquals("checkin-slot",
                response.path("checkInEntryInterstitialPlacementId").asText());
        assertEquals("drama-slot",
                response.path("postCheckInDramaInterstitialPlacementId").asText());
        assertEquals("banner-slot", response.path("homeBannerPlacementId").asText());
        assertEquals("checkin-slot",
                response.path("taku").path("checkInEntryInterstitialPlacementId").asText());
        String serialized = objectMapper.writeValueAsString(response);
        assertFalse(serialized.toLowerCase().contains("appkey"));
        assertFalse(serialized.toLowerCase().contains("secret"));
    }

    @Test
    void blankDisplayPlacementsAreNotReturnedAsEffectivePlacements() throws Exception {
        SkitAdAccountService.PublicConfig taku = objectMapper.readValue("{"
                + "\"provider\":\"TAKU\","
                + "\"appId\":\"public-app\","
                + "\"placementId\":\"reward-slot\","
                + "\"checkInEntryInterstitialPlacementId\":null,"
                + "\"postCheckInDramaInterstitialPlacementId\":null,"
                + "\"homeBannerPlacementId\":null,"
                + "\"enabled\":true,"
                + "\"whiteLabelRequired\":true}",
                SkitAdAccountService.PublicConfig.class);
        when(adAccountService.getEnabledPublicConfigs())
                .thenReturn(Collections.singletonList(taku));

        JsonNode response = objectMapper.valueToTree(controller.getAdConfig().getData());

        assertTrue(response.path("checkInEntryInterstitialPlacementId").isNull());
        assertTrue(response.path("postCheckInDramaInterstitialPlacementId").isNull());
        assertTrue(response.path("homeBannerPlacementId").isNull());
    }
}
