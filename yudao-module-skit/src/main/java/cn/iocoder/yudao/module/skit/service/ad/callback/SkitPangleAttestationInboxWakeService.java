package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Requeues the exact Taku reward callback whose only missing fact was a matching
 * Pangle reward attestation.
 */
@Service
public class SkitPangleAttestationInboxWakeService {

    private final SkitAdCallbackInboxMapper inboxMapper;

    public SkitPangleAttestationInboxWakeService(SkitAdCallbackInboxMapper inboxMapper) {
        this.inboxMapper = Objects.requireNonNull(inboxMapper, "inboxMapper");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean wakeRetry(Long tenantId, Long takuAdAccountId, Long adSessionId,
                             Integer callbackKeyVersion) {
        int changed = inboxMapper.wakePangleAttestationPendingRewardCas(
                tenantId, takuAdAccountId, adSessionId, callbackKeyVersion);
        if (changed == 0) {
            return false;
        }
        if (changed != 1) {
            throw new IllegalStateException(
                    "Pangle attestation wake CAS changed multiple callback inbox rows");
        }
        return true;
    }

}
