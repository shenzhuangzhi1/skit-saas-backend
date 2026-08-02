package cn.iocoder.yudao.module.skit.controller.admin.provider.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.Arrays;

/** One-shot response whose callback URL is written directly from, then wiped from, a char array. */
@JsonSerialize(using = SkitProviderCallbackRouteIssuedRespVO.Serializer.class)
public final class SkitProviderCallbackRouteIssuedRespVO {

  private final long routeId;
  private final String status;
  private final String fingerprint;
  @JsonIgnore private char[] callbackUrl;
  @JsonIgnore private boolean serialized;

  public SkitProviderCallbackRouteIssuedRespVO(
      long routeId, String status, String fingerprint, char[] callbackUrl) {
    if (routeId <= 0
        || status == null
        || fingerprint == null
        || callbackUrl == null
        || callbackUrl.length == 0) {
      clear(callbackUrl);
      throw new IllegalArgumentException("Invalid issued callback route response");
    }
    this.routeId = routeId;
    this.status = status;
    this.fingerprint = fingerprint;
    try {
      this.callbackUrl = callbackUrl.clone();
    } finally {
      clear(callbackUrl);
    }
  }

  public long getRouteId() {
    return routeId;
  }

  public String getStatus() {
    return status;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  @Override
  public String toString() {
    return "SkitProviderCallbackRouteIssuedRespVO{routeId="
        + routeId
        + ", status='"
        + status
        + "', fingerprint='"
        + fingerprint
        + "'}";
  }

  public static final class Serializer
      extends JsonSerializer<SkitProviderCallbackRouteIssuedRespVO> {

    @Override
    public void serialize(
        SkitProviderCallbackRouteIssuedRespVO value,
        JsonGenerator generator,
        SerializerProvider serializers)
        throws IOException {
      synchronized (value) {
        if (value.serialized || value.callbackUrl == null) {
          throw JsonMappingException.from(
              generator, "Issued callback route response has already been serialized");
        }
        value.serialized = true;
        try {
          generator.writeStartObject();
          generator.writeNumberField("routeId", value.routeId);
          generator.writeStringField("status", value.status);
          generator.writeStringField("fingerprint", value.fingerprint);
          generator.writeFieldName("callbackUrl");
          generator.writeString(value.callbackUrl, 0, value.callbackUrl.length);
          generator.writeEndObject();
        } finally {
          clear(value.callbackUrl);
          value.callbackUrl = null;
        }
      }
    }
  }

  private static void clear(char[] value) {
    if (value != null) {
      Arrays.fill(value, '\0');
    }
  }
}
