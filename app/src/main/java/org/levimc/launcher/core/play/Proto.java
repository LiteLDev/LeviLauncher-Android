package org.levimc.launcher.core.play;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Just enough of the protobuf wire format for the handful of Google Play messages the
 * launcher sends and reads, so the app does not need generated message classes.
 */
final class Proto {
    private static final int VARINT = 0;
    private static final int FIXED64 = 1;
    private static final int BYTES = 2;
    private static final int FIXED32 = 5;

    private Proto() {
    }

    /** Builds one message; nested messages are written as already-encoded bytes. */
    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer int64(int field, long value) {
            tag(field, VARINT);
            varint(value);
            return this;
        }

        Writer bool(int field, boolean value) {
            return int64(field, value ? 1 : 0);
        }

        Writer string(int field, String value) {
            if (value == null) return this;
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Writer strings(int field, Iterable<String> values) {
            for (String value : values) {
                if (value != null && !value.isEmpty()) string(field, value);
            }
            return this;
        }

        Writer bytes(int field, byte[] value) {
            tag(field, BYTES);
            varint(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        Writer message(int field, Writer message) {
            return bytes(field, message.toByteArray());
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        private void tag(int field, int wireType) {
            varint(((long) field << 3) | wireType);
        }

        private void varint(long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        }
    }

    /**
     * A decoded message: every field kept by number, so readers can pull the few they need
     * and ignore the rest of Play's (large, ever-growing) schema.
     */
    static final class Message {
        private final List<Field> fields = new ArrayList<>();

        static Message parse(byte[] data) throws PlayException {
            try {
                return parse(data, 0, data.length);
            } catch (RuntimeException e) {
                throw new PlayException(PlayException.Kind.MALFORMED, "Malformed response from Google Play", e);
            }
        }

        private static Message parse(byte[] data, int start, int end) {
            Message message = new Message();
            int[] pos = {start};
            while (pos[0] < end) {
                long key = readVarint(data, pos, end);
                int field = (int) (key >>> 3);
                int wireType = (int) (key & 7);
                switch (wireType) {
                    case VARINT:
                        message.fields.add(new Field(field, readVarint(data, pos, end), null));
                        break;
                    case FIXED64: {
                        require(pos[0] + 8 <= end);
                        long value = 0;
                        for (int i = 7; i >= 0; i--) value = (value << 8) | (data[pos[0] + i] & 0xFFL);
                        pos[0] += 8;
                        message.fields.add(new Field(field, value, null));
                        break;
                    }
                    case BYTES: {
                        long length = readVarint(data, pos, end);
                        require(length >= 0 && pos[0] + length <= end);
                        byte[] value = new byte[(int) length];
                        System.arraycopy(data, pos[0], value, 0, (int) length);
                        pos[0] += (int) length;
                        message.fields.add(new Field(field, 0, value));
                        break;
                    }
                    case FIXED32: {
                        require(pos[0] + 4 <= end);
                        long value = 0;
                        for (int i = 3; i >= 0; i--) value = (value << 8) | (data[pos[0] + i] & 0xFFL);
                        pos[0] += 4;
                        message.fields.add(new Field(field, value, null));
                        break;
                    }
                    case 3: // start group: skip its contents up to the matching end group
                        skipGroup(data, pos, end, field);
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported wire type " + wireType);
                }
            }
            return message;
        }

        private static void skipGroup(byte[] data, int[] pos, int end, int groupField) {
            while (true) {
                long key = readVarint(data, pos, end);
                int field = (int) (key >>> 3);
                int wireType = (int) (key & 7);
                if (wireType == 4 && field == groupField) return;
                switch (wireType) {
                    case VARINT: readVarint(data, pos, end); break;
                    case FIXED64: pos[0] += 8; break;
                    case BYTES: pos[0] += (int) readVarint(data, pos, end); break;
                    case FIXED32: pos[0] += 4; break;
                    case 3: skipGroup(data, pos, end, field); break;
                    default: throw new IllegalArgumentException("Unsupported wire type " + wireType);
                }
                require(pos[0] <= end);
            }
        }

        private static long readVarint(byte[] data, int[] pos, int end) {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                require(pos[0] < end);
                byte b = data[pos[0]++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw new IllegalArgumentException("Varint too long");
        }

        private static void require(boolean condition) {
            if (!condition) throw new IllegalArgumentException("Truncated message");
        }

        private Field first(int number) {
            for (Field field : fields) {
                if (field.number == number) return field;
            }
            return null;
        }

        boolean has(int number) {
            return first(number) != null;
        }

        long int64(int number) {
            Field field = first(number);
            return field == null ? 0 : field.value;
        }

        boolean bool(int number) {
            return int64(number) != 0;
        }

        String string(int number) {
            Field field = first(number);
            return field == null || field.bytes == null ? "" : new String(field.bytes, StandardCharsets.UTF_8);
        }

        /** The nested message at [number], or an empty message when it is absent. */
        Message message(int number) {
            Field field = first(number);
            if (field == null || field.bytes == null) return new Message();
            return parse(field.bytes, 0, field.bytes.length);
        }

        List<Message> messages(int number) {
            List<Message> result = new ArrayList<>();
            for (Field field : fields) {
                if (field.number == number && field.bytes != null) {
                    result.add(parse(field.bytes, 0, field.bytes.length));
                }
            }
            return result;
        }

        /** Walks a chain of nested message fields, e.g. path(1, 2, 4). */
        Message path(int... numbers) {
            Message current = this;
            for (int number : numbers) current = current.message(number);
            return current;
        }
    }

    private static final class Field {
        final int number;
        final long value;
        final byte[] bytes;

        Field(int number, long value, byte[] bytes) {
            this.number = number;
            this.value = value;
            this.bytes = bytes;
        }
    }
}
