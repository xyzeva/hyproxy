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

            SecretMessageUtil.BackendPlayerInfoMessage message = SecretMessageUtil.validateAndDecodePlayerInfoReferral(
                    buf,
                    event.getUuid(),
                    event.getUsername(),
                    getBackendName(),
                    getProxySecret()
            );

            if (message == null) {
                event.setCancelled(true);
                event.setReason(Message.raw("invalid player info message (is your proxy secret and backend id valid?)"));
                getLogger().at(Level.WARNING).log(
                    "failed to parse player info message, likely an invalid secret or backend (secret=<%d bytes>, backend=%s)",
                    this.getProxySecret().length,
                    this.getBackendName()
                );
                return;
            }

            getLogger().at(Level.INFO).log("successfully authenticated player {} with hyproxy (remoteAddress={})", message.profileId(), message.remoteAddress());
        } catch (Throwable throwable) {
            event.setCancelled(true);
            event.setReason(Message.raw("internal error while verifying player information"));
        }
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
    
    private String getBackendName() {
        return System.getenv("HYPROXY_BACKEND") != null ? System.getenv("HYPROXY_BACKEND") : config.get().getBackendName();
    }

    public void sendProxyMessage(ProxyCommunicationMessage message) {
        final PlayerRef player = Universe.get().getPlayers().iterator().next();
        this.sendProxyMessage(player, message);
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
}
