package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class SkitUtf8ByteSizeValidator
        implements ConstraintValidator<SkitUtf8ByteSize, Object> {

    private int minimum;
    private int maximum;
    private boolean allowBlank;

    @Override
    public void initialize(SkitUtf8ByteSize constraint) {
        minimum = constraint.min();
        maximum = constraint.max();
        allowBlank = constraint.allowBlank();
        if (minimum < 0 || maximum < minimum) {
            throw new IllegalArgumentException("Invalid UTF-8 byte length constraint");
        }
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        CharBuffer characters;
        if (value instanceof char[]) {
            characters = CharBuffer.wrap((char[]) value);
        } else if (value instanceof CharSequence) {
            CharSequence sequence = (CharSequence) value;
            if (allowBlank && isBlank(sequence)) {
                return sequence.length() <= maximum;
            }
            characters = CharBuffer.wrap(sequence);
        } else {
            return false;
        }
        if (characters.remaining() > maximum) {
            return false;
        }

        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        int capacity = Math.max(1,
                (int) Math.ceil(characters.remaining() * encoder.maxBytesPerChar()));
        ByteBuffer encoded = ByteBuffer.allocate(capacity);
        try {
            CoderResult encodedResult = encoder.encode(characters, encoded, true);
            if (encodedResult.isError() || encodedResult.isOverflow()) {
                return false;
            }
            CoderResult flushedResult = encoder.flush(encoded);
            if (flushedResult.isError() || flushedResult.isOverflow()) {
                return false;
            }
            int byteLength = encoded.position();
            return byteLength >= minimum && byteLength <= maximum;
        } finally {
            Arrays.fill(encoded.array(), (byte) 0);
        }
    }

    private static boolean isBlank(CharSequence value) {
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
