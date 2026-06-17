package ac.eva.hyproxy.io.packet.impl.auth;

import io.netty.buffer.ByteBuf;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;

/**
 * Server -> client (id 364). Sent by an insecure-mode backend after the proxy's Connect to ask the
 * proxy for the player's {@link InsecurePlayerOptions} (uuid/username). Empty payload.
 */
public class RequestInsecurePlayerOptions implements Packet {

    public static RequestInsecurePlayerOptions deserialize(ByteBuf buf) {
        return new RequestInsecurePlayerOptions();
    }

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }

    @Override
    public void serialize(ByteBuf buf) {
        // empty payload
    }
}
