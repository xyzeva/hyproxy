package ac.eva.hyproxy.io.handler.inbound;

import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.auth.JWTVerifier;
import ac.eva.hyproxy.event.impl.player.PlayerAuthSuccessEvent;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.impl.auth.AuthGrant;
import ac.eva.hyproxy.io.packet.impl.auth.AuthToken;
import ac.eva.hyproxy.io.packet.impl.auth.ServerAuthToken;
import ac.eva.hyproxy.io.proto.ClientType;
import ac.eva.hyproxy.player.HyProxyPlayer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.security.cert.X509Certificate;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class InboundAuthPacketHandler implements HytalePacketHandler {
    private final HytaleConnection connection;
    private AuthState authState = AuthState.REQUESTING_AUTH_GRANT;

    @Override
    public void activated() {
        HyProxyPlayer player = connection.ensurePlayer();
        if (player.getIdentityToken() == null) {
            connection.disconnect("This proxy only supports online mode players!");
            return;
        }
        JWTVerifier.IdentityTokenClaims claims = connection.getProxy().getJwtVerifier().validateIdentityToken(player.getIdentityToken());
        if (claims == null) {
            connection.disconnect("Invalid or expired identity token");
            return;
        }

        UUID tokenProfileId = claims.getSubjectAsUUID();
        if (tokenProfileId == null || !tokenProfileId.equals(player.getProfileId())) {
            connection.disconnect("Invalid identity token: UUID mismatch");
            return;
        }

        String requiredScope = player.getClientType() == ClientType.EDITOR ? "hytale:editor" : "hytale:client";
        if (!claims.hasScope(requiredScope)) {
            connection.disconnect("Invalid identity token: missing " + requiredScope + " scope");
            return;
        }

        // let's send the client an auth grant
        connection.getProxy().getSessionServiceClient().requestAuthGrant(player.getIdentityToken())
                .thenAccept(grant -> {
                    String serverIdentityToken = connection.getProxy().getSessionServiceClient().getIdentityToken();
                    this.connection.getChannel().eventLoop()
                            .execute(() -> {
                                if (!this.connection.getChannel().isActive()) {
                                    return;
                                }

                                if (grant == null) {
                                    connection.disconnect("Failed to obtain authorization grant from session service");
                                    return;
                                }

                                this.authState = AuthState.AWAITING_AUTH_TOKEN;
                                this.connection.send(new AuthGrant(grant, serverIdentityToken));
                            });
                });
    }

    @Override
    public boolean handle(AuthToken authToken) {
        HyProxyPlayer player = connection.ensurePlayer();

        if (this.authState != AuthState.AWAITING_AUTH_TOKEN) {
            connection.disconnect("Protocol error: unexpected AuthToken packet");
            return true;
        }

        if (authToken.getAccessToken() == null || authToken.getAccessToken().isEmpty()) {
            connection.disconnect("Invalid access token");
            return true;
        }


        X509Certificate clientCert = connection.getChannel().attr(HyProxy.CLIENT_CERTIFICATE_ATTR).get();

        JWTVerifier.JWTClaims claims = connection.getProxy().getJwtVerifier().validateToken(authToken.getAccessToken(), clientCert);
        if (claims == null) {
            connection.disconnect("Invalid access token");
            return true;
        }

        UUID tokenProfileId = claims.getSubjectAsUUID();
        if (tokenProfileId == null || !tokenProfileId.equals(player.getProfileId())) {
            connection.disconnect("Invalid token claims: UUID mismatch");
            return true;
        }

        String tokenUsername = claims.username();
        if (tokenUsername == null || tokenUsername.isEmpty()) {
            connection.disconnect("Invalid token claims: missing username");
            return true;
        }

        player.setUsername(tokenUsername);
        String serverAuthGrant = authToken.getServerAuthorizationGrant();

        if (serverAuthGrant == null || serverAuthGrant.isEmpty()) {
            connection.disconnect("Mutual authentication required - please update your client");
            return true;
        }

        this.authState = AuthState.EXCHANGING_SERVER_TOKEN;
        connection.getProxy().getSessionServiceClient().exchangeAuthGrantForToken(serverAuthGrant, connection.getProxy().getServerCertFingerprint())
                .thenAccept(serverAccessToken -> {
                    if (!this.connection.getChannel().isActive()) {
                        return;
                    }

                    this.connection.getChannel()
                            .eventLoop()
                            .execute(() -> {
                                if (serverAccessToken == null) {
                                    connection.disconnect("Server authentication failed - please try again later");
                                    return;
                                }

                                this.onAuthenticated(serverAccessToken);
                            });
                });

        return true;
    }

    private void onAuthenticated(String serverAccessToken) {
        HyProxyPlayer player = connection.ensurePlayer();

        // check again as another connection may have come through
        if (connection.getProxy().getPlayerByProfileId(connection.ensurePlayer().getProfileId()) != null) {
            connection.disconnect("You are already connected to this proxy!");
            return;
        }

        connection.getProxy().registerPlayer(player);
        PlayerAuthSuccessEvent event = connection.getProxy().getEventBus().fire(new PlayerAuthSuccessEvent(
                player,
                false
        ));

        if (event.isCanceled()) {
            if (connection.isDisconnected()) {
                return;
            }

            connection.disconnect("proxy auth success event cancelled without disconnect");
            return;
        }

        if (connection.isDisconnected()) {
            return;
        }

        this.connection.send(new ServerAuthToken(serverAccessToken, null));
        log.info("{} successfully authenticated", this.connection.getIdentifier());
        player.setAuthenticated(true);

        this.connection.setPacketHandler(new InboundForwardingPacketHandler(this.connection));
    }

    enum AuthState {
        REQUESTING_AUTH_GRANT,
        AWAITING_AUTH_TOKEN,
        EXCHANGING_SERVER_TOKEN
    }
}
