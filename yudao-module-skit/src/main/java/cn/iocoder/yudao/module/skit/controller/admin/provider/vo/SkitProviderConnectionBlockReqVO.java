package cn.iocoder.yudao.module.skit.controller.admin.provider.vo;

public final class SkitProviderConnectionBlockReqVO extends AbstractSkitCurrentPasswordReqVO {

  private String reason;

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  @Override
  public String toString() {
    return "SkitProviderConnectionBlockReqVO{reasonPresent=" + (reason != null) + '}';
  }
}
