package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdClientEventBatchReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionCreateRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitAdSessionStatusRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitEntitlementRespVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitPlayerGrantCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.app.member.vo.ad.SkitPlayerGrantRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitMemberRateLimiterKeyResolver;
import cn.iocoder.yudao.module.skit.framework.security.SkitClientRuntimeResolver;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdSessionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import cn.iocoder.yudao.module.skit.service.content.SkitPangleDramaCatalogSyncService;
import cn.iocoder.yudao.module.skit.service.member.SkitContentEntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_CONTENT_CATALOG_MISSING;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_CONTENT_CATALOG_STALE;

@Tag(name = "用户 APP - 广告会话与服务端权益")
@RestController
@RequestMapping("/skit/member")
@Validated
@PreAuthorize("@ss.hasScope('skit_member') and @skitMemberSecurityGuard.isSkitMember()")
public class SkitMemberAdSessionController {

    @Resource
    private SkitAdSessionService adSessionService;
    @Resource
    private SkitContentEntitlementService entitlementService;
    @Resource
    private SkitPangleDramaCatalogSyncService catalogSyncService;
    @Resource
    private SkitClientRuntimeResolver clientRuntimeResolver;

    @PostMapping("/player-grants")
    @RateLimiter(time = 60, count = 30, keyResolver = SkitMemberRateLimiterKeyResolver.class)
    @ApiAccessLog(responseEnable = false)
    @Operation(summary = "为当前会员和短剧签发短时原生播放器权限")
    public CommonResult<SkitPlayerGrantRespVO> issuePlayerGrant(
            @Valid @RequestBody SkitPlayerGrantCreateReqVO request) {
        Long memberId = getLoginUserId();
        SkitTenantAdCapabilityService.ClientRuntime runtime = clientRuntimeResolver.resolve();
        SkitContentEntitlementService.PlayerGrantIssue issue;
        try {
            issue = entitlementService.issuePlayerGrant(memberId, request.getDramaId(), runtime);
        } catch (ServiceException failure) {
            if (!AD_CONTENT_CATALOG_MISSING.getCode().equals(failure.getCode())
                    && !AD_CONTENT_CATALOG_STALE.getCode().equals(failure.getCode())) {
                throw failure;
            }
            catalogSyncService.syncDrama(
                    TenantContextHolder.getRequiredTenantId(), request.getDramaId());
            issue = entitlementService.issuePlayerGrant(memberId, request.getDramaId(), runtime);
        }
        return success(SkitPlayerGrantRespVO.from(issue));
    }

    @PostMapping("/ad-sessions")
    @RateLimiter(time = 60, count = 60, keyResolver = SkitMemberRateLimiterKeyResolver.class)
    @ApiAccessLog(responseEnable = false)
    @Operation(summary = "为当前会员创建或恢复同一解锁范围的广告会话")
    public CommonResult<SkitAdSessionCreateRespVO> createAdSession(
            @Valid @RequestBody SkitAdSessionCreateReqVO request) {
        return success(SkitAdSessionCreateRespVO.from(
                adSessionService.createForMember(getLoginUserId(),
                        request.toCommand(clientRuntimeResolver.resolve()))));
    }

    @PostMapping("/ad-sessions/{sessionId}/client-events")
    @RateLimiter(time = 60, count = 120, keyResolver = SkitMemberRateLimiterKeyResolver.class)
    @Operation(summary = "批量幂等记录当前会员广告会话的客户端遥测")
    public CommonResult<SkitAdSessionStatusRespVO> recordClientEvents(
            @PathVariable("sessionId")
            @Pattern(regexp = "[A-Za-z0-9_-]{22}", message = "广告会话编号格式错误") String sessionId,
            @Valid @RequestBody SkitAdClientEventBatchReqVO request) {
        return success(SkitAdSessionStatusRespVO.from(adSessionService.recordClientEvents(
                getLoginUserId(), sessionId, request.toCommands(), clientRuntimeResolver.resolve())));
    }

    @GetMapping("/ad-sessions/{sessionId}")
    @RateLimiter(time = 60, count = 120, keyResolver = SkitMemberRateLimiterKeyResolver.class)
    @Operation(summary = "查询当前会员广告会话的验证、权益和收益状态")
    public CommonResult<SkitAdSessionStatusRespVO> getAdSession(
            @PathVariable("sessionId")
            @Pattern(regexp = "[A-Za-z0-9_-]{22}", message = "广告会话编号格式错误") String sessionId) {
        return success(SkitAdSessionStatusRespVO.from(
                adSessionService.getForMember(getLoginUserId(), sessionId,
                        clientRuntimeResolver.resolve())));
    }

    @GetMapping("/entitlements")
    @RateLimiter(time = 60, count = 120, keyResolver = SkitMemberRateLimiterKeyResolver.class)
    @Operation(summary = "按短剧查询当前会员的服务端逐集权益")
    public CommonResult<SkitEntitlementRespVO> getEntitlements(
            @RequestParam("dramaId") @Positive(message = "短剧编号必须大于 0") Long dramaId) {
        List<Integer> episodes = entitlementService.listGrantedEpisodes(
                getLoginUserId(), dramaId, clientRuntimeResolver.resolve());
        return success(SkitEntitlementRespVO.of(dramaId, episodes));
    }

}
