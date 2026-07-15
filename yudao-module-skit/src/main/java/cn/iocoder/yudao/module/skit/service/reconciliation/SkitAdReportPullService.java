package cn.iocoder.yudao.module.skit.service.reconciliation;

public interface SkitAdReportPullService {

    /** Claims and serially processes a bounded set of due Taku accounts. */
    int pullDueAccounts();

}
