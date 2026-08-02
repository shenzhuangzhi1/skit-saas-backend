package cn.iocoder.yudao.module.skit.controller.admin.provider.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SkitProviderConnectionCreateReqVO extends AbstractSkitCurrentPasswordReqVO {

  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  private char[] externalAccountReference;

  private String reason;

  @JsonProperty(value = "externalAccountReference", access = JsonProperty.Access.WRITE_ONLY)
  public synchronized void setExternalAccountReference(char[] value) {
    clear(externalAccountReference);
    externalAccountReference = value == null ? null : value.clone();
    clear(value);
  }

  @JsonIgnore
  public synchronized char[] consumeExternalAccountReference() {
    if (externalAccountReference == null) {
      throw new IllegalStateException("External account reference has already been consumed");
    }
    char[] owned = externalAccountReference;
    externalAccountReference = null;
    return owned;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  @Override
  public String toString() {
    return "SkitProviderConnectionCreateReqVO{reasonPresent=" + (reason != null) + '}';
  }
}
