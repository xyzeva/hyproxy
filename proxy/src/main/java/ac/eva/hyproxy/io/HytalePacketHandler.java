package ac.eva.hyproxy.io;

import ac.eva.hyproxy.io.packet.impl.ClientDisconnect;
import ac.eva.hyproxy.io.packet.impl.auth.*;
import ac.eva.hyproxy.io.proto.NetworkChannel;
import io.netty.buffer.ByteBuf;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.packet.impl.ClientReferral;
import ac.eva.hyproxy.io.packet.impl.ServerDisconnect;
import ac.eva.hyproxy.io.packet.impl.game.ChatMessage;
import ac.eva.hyproxy.io.packet.impl.game.ServerMessage;
import ac.eva.hyproxy.io.packet.impl.setup.ServerInfo;

public interface HytalePacketHandler {
    default void connected() {}
    default void disconnected() {}
    default void activated() {}
    default void deactivated() {}

    default boolean handle(Connect connect) {
        return false;
    }
    default boolean handle(ClientDisconnect clientDisconnect) {
        return false;
    }
    default boolean handle(ServerDisconnect serverDisconnect) {
        return false;
    }
    default boolean handle(AuthGrant authGrant) {
        return false;
    }
    default boolean handle(AuthToken authToken) {
        return false;
    }
    default boolean handle(ServerAuthToken serverAuthToken) {
        return false;
    }
    default boolean handle(ConnectAccept connectAccept) {
        return false;
    }
    default boolean handle(RequestInsecurePlayerOptions requestInsecurePlayerOptions) {
        return false;
    }
    default boolean handle(InsecurePlayerOptions insecurePlayerOptions) {
        return false;
    }
    default boolean handle(ClientReferral referral) {
        return false;
    }
    default boolean handle(ServerMessage serverMessage) {
        return false;
    }
    default boolean handle(ChatMessage chatMessage) {
        return false;
    }
    default boolean handle(ServerInfo serverInfo) {
        return false;
    }

    default void handleGeneric(NetworkChannel channel, Packet packet) {}
    default void handleUnknown(NetworkChannel channel, ByteBuf buf) {}
}
