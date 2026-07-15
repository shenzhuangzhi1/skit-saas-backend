package cn.iocoder.yudao.module.skit.service.ad.callback;

/** Globally claims and processes one bounded batch of verified callback inbox rows. */
public interface SkitAdCallbackInboxDrainService {

    int drainOnce();

}
