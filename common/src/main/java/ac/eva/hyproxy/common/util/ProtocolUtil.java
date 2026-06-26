package ac.eva.hyproxy.common.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicTransportError;
import it.unimi.dsi.fastutil.Pair;
import lombok.experimental.UtilityClass;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@UtilityClass
public class ProtocolUtil {
    public static final ChannelFutureListener CLOSE_ON_COMPLETE = ProtocolUtil::closeApplicationOnComplete;

    public void writeVarString(ByteBuf buf, String str) {
        writeVarString(buf, str, StandardCharsets.US_ASCII);
    }
    public void writeVarString(ByteBuf buf, String str, Charset charset) {
        byte[] bytes = str.getBytes(charset);
        VarIntUtil.write(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public String readVarString(ByteBuf buf, int maxLength) {
        return readVarString(buf, maxLength, StandardCharsets.US_ASCII);
    }

    public Pair<String, Integer> readVarString(ByteBuf buf, int offset, int maxLength) {
        int varIntLength = VarIntUtil.length(buf, offset);
        int strLength = VarIntUtil.peek(buf, offset);
        if (strLength > maxLength) {
            throw new IllegalStateException("varint length too large (len=" + strLength + ", maxLength=" + maxLength + ")");
        }

        byte[] data = new byte[strLength];
        for (int i = 0; i < strLength; i++) {
            data[i] = buf.getByte(offset + varIntLength + i);
        }

        return Pair.of(new String(data, StandardCharsets.UTF_8), varIntLength + data.length);
    }

    public String readVarString(ByteBuf buf, int maxLength, Charset charset) {
        int len = VarIntUtil.read(buf);
        if (len > maxLength) {
            throw new IllegalStateException("varint length too large (len=" + len + ", maxLength=" + maxLength + ")");
        }

        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, charset);
    }

    public UUID readUUID(ByteBuf buf) {
        return new UUID(
                buf.readLong(),
                buf.readLong()
        );
    }


    public void writeUUID(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    public void closeConnection(Channel channel) {
        closeConnection(channel, QuicTransportError.PROTOCOL_VIOLATION);
    }

    public static void closeConnection(Channel channel, QuicTransportError error) {
        int errorCode = (int) error.code();
        if (channel instanceof QuicChannel quicChannel) {
            quicChannel.close(false, errorCode, Unpooled.EMPTY_BUFFER);
            return;
        }
        Channel parent = channel.parent();
        if (parent instanceof QuicChannel quicChannel) {
            quicChannel.close(false, errorCode, Unpooled.EMPTY_BUFFER);
        } else {
            channel.close();
        }
    }

    public static void closeApplicationConnection(Channel channel) {
        closeApplicationConnection(channel, 0);
    }

    public static void closeApplicationConnection(Channel channel, int errorCode) {
        if (channel instanceof QuicChannel quicChannel) {
            quicChannel.close(true, errorCode, Unpooled.EMPTY_BUFFER);
        } else {
            if (channel.parent() instanceof QuicChannel quicChannel) {
                quicChannel.close(true, errorCode, Unpooled.EMPTY_BUFFER);
            } else {
                channel.close();
            }
        }
    }
    private static void closeApplicationOnComplete(ChannelFuture future) {
        closeApplicationConnection(future.channel());
    }
}
