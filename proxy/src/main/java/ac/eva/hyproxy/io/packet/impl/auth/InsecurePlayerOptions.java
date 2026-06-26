package ac.eva.hyproxy.io.packet.impl.auth;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.proto.PlayerSkin;
import ac.eva.hyproxy.common.util.ProtocolUtil;

import java.util.UUID;

/**
 * Client -> server (id 363). In insecure mode the player's identity is carried here rather than in
 * Connect. The proxy sends this on the player's behalf to a backend in response to
 * {@link RequestInsecurePlayerOptions}. Skin is omitted (proxy backends authenticate via the signed
 * referral handled by the hyproxy-backend plugin).
 *
 * Wire layout (little-endian): nullBits(1, bit0=skin present), uuid(16), then 2 int32-LE offset
 * slots [username, skin] relative to the variable block at byte 25.
 */
@Getter
@RequiredArgsConstructor
@ToString
public class InsecurePlayerOptions implements Packet {
    private final UUID uuid;
    private final String username;
    private final @Nullable PlayerSkin skin;

    public static InsecurePlayerOptions deserialize(ByteBuf buf) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }

    @Override
    public void serialize(ByteBuf buf) {
        final byte nullBits = (byte) (this.skin != null ? 0x1 : 0x0);

        buf.writeByte(nullBits);
        ProtocolUtil.writeUUID(buf, this.uuid);

        final int usernameOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        final int skinOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);

        final int varsOffset = buf.writerIndex();

        buf.setIntLE(usernameOffsetSlot, buf.writerIndex() - varsOffset);
        ProtocolUtil.writeVarString(buf, this.username);

        if (this.skin != null) {
            buf.setIntLE(skinOffsetSlot, buf.writerIndex() - varsOffset);
            this.skin.serialize(buf);
        }
    }
}
