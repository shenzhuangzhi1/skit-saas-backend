package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdClientEventBatchReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionCreateRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionStatusRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitGrantedEpisodesRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitRewardProvenanceRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitClientIpRateLimiterKeyResolver;
import cn.iocoder.yudao.module.skit.framework.security.SkitClientRuntimeResolver;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionService;
import cn.iocoder.yudao.module.skit.service.member.SkitContentEntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 APP - 短时播放器权限广告会话")
@RestController
@RequestMapping("/skit/member/native")
@Validated
@PermitAll
@TenantIgnore
public class SkitNativeAdSessionController {

    @Resource
    private SkitAdSessionService adSessionService;
    @Resource
    private SkitContentEntitlementService entitlementService;
    @Resource
    private SkitClientRuntimeResolver clientRuntimeResolver;

    @PostMapping("/ad-sessions")
    @RateLimiter(time = 60, count = 30, keyResolver = SkitClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "使用短时播放器权限创建或恢复广告会话")
    public CommonResult<SkitAdSessionCreateRespVO> createAdSession(
            @RequestHeader("X-Skit-Player-Grant")
            @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "播放器权限令牌格式错误") String grantToken,
            @Valid @RequestBody SkitAdSessionCreateReqVO request) {
        return success(SkitAdSessionCreateRespVO.from(
                adSessionService.createForNativeGrant(grantToken,
                        request.toCommand(clientRuntimeResolver.resolve()))));
    }

    @PostMapping("/ad-sessions/{sessionId}/client-events")
    @RateLimiter(time = 60, count = 120, keyResolver = SkitClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "使用短时播放器权限批量幂等记录客户端遥测")
    public CommonResult<SkitAdSessionStatusRespVO> recordClientEvents(
            @RequestHeader("X-Skit-Player-Grant")
            @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "播放器权限令牌格式错误") String grantToken,
            @PathVariable("sessionId")
            @Pattern(regexp = "[A-Za-z0-9_-]{22}", message = "广告会话编号格式错误") String sessionId,
            @Valid @RequestBody SkitAdClientEventBatchReqVO request) {
        return success(SkitAdSessionStatusRespVO.from(
                adSessionService.recordClientEventsForNativeGrant(
                        grantToken, sessionId, request.toCommands(), clientRuntimeResolver.resolve())));
    }

    @GetMapping("/ad-sessions/{sessionId}")
    @RateLimiter(time = 60, count = 120, keyResolver = SkitClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "使用短时播放器权限查询广告会话权威状态")
    public CommonResult<SkitAdSessionStatusRespVO> getAdSession(
            @RequestHeader("X-Skit-Player-Grant")
            @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "播放器权限令牌格式错误") String grantToken,
            @PathVariable("sessionId")
            @Pattern(regexp = "[A-Za-z0-9_-]{22}", message = "广告会话编号格式错误") String sessionId) {
        return success(SkitAdSessionStatusRespVO.from(
                adSessionService.getForNativeGrant(
                        grantToken, sessionId, clientRuntimeResolver.resolve())));
    }

    @GetMapping("/entitlements")
    @RateLimiter(time = 60, count = 120, keyResolver = SkitClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "使用短时播放器权限查询其固定短剧的服务端逐集权益")
    public CommonResult<SkitGrantedEpisodesRespVO> getEntitlements(
            @RequestHeader("X-Skit-Player-Grant")
            @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "播放器权限令牌格式错误") String grantToken) {
        return success(SkitGrantedEpisodesRespVO.of(
                entitlementService.listGrantedEpisodesForPlayerGrant(
                        grantToken, clientRuntimeResolver.resolve())));
    }

    @GetMapping("/entitlements/{episodeNo}/reward-provenance")
    @RateLimiter(time = 60, count = 120, keyResolver = SkitClientIpRateLimiterKeyResolver.class)
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "使用短时播放器权限查询已验签奖励展示凭证")
    public CommonResult<SkitRewardProvenanceRespVO> getRewardProvenance(
            @RequestHeader("X-Skit-Player-Grant")
            @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "播放器权限令牌格式错误") String grantToken,
            @PathVariable("episodeNo") @Positive(message = "剧集编号必须大于 0") Integer episodeNo,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        return success(SkitRewardProvenanceRespVO.of(episodeNo,
                entitlementService.findVerifiedRewardProvenanceForPlayerGrant(
                        grantToken, episodeNo, clientRuntimeResolver.resolve())));
    }

}
