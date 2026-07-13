package cn.iocoder.yudao.module.skit.dal.dataobject.agent;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Platform agent page projection. Tenant core fields are selected in the same page query so enrichment stays batched.
 */
@Data
public class SkitAgentPageRow {

    private Long agentId;
    private Long tenantId;
    private String tenantCode;
    private String rootInviteCode;
    private LocalDateTime archivedTime;
    private Long archivedBy;
    private String remark;
    private LocalDateTime createTime;

    private String name;
    private Long contactUserId;
    private String contactName;
    private String contactMobile;
    private Integer status;
    private Long packageId;
    private LocalDateTime expireTime;
    private Integer accountCount;

}
