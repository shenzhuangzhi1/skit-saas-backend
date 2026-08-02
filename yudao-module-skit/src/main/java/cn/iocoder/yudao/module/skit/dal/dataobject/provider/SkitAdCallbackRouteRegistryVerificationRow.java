package cn.iocoder.yudao.module.skit.dal.dataobject.provider;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/** One ordered legacy owner paired with its optional global registry row. */
@Data
public class SkitAdCallbackRouteRegistryVerificationRow {

    private Long tenantCallbackKeyId;
    private Long expectedTenantId;
    private Long expectedAdAccountId;
    private Integer expectedKeyVersion;
    private Boolean expectedActive;
    private LocalDateTime expectedAcceptUntil;
    @JsonIgnore
    @ToString.Exclude
    private byte[] expectedKeyHash;
    private LocalDateTime expectedTombstonedAt;

    private Long registryId;
    private String actualRouteType;
    private Long actualProviderCallbackRouteId;
    private Long actualTenantCallbackKeyId;
    private Long actualTenantId;
    private Long actualAdAccountId;
    private Integer actualKeyVersion;
    private Boolean actualActive;
    private LocalDateTime actualAcceptUntil;
    @JsonIgnore
    @ToString.Exclude
    private byte[] actualKeyHash;
    private LocalDateTime actualTombstonedAt;

}
