package cn.iocoder.yudao.module.skit.controller.admin.provider;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PROVIDER_COMMAND_INVALID;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderCallbackRouteAbandonReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderCallbackRouteCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderCallbackRouteIssueReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderCallbackRouteIssuedRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderCallbackRouteSubmittedReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderConnectionBlockReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderConnectionCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.provider.vo.SkitProviderConnectionRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.provider.SkitPlatformProviderCommandExecutor;
import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台 - 平台广告账号回调路由")
@RestController
@RequestMapping("/skit")
public class SkitProviderConnectionController {

  private final SkitPlatformAdminGuard platformAdminGuard;
  private final SkitPlatformProviderCommandExecutor commandExecutor;

  public SkitProviderConnectionController(
      SkitPlatformAdminGuard platformAdminGuard,
      SkitPlatformProviderCommandExecutor commandExecutor) {
    this.platformAdminGuard = platformAdminGuard;
    this.commandExecutor = commandExecutor;
  }

  @PostMapping("/provider-connections/shared-master")
  @Operation(summary = "创建唯一的共享 Taku 主账号连接")
  @PreAuthorize("@ss.hasRole('super_admin')")
  @ApiAccessLog(requestEnable = false, responseEnable = false)
  public ResponseEntity<CommonResult<SkitProviderConnectionRespVO>> createSharedMaster(
      @RequestBody SkitProviderConnectionCreateReqVO request) {
    requireRequest(request);
    char[] password = null;
    char[] externalReference = null;
    try {
      password = request.consumeCurrentPassword();
      externalReference = request.consumeExternalAccountReference();
      platformAdminGuard.check();
      return noStore(
          success(
              SkitProviderConnectionRespVO.from(
                  commandExecutor.createSharedMaster(
                      password, externalReference, request.getReason()))));
    } finally {
      clear(password);
      clear(externalReference);
    }
  }

  @GetMapping("/provider-connections/{id}")
  @Operation(summary = "查询共享账号及其活跃路由的安全状态")
  @PreAuthorize("@ss.hasRole('super_admin')")
  public ResponseEntity<CommonResult<SkitProviderConnectionRespVO>> getConnection(
      @PathVariable("id") long id) {
    platformAdminGuard.check();
    return noStore(success(SkitProviderConnectionRespVO.from(commandExecutor.getConnection(id))));
  }

  @PostMapping("/provider-connections/{id}/routes")
  @Operation(summary = "创建不可变用途的草稿回调路由")
  @PreAuthorize("@ss.hasRole('super_admin')")
  @ApiAccessLog(requestEnable = false, responseEnable = false)
  public ResponseEntity<CommonResult<SkitProviderConnectionRespVO>> createDraftRoute(
      @PathVariable("id") long id, @RequestBody SkitProviderCallbackRouteCreateReqVO request) {
    requireRequest(request);
    char[] password = null;
    try {
      password = request.consumeCurrentPassword();
      platformAdminGuard.check();
      return noStore(
          success(
              SkitProviderConnectionRespVO.from(
                  commandExecutor.createDraftRoute(
                      id, request.getPurpose(), password, request.getReason()))));
    } finally {
      clear(password);
    }
  }

  @PostMapping("/provider-routes/{id}/issue-once")
  @Operation(summary = "一次性签发 provider 回调地址")
  @PreAuthorize("@ss.hasRole('super_admin')")
  @ApiAccessLog(requestEnable = false, responseEnable = false)
  public ResponseEntity<CommonResult<SkitProviderCallbackRouteIssuedRespVO>> issueOnce(
      @PathVariable("id") long id, @RequestBody SkitProviderCallbackRouteIssueReqVO request) {
    requireRequest(request);
    char[] password = null;
    char[] callbackUrl = null;
    try {
      password = request.consumeCurrentPassword();
      platformAdminGuard.check();
      SkitProviderConnectionService.IssuedRoute issued =
          commandExecutor.issueOnce(id, password, request.getReason());
      callbackUrl = issued.consumeCallbackUrl();
      SkitProviderCallbackRouteIssuedRespVO response =
          new SkitProviderCallbackRouteIssuedRespVO(
              issued.getRouteId(), "ISSUED", issued.getFingerprint(), callbackUrl);
      return noStore(success(response));
    } finally {
      clear(password);
      clear(callbackUrl);
    }
  }

  @PostMapping("/provider-routes/{id}/abandon-never-shared")
  @Operation(summary = "声明未共享后放弃已签发路由")
  @PreAuthorize("@ss.hasRole('super_admin')")
  @ApiAccessLog(requestEnable = false, responseEnable = false)
  public ResponseEntity<CommonResult<SkitProviderConnectionRespVO>> abandonNeverShared(
      @PathVariable("id") long id, @RequestBody SkitProviderCallbackRouteAbandonReqVO request) {
    requireRequest(request);
    char[] password = null;
    try {
      password = request.consumeCurrentPassword();
      platformAdminGuard.check();
      return noStore(
          success(
              SkitProviderConnectionRespVO.from(
                  commandExecutor.abandonNeverShared(
                      id, password, request.getNeverSharedDeclaration()))));
    } finally {
      clear(password);
    }
  }

  @PostMapping("/provider-routes/{id}/mark-submitted")
  @Operation(summary = "记录生产回调路由已提交 provider")
  @PreAuthorize("@ss.hasRole('super_admin')")
  @ApiAccessLog(requestEnable = false, responseEnable = false)
  public ResponseEntity<CommonResult<SkitProviderConnectionRespVO>> markSubmitted(
      @PathVariable("id") long id, @RequestBody SkitProviderCallbackRouteSubmittedReqVO request) {
    requireRequest(request);
    char[] password = null;
    try {
      password = request.consumeCurrentPassword();
      platformAdminGuard.check();
      return noStore(
          success(
              SkitProviderConnectionRespVO.from(
                  commandExecutor.markSubmitted(
                      id,
                      password,
                      request.getTicket(),
                      request.getReference(),
                      request.getRecipient(),
                      request.getReason()))));
    } finally {
      clear(password);
    }
  }

  @PostMapping("/provider-connections/{id}/block")
  @Operation(summary = "终止共享账号连接及其接收路由")
  @PreAuthorize("@ss.hasRole('super_admin')")
  @ApiAccessLog(requestEnable = false, responseEnable = false)
  public ResponseEntity<CommonResult<SkitProviderConnectionRespVO>> block(
      @PathVariable("id") long id, @RequestBody SkitProviderConnectionBlockReqVO request) {
    requireRequest(request);
    char[] password = null;
    try {
      password = request.consumeCurrentPassword();
      platformAdminGuard.check();
      return noStore(
          success(
              SkitProviderConnectionRespVO.from(
                  commandExecutor.block(id, password, request.getReason()))));
    } finally {
      clear(password);
    }
  }

  private static void requireRequest(Object request) {
    if (request == null) {
      throw exception(PROVIDER_COMMAND_INVALID);
    }
  }

  private static void clear(char[] value) {
    if (value != null) {
      Arrays.fill(value, '\0');
    }
  }

  private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(body);
  }
}
