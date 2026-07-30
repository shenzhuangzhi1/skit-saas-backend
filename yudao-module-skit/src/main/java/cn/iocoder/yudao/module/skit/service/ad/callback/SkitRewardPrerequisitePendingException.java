package cn.iocoder.yudao.module.skit.service.ad.callback;

/** Signals that a valid Taku reward is waiting for its Pangle attestation. */
public final class SkitRewardPrerequisitePendingException extends RuntimeException {

    public SkitRewardPrerequisitePendingException() {
        super("Pangle reward attestation is pending");
    }
}
