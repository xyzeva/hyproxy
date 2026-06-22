package ac.eva.hyproxy.io;

import ac.eva.hyproxy.common.util.ProtocolUtil;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.packet.PacketRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class PacketDecoder extends ByteToMessageDecoder {
    private static final int MAX_PAYLOAD_LENGTH = 1677721600;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 8) return;

        in.markReaderIndex();
        int originalReaderIndex = in.readerIndex();
        int payloadLength = in.readIntLE();

        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
            in.skipBytes(in.readableBytes());
            ProtocolUtil.closeConnection(ctx.channel());
            return;
        }

        int packetId = in.readIntLE();
        PacketRegistry.PacketInfo packetInfo = PacketRegistry.getPacketById(packetId);

        if (in.readableBytes() < payloadLength) {
            in.resetReaderIndex();
            return;
        }

        if (System.getProperty("hyproxy.debugBytes").equals("true")) {
            int frameLength = 8 + payloadLength;
            log.info("INBOUND frame ({}B):\n{}", frameLength,
                    ByteBufUtil.prettyHexDump(in, originalReaderIndex,
                            Math.min(frameLength, 256)));
        }

        if (packetInfo == null) {
            out.add(in.copy(originalReaderIndex, 8 + payloadLength));
            in.skipBytes(payloadLength);
            return;
        }

        ByteBuf payload = in.readRetainedSlice(payloadLength);
        try {
            Packet packet = packetInfo.deserializeFunction().apply(payload);
            out.add(packet);
        } catch (Exception e) {
            log.warn("failed to decode packet id {} ({}); forwarding raw frame", packetId, e.toString());
            out.add(in.copy(originalReaderIndex, 8 + payloadLength));
        } finally {
            payload.release();
        }
    }
}
