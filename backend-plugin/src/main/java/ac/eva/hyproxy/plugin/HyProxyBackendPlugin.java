package ac.eva.hyproxy.plugin;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.protocol.packets.auth.AuthGrant;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupConnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.Config;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.jspecify.annotations.NonNull;
import ac.eva.hyproxy.common.communication.ProxyCommunicationMessage;
import ac.eva.hyproxy.common.util.ProxyCommunicationUtil;
import ac.eva.hyproxy.common.util.RandomUtil;
import ac.eva.hyproxy.common.util.SecretMessageUtil;
import ac.eva.hyproxy.plugin.command.HyProxyBackendCommand;
import ac.eva.hyproxy.plugin.config.HyProxyBackendConfig;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public class HyProxyBackendPlugin extends JavaPlugin {
    private final Config<HyProxyBackendConfig> config = this.withConfig("config", HyProxyBackendConfig.CODEC);

    public HyProxyBackendPlugin(@NonNull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        HytaleServer.get().getEventBus().register(
                EventPriority.FIRST,
                PlayerSetupConnectEvent.class,
                this::onPlayerSetupConnect
        );
        CommandManager.get().register(new HyProxyBackendCommand(this));

        this.config.save();
    }

    private void onPlayerSetupConnect(PlayerSetupConnectEvent event) {
        byte[] data = event.getReferralData();
        if (data  == null) {
            event.setCancelled(true);
            event.setReason(Message.raw("cannot direct join hyproxy backend"));
            return;
        }

        try {
            ByteBuf buf = Unpooled.copiedBuffer(data);

            byte[] secret = getProxySecret();

            SecretMessageUtil.BackendPlayerInfoMessage message = SecretMessageUtil.validateAndDecodePlayerInfoReferral(
                    buf,
                    event.getUuid(),
                    event.getUsername(),
                    getBackendName(),
                    secret
            );

            if (message == null) {
                event.setCancelled(true);
                event.setReason(Message.raw("invalid player info message (is your proxy secret and backend id valid?)"));
                return;
            }

            getLogger().at(Level.INFO).log("successfully authenticated player {} with hyproxy (remoteAddress={})", message.profileId(), message.remoteAddress());
        } catch (Throwable throwable) {
            event.setCancelled(true);
            event.setReason(Message.raw("internal error while verifying player information"));
        }
    }

    /**
     * The backend id the proxy signs referrals with is this server's id. The proxy uses
     * {@code backend.getInfo().id()} (the id registered with the Hytale API), which in our
     * deployment is the {@code SERVER_ID} env (the pod name). Prefer that env over the config
     * default ("main") so a backend doesn't need a hand-written config.json per deployment.
     */
    private String getBackendName() {
        String serverId = System.getenv("SERVER_ID");
        return serverId != null && !serverId.isEmpty() ? serverId : config.get().getBackendName();
    }

    private byte[] getProxySecret() {
        byte[] proxySecret = System.getenv("HYPROXY_SECRET") != null ? System.getenv("HYPROXY_SECRET").getBytes(StandardCharsets.UTF_8) : null;

        if (proxySecret == null) {
            String configProxySecret = config.get().getProxySecret();

            if (configProxySecret == null) {
                return RandomUtil.generateSecureRandomString(32).getBytes(StandardCharsets.UTF_8);
            }

            proxySecret = config.get().getProxySecret().getBytes(StandardCharsets.UTF_8);
        }

        return proxySecret;
    }

    public void sendProxyMessage(ProxyCommunicationMessage message) {
        Universe.get().getPlayers().stream().findFirst()
                .ifPresent(playerRef -> this.sendProxyMessage(playerRef, message));
    }
    public void sendProxyMessage(PlayerRef playerRef, ProxyCommunicationMessage message) {
        this.sendProxyMessage(playerRef.getPacketHandler().getChannel(), message);
    }

    public void sendProxyMessage(ChannelConnection channel, ProxyCommunicationMessage message) {
        channel.writeAndFlush(new AuthGrant(
                null,
                ProxyCommunicationUtil.serializeMessage(message)
        ));
    }

    public void sendProxyMessage(Channel channel, ProxyCommunicationMessage message) {
        channel.writeAndFlush(new AuthGrant(
                null,
                ProxyCommunicationUtil.serializeMessage(message)
        ));
    }
}
