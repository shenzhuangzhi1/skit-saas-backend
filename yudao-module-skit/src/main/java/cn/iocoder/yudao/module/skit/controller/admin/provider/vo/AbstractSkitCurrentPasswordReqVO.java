package cn.iocoder.yudao.module.skit.controller.admin.provider.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Arrays;

/** Owns a current-password buffer until the controller transfers it exactly once. */
public abstract class AbstractSkitCurrentPasswordReqVO {

  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  private char[] currentPassword;

  @JsonProperty(value = "currentPassword", access = JsonProperty.Access.WRITE_ONLY)
  public synchronized void setCurrentPassword(char[] value) {
    clear(currentPassword);
    currentPassword = value == null ? null : value.clone();
    clear(value);
  }

  @JsonIgnore
  public synchronized char[] consumeCurrentPassword() {
    if (currentPassword == null) {
      throw new IllegalStateException("Current password has already been consumed");
    }
    char[] owned = currentPassword;
    currentPassword = null;
    return owned;
  }

  static void clear(char[] value) {
    if (value != null) {
      Arrays.fill(value, '\0');
    }
  }
}
