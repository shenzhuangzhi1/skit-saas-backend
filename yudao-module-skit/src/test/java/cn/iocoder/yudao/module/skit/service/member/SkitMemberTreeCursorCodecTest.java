package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_CURSOR_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitMemberTreeCursorCodecTest {

    private final SkitMemberTreeCursorCodec codec = new SkitMemberTreeCursorCodec();

    @Test
    void roundTripCarriesStableSnapshotAndLastSortKey() {
        LocalDateTime asOf = LocalDateTime.of(2026, 7, 15, 10, 11, 12);
        LocalDateTime lastCreatedAt = LocalDateTime.of(2026, 7, 14, 9, 8, 7);

        String encoded = codec.encode(42L, 700L, asOf, lastCreatedAt, 900L);
        SkitMemberTreeCursorCodec.Cursor decoded = codec.decode(encoded, 42L, 700L);

        assertEquals(asOf, decoded.getAsOf());
        assertEquals(lastCreatedAt, decoded.getLastCreatedAt());
        assertEquals(900L, decoded.getLastId());
    }

    @Test
    void cursorCannotSelectAnotherTenantOrParent() {
        String encoded = codec.encode(42L, 700L,
                LocalDateTime.of(2026, 7, 15, 10, 11, 12),
                LocalDateTime.of(2026, 7, 14, 9, 8, 7), 900L);

        assertInvalid(() -> codec.decode(encoded, 43L, 700L));
        assertInvalid(() -> codec.decode(encoded, 42L, 701L));
        assertInvalid(() -> codec.decode("not-a-cursor", 42L, 700L));
    }

    private void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        ServiceException exception = assertThrows(ServiceException.class, executable);
        assertEquals(MANAGEMENT_CURSOR_INVALID.getCode(), exception.getCode());
    }
}
