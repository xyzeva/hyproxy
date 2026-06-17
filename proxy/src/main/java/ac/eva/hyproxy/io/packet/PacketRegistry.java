package ac.eva.hyproxy.io.packet;

import ac.eva.hyproxy.io.packet.impl.ClientDisconnect;
import ac.eva.hyproxy.io.packet.impl.auth.*;
import io.netty.buffer.ByteBuf;
import ac.eva.hyproxy.io.packet.impl.ClientReferral;
import ac.eva.hyproxy.io.packet.impl.ServerDisconnect;
import ac.eva.hyproxy.io.packet.impl.game.ChatMessage;
import ac.eva.hyproxy.io.packet.impl.game.ServerMessage;
import ac.eva.hyproxy.io.packet.impl.setup.ServerInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class PacketRegistry {
    private static final Map<Integer, PacketInfo> BY_ID = new HashMap<>();
    private static final Map<Class<? extends Packet>, PacketInfo> BY_CLASS = new HashMap<>();


    static {
        register(new PacketInfo(0, Connect.class, Connect::deserialize));
        register(new PacketInfo(1, ClientDisconnect.class, ClientDisconnect::deserialize));
        register(new PacketInfo(2, ServerDisconnect.class, ServerDisconnect::deserialize));
        register(new PacketInfo(11, AuthGrant.class, AuthGrant::deserialize));
        register(new PacketInfo(12, AuthToken.class, AuthToken::deserialize));
        register(new PacketInfo(13, ServerAuthToken.class, ServerAuthToken::deserialize));
        register(new PacketInfo(14, ConnectAccept.class, ConnectAccept::deserialize));
        register(new PacketInfo(18, ClientReferral.class, ClientReferral::deserialize));
        register(new PacketInfo(363, InsecurePlayerOptions.class, InsecurePlayerOptions::deserialize));
        register(new PacketInfo(364, RequestInsecurePlayerOptions.class, RequestInsecurePlayerOptions::deserialize));
        register(new PacketInfo(210, ServerMessage.class, ServerMessage::deserialize));
        register(new PacketInfo(211, ChatMessage.class, ChatMessage::deserialize));
        register(new PacketInfo(223, ServerInfo.class, ServerInfo::deserialize));
    }

    public static PacketInfo getPacketById(int id) {
        return BY_ID.get(id);
    }

    public static PacketInfo getPacketByClass(Class<? extends Packet> clazz) {
        return BY_CLASS.get(clazz);
    }

    private static void register(PacketInfo info) {
        BY_ID.put(info.id(), info);
        BY_CLASS.put(info.clazz(), info);
    }


    public record PacketInfo(int id, Class<? extends Packet> clazz, Function<ByteBuf, Packet> deserializeFunction) {}
}
