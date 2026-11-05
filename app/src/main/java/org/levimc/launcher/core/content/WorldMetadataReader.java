package org.levimc.launcher.core.content;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class WorldMetadataReader {
    static final class Summary {
        Integer gameType;
        String levelName;
    }

    private final ByteBuffer data;

    private WorldMetadataReader(byte[] bytes) {
        data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    static Summary read(File file, boolean needName) throws IOException {
        if (file.length() > org.levimc.launcher.core.content.nbt.NativeNbt.MAX_BYTES) {
            throw new IOException("NBT file exceeds 64 MiB");
        }
        try {
            WorldMetadataReader reader = new WorldMetadataReader(Files.readAllBytes(file.toPath()));
            if (reader.data.remaining() < 11) throw new IOException("Incomplete level.dat");
            reader.data.getInt();
            if (reader.data.getInt() != reader.data.remaining()) throw new IOException("Invalid level.dat length");
            if (reader.type() != 10) throw new IOException("Invalid level.dat root");
            reader.string();
            Summary summary = new Summary();
            while (reader.data.hasRemaining()) {
                int type = reader.type();
                if (type == 0) break;
                String name = reader.string();
                if (type == 3 && "GameType".equals(name)) summary.gameType = reader.data.getInt();
                else if (type == 8 && "LevelName".equals(name)) summary.levelName = reader.string();
                else reader.skip(type, 0);
                if (summary.gameType != null && (!needName || summary.levelName != null)) break;
            }
            return summary;
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException error) {
            throw new IOException("Invalid level.dat metadata", error);
        }
    }

    private int type() { return Byte.toUnsignedInt(data.get()); }

    private String string() throws IOException {
        int length = Short.toUnsignedInt(data.getShort());
        if (length > data.remaining()) throw new IOException("Incomplete NBT string");
        String value = new String(data.array(), data.position(), length, StandardCharsets.UTF_8);
        data.position(data.position() + length);
        return value;
    }

    private void advance(long bytes) throws IOException {
        if (bytes < 0 || bytes > data.remaining()) throw new IOException("Invalid NBT length");
        data.position(data.position() + (int) bytes);
    }

    private void skip(int type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nesting is too deep");
        switch (type) {
            case 0 -> { }
            case 1 -> advance(1);
            case 2 -> advance(2);
            case 3, 5 -> advance(4);
            case 4, 6 -> advance(8);
            case 7 -> advance(data.getInt());
            case 8 -> advance(Short.toUnsignedInt(data.getShort()));
            case 9 -> {
                int element = type();
                int count = data.getInt();
                if (count < 0 || (count > 0 && element == 0)) throw new IOException("Invalid NBT list");
                int width = switch (element) { case 1 -> 1; case 2 -> 2; case 3, 5 -> 4; case 4, 6 -> 8; default -> 0; };
                if (width != 0) advance((long) width * count);
                else for (int i = 0; i < count; i++) skip(element, depth + 1);
            }
            case 10 -> {
                int child;
                while ((child = type()) != 0) {
                    advance(Short.toUnsignedInt(data.getShort()));
                    skip(child, depth + 1);
                }
            }
            case 11 -> advance((long) data.getInt() * 4);
            case 12 -> advance((long) data.getInt() * 8);
            default -> throw new IOException("Unsupported NBT tag: " + type);
        }
    }
}
