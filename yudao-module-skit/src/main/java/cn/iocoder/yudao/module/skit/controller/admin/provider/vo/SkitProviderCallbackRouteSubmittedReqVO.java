package cn.iocoder.yudao.module.skit.controller.admin.provider.vo;

public final class SkitProviderCallbackRouteSubmittedReqVO
    extends AbstractSkitCurrentPasswordReqVO {

  private String ticket;
  private String reference;
  private String recipient;
  private String reason;

  public String getTicket() {
    return ticket;
  }

  public void setTicket(String ticket) {
    this.ticket = ticket;
  }

  public String getReference() {
    return reference;
  }

  public void setReference(String reference) {
    this.reference = reference;
  }

  public String getRecipient() {
    return recipient;
  }

  public void setRecipient(String recipient) {
    this.recipient = recipient;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  @Override
  public String toString() {
    return "SkitProviderCallbackRouteSubmittedReqVO{ticketPresent="
        + (ticket != null)
        + ", referencePresent="
        + (reference != null)
        + ", recipientPresent="
        + (recipient != null)
        + ", reasonPresent="
        + (reason != null)
        + '}';
  }
}
