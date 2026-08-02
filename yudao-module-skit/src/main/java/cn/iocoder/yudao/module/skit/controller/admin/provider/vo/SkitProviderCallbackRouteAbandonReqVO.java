package cn.iocoder.yudao.module.skit.controller.admin.provider.vo;

public final class SkitProviderCallbackRouteAbandonReqVO extends AbstractSkitCurrentPasswordReqVO {

  private String neverSharedDeclaration;

  public String getNeverSharedDeclaration() {
    return neverSharedDeclaration;
  }

  public void setNeverSharedDeclaration(String neverSharedDeclaration) {
    this.neverSharedDeclaration = neverSharedDeclaration;
  }

  @Override
  public String toString() {
    return "SkitProviderCallbackRouteAbandonReqVO{declarationPresent="
        + (neverSharedDeclaration != null)
        + '}';
  }
}
