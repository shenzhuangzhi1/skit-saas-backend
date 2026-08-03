package cn.iocoder.yudao.module.skit.service.ad.callback;

/** Automatically drains captured account-level Taku observations into exact tenant attribution. */
public interface SkitProviderImpressionAttributionDrainService {

    int drainOnce();
}
