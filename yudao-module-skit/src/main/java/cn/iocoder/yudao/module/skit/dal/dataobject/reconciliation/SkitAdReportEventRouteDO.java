package cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation;

import lombok.Data;

/** Callback-backed dimensions that can safely route a local event into a Taku report bucket. */
@Data
public class SkitAdReportEventRouteDO {

    private String placementId;
    private Integer networkFirmId;
    private String adsourceId;
}
