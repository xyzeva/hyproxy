package ac.eva.hyproxy.io.handler.outbound;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.common.util.SecretMessageUtil;
import ac.eva.hyproxy.config.HyProxyConfiguration;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.impl.auth.Connect;
import ac.eva.hyproxy.io.packet.impl.auth.ConnectAccept;
import ac.eva.hyproxy.player.HyProxyPlayer;
import ac.eva.hyproxy.util.NettyUtil;

import java.time.Instant;

@Slf4j
@RequiredArgsConstructor
public class OutboundInitialPacketHandler implements HytalePacketHandler {
    private final HytaleConnection connection;
    private final HyProxyBackend backend;

    @Override
    public void connected() {
        HyProxyPlayer player = connection.ensurePlayer();
        HyProxyConfiguration config = player.getProxy().getConfiguration();

        connection.send(new Connect(
                player.getProtocolCrc(),
                player.getProtocolBuildNumber(),
                player.getClientVersion(),
                player.getClientType(),
                null,
                player.getLanguage(),
                SecretMessageUtil.generatePlayerInfoReferral(new SecretMessageUtil.BackendPlayerInfoMessage(
                        player.getProfileId(),
                        player.getUsername(),
                        backend.getInfo().id(),
                        NettyUtil.formatRemoteAddress(player.getInboundConnection().getChannel()),
                        Instant.now().getEpochSecond()
                ), config.getProxySecret()),
                config.getPublicIp()
        ));
    }

    @Override
    public boolean handle(ConnectAccept connectAccept) {
        log.info("starting forwarding for {} to backend {}", connection.getIdentifier(), backend.getInfo().id());
        connection.setPacketHandler(new OutboundForwardingPacketHandler(connection, backend));
        return true;
    }

    @Override
    public void disconnected() {
        HyProxyPlayer player = connection.ensurePlayer();

        if (!player.hasActiveInboundConnection()) return;
        player.getInboundConnection().close();
    }
}
