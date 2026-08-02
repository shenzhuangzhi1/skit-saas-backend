package cn.iocoder.yudao.module.skit.controller.admin.provider.vo;

public final class SkitProviderCallbackRouteIssueReqVO extends AbstractSkitCurrentPasswordReqVO {

  private String reason;

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  @Override
  public String toString() {
    return "SkitProviderCallbackRouteIssueReqVO{reasonPresent=" + (reason != null) + '}';
  }
}
