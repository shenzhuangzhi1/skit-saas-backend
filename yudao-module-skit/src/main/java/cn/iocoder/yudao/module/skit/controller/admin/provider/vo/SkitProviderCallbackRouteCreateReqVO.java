package cn.iocoder.yudao.module.skit.controller.admin.provider.vo;

import cn.iocoder.yudao.module.skit.service.provider.SkitProviderConnectionService;

public final class SkitProviderCallbackRouteCreateReqVO extends AbstractSkitCurrentPasswordReqVO {

  private SkitProviderConnectionService.RoutePurpose purpose;
  private String reason;

  public SkitProviderConnectionService.RoutePurpose getPurpose() {
    return purpose;
  }

  public void setPurpose(SkitProviderConnectionService.RoutePurpose purpose) {
    this.purpose = purpose;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  @Override
  public String toString() {
    return "SkitProviderCallbackRouteCreateReqVO{purpose="
        + purpose
        + ", reasonPresent="
        + (reason != null)
        + '}';
  }
}
