package ac.eva.hyproxy.player;

import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.command.CommandSender;
import ac.eva.hyproxy.common.util.SecretMessageUtil;
import ac.eva.hyproxy.event.impl.player.PlayerSentToBackendEvent;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.handler.outbound.OutboundEmptyPacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.packet.impl.ClientReferral;
import ac.eva.hyproxy.io.packet.impl.game.ServerMessage;
import ac.eva.hyproxy.io.proto.ClientType;
import ac.eva.hyproxy.io.proto.DisconnectType;
import ac.eva.hyproxy.io.proto.NetworkChannel;
import ac.eva.hyproxy.io.proto.PlayerSkin;
import ac.eva.hyproxy.message.Message;
import ac.eva.hyproxy.player.permission.PlayerPermissionProvider;
import com.google.common.collect.ImmutableSet;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Getter
@RequiredArgsConstructor
public class HyProxyPlayer implements CommandSender {
    private final HyProxy proxy;
    private final HytaleConnection inboundConnection;
    @Setter
    private HytaleConnection outboundConnection;

    // player info
    @Setter
    private int protocolCrc;
    @Setter
    private int protocolBuildNumber;
    @Setter
    private String clientVersion;
    @Setter
    private UUID profileId;
    @Setter
    private String username;
    @Setter
    private String identityToken;
    @Setter
    private String language;
    @Setter
    private ClientType clientType;
    @Setter
    private @Nullable HyProxyBackend referredBackend;
    @Setter
    private @Nullable PlayerSkin skin;

    @Setter
    private boolean authenticated = false;

    private @Nullable HyProxyBackend connectedBackend;

    /**
     * sends a player to another backend. this will send the client a referral with special referral data
     * that makes the proxy refer them to a different backend.
     * fires {@link PlayerSentToBackendEvent}
     * @param backend the new backend
     */
    public void sendPlayerToBackend(HyProxyBackend backend) {
        PlayerSentToBackendEvent event = this.proxy.getEventBus().fire(new PlayerSentToBackendEvent(
                this,
                this.connectedBackend,
                backend,
                false
        ));

        if (event.isCanceled()) {
            return;
        }

        HyProxyBackend newBackend = event.getNewBackend();
        log.info("{} is connecting to backend {}", this.getIdentifier(), newBackend.getInfo().id());

        byte[] referralData = SecretMessageUtil.generateReferralData(new SecretMessageUtil.BackendReferralMessage(
                this.profileId,
                newBackend.getInfo().id(),
                Instant.now().getEpochSecond()
        ), proxy.getConfiguration().getProxySecret());

        this.outboundConnection.setPacketHandler(new OutboundEmptyPacketHandler());

        this.sendToPlayer(new ClientReferral(proxy.getConfiguration().getPublicIp(), referralData));

        this.outboundConnection.disconnect("player sent to another backend");
    }

    public String getIdentifier() {
        return String.format("%s (%s)", this.getUsername(), this.getProfileId());
    }

    /**
     * sends the player's inbound connection a packet using the default network channel
     * @param packet the packet to send
     */
    public void sendToPlayer(Packet packet) {
        this.sendToPlayer(NetworkChannel.DEFAULT, packet);
    }

    /**
     * sends the player's inbound connection a packet using the specified network channel
     * @param channel the channel to send to
     * @param packet the packet to send
     */
    public void sendToPlayer(NetworkChannel channel, Packet packet) {
        if (!hasActiveInboundConnection()) {
            throw new IllegalStateException("tried sending player packet while inbound connection isn't active");
        }

        inboundConnection.send(channel, packet);
    }

    /**
     * sends the player's outbound connection a packet using the default network channel
     * @param packet the packet to send
     */
    public void sendAsPlayer(Packet packet) {
       this.sendAsPlayer(NetworkChannel.DEFAULT, packet);
    }

    /**
     * sends the player's outbound connection a packet using the specified network channel
     * @param channel the channel to send to
     * @param packet the packet to send
     */
    public void sendAsPlayer(NetworkChannel channel, Packet packet) {
        if (!hasActiveOutboundConnection()) {
            throw new IllegalStateException("tried sending packet as player while outbound channel isn't active");
        }

        outboundConnection.send(channel, packet);
    }

    /**
     * internal: please don't call this!
     */
    public void setConnectedBackend(HyProxyBackend backend) {
        if (this.connectedBackend != null) {
            throw new IllegalStateException("cannot call setConnectedBackend more then once, use sendPlayerToBackend to transfer players to other servers");
        }

        this.connectedBackend = backend;
        this.connectedBackend.registerPlayer(this);

        // cleanup so we dont have a old backend instance laying around
        this.referredBackend = null;
    }

    /**
     * disconnects the player with the disconnect type being a normal disconnect
     * @param message the disconnect message
     */
    public void disconnect(String message) {
        this.disconnect(message, DisconnectType.DISCONNECT);
    }

    /**
     * disconnects the player with the given disconnect type
     * @param message the disconnect message
     * @param type the disconnect type
     */
    public void disconnect(String message, DisconnectType type) {
        this.inboundConnection.disconnect(message, type); // this will disconnect the outbound connection too
    }

    /**
     * internal: please don't call this!
     */
    public void onDisconnect() {
        proxy.unregisterPlayer(this);
        if (this.connectedBackend != null) {
            this.connectedBackend.unregisterPlayer(this);
        }
    }

    public boolean hasActiveOutboundConnection() {
        return this.outboundConnection != null && this.outboundConnection.getChannel().isActive() && this.connectedBackend != null;
    }

    public boolean hasActiveInboundConnection() {
        return this.inboundConnection.getChannel().isActive();
    }

    public boolean isActive() {
        return hasActiveOutboundConnection() && hasActiveInboundConnection() && this.authenticated;
    }

    @Override
    public void sendMessage(Message message) {
        this.inboundConnection.send(new ServerMessage((byte) 0, message.getFormatted()));
    }

    /**
     * performs a proxy command as the player
     * <br /><br />
     * note: this will <b>not</b> execute backend commands
     * @param command the command to perform
     * @return if the command was found (on the proxy) or not
     */
    @Override
    public boolean performCommand(String command) {
        return proxy.getCommandManager().performCommand(this, command);
    }

    /**
     * @return all the player's permissions
     */
    public Set<String> getPermissions() {
        Set<String> allPermissions = new HashSet<>();
        for (PlayerPermissionProvider provider : proxy.getPlayerPermissionProviders()) {
            allPermissions.addAll(provider.getPlayerPermissions(this));
        }

        return ImmutableSet.copyOf(allPermissions);
    }

    /**
     * checks if the player has a permission
     * @param permission the permission to check for
     * @return if the player has the permission or not
     */
    @Override
    public boolean hasPermission(String permission) {
        for (PlayerPermissionProvider provider : proxy.getPlayerPermissionProviders()) {
            if (provider.hasPermission(this, permission)) return true;
        }

        return false;
    }
}
