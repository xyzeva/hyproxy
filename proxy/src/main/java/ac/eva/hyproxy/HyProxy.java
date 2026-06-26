package ac.eva.hyproxy;

import ac.eva.hyproxy.auth.HytaleAccountDataServiceClient;
import ac.eva.hyproxy.auth.HytaleOAuthServiceClient;
import ac.eva.hyproxy.auth.HytaleSessionServiceClient;
import ac.eva.hyproxy.auth.JWTVerifier;
import ac.eva.hyproxy.backend.BackendInfo;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.command.HyProxyCommandManager;
import ac.eva.hyproxy.command.provided.ReloadCommand;
import ac.eva.hyproxy.command.provided.SendCommand;
import ac.eva.hyproxy.command.provided.ShutdownCommand;
import ac.eva.hyproxy.config.HyProxyConfiguration;
import ac.eva.hyproxy.console.HyProxyConsole;
import ac.eva.hyproxy.event.HyProxyEventBus;
import ac.eva.hyproxy.io.HytaleConnection;
import ac.eva.hyproxy.io.QuicChannelInboundHandlerAdapter;
import ac.eva.hyproxy.player.HyProxyPlayer;
import ac.eva.hyproxy.player.permission.OperatorsPlayerPermissionProvider;
import ac.eva.hyproxy.player.permission.PlayerPermissionProvider;
import ac.eva.hyproxy.plugin.HyProxyPluginManager;
import ac.eva.hyproxy.util.AddressUtil;
import ac.eva.hyproxy.util.CertificateUtil;
import ac.eva.hyproxy.util.NettyUtil;
import com.google.common.collect.ImmutableList;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketProtocolFamily;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AttributeKey;
import jdk.net.ExtendedSocketOptions;
import kong.unirest.core.Unirest;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
public class HyProxy {
    public static final AttributeKey<X509Certificate> CLIENT_CERTIFICATE_ATTR = AttributeKey.valueOf("CLIENT_CERTIFICATE");
    public static final AttributeKey<HytaleConnection> HYTALE_CONNECTION_ATTR = AttributeKey.newInstance("HYTALE_CONNECTION");

    private static final EventLoopGroup WORKER_GROUP = NettyUtil.getEventLoopGroup("hyproxy-worker-group");

    private Bootstrap bootstrapIpv4 = null;
    private Bootstrap bootstrapIpv6 = null;
    private final List<ChannelFuture> endpoints = new ArrayList<>();


    @Getter
    private HyProxyConfiguration configuration;
    private final HyProxyConsole console = new HyProxyConsole(this);

    @Getter
    private final HytaleSessionServiceClient sessionServiceClient = new HytaleSessionServiceClient(this);
    @Getter
    private final HytaleOAuthServiceClient oAuthServiceClient = new HytaleOAuthServiceClient(this);
    @Getter
    private final HytaleAccountDataServiceClient accountDataServiceClient = new HytaleAccountDataServiceClient();

    @Getter
    private final JWTVerifier jwtVerifier = new JWTVerifier(this);
    @Getter
    private SelfSignedCertificate certificate;

    private final Map<UUID, HyProxyPlayer> playersByProfileId = new ConcurrentHashMap<>();
    private final Map<String, HyProxyPlayer> playersByUsername = new ConcurrentHashMap<>();
    private final Map<String, HyProxyBackend> backendsById = new ConcurrentHashMap<>();

    @Getter
    private final HyProxyCommandManager commandManager = new HyProxyCommandManager(this);

    private final HyProxyPluginManager pluginManager = new HyProxyPluginManager(this);

    @Getter
    private final HyProxyEventBus eventBus = new HyProxyEventBus();

    private final List<PlayerPermissionProvider> playerPermissionProviders = new ArrayList<>();

    public HyProxy() {
        try {
            this.certificate = new SelfSignedCertificate("localhost");

            QuicSslContext sslContext = QuicSslContextBuilder
                    .forServer(this.certificate.key(), null, this.certificate.cert())
                    .applicationProtocols("hytale/2")
                    .earlyData(false).clientAuth(ClientAuth.REQUIRE)
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();

            NettyUtil.ReflectiveChannelFactory<? extends DatagramChannel> channelFactoryIpv4 = NettyUtil.getDatagramChannelFactory(SocketProtocolFamily.INET);
            this.bootstrapIpv4 = new Bootstrap()
                    .group(WORKER_GROUP)
                    .channelFactory(channelFactoryIpv4)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(NioChannelOption.of(ExtendedSocketOptions.IP_DONTFRAGMENT), true)
                    .handler(new QuicChannelInboundHandlerAdapter(sslContext, this))
                    .validate();

            NettyUtil.ReflectiveChannelFactory<? extends DatagramChannel> channelFactoryIpv6 = NettyUtil.getDatagramChannelFactory(SocketProtocolFamily.INET6);
            this.bootstrapIpv6 = new Bootstrap()
                    .group(WORKER_GROUP)
                    .channelFactory(channelFactoryIpv6)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(NioChannelOption.of(ExtendedSocketOptions.IP_DONTFRAGMENT), true)
                    .handler(new QuicChannelInboundHandlerAdapter(sslContext, this))
                    .validate();
        } catch (Throwable throwable) {
            log.error("failed to initialize hyproxy", throwable);
            System.exit(1);
        }
    }

    public void start() {
        try {
            log.info("starting hyproxy (github.com/xyzeva/hyproxy)");
            log.info("heavily inspired by velocity");

            Unirest.config()
                    .addDefaultHeader("user-agent", "hyproxy (github.com/xyzeva/hyproxy)");

            this.configuration = HyProxyConfiguration.load(this, Path.of("config.toml"));

            if (!this.configuration.validate()) {
                log.error("your configuration isn't valid, please read the logs above and fix all the errors.");
                this.shutdown(true);
                return;
            }

            this.registerInitialConfigBackends();
            this.registerProvidedCommands();
            this.addPlayerPermissionProvider(new OperatorsPlayerPermissionProvider());

            Path pluginsDir = Path.of("plugins");
            boolean shouldLoadPlugins = true;

            if (!pluginsDir.toFile().isDirectory()) {
                if (!pluginsDir.toFile().mkdirs()) {
                    shouldLoadPlugins = false;
                    log.warn("failed to create plugins directory, plugins will NOT be loaded.");
                }
            }

            if (shouldLoadPlugins) {
                this.pluginManager.loadPlugins(pluginsDir);
            }

            this.oAuthServiceClient.start();
            this.sessionServiceClient.start();

            endpoints.add(this.bootstrapIpv4.bind(this.configuration.getBind()).syncUninterruptibly());
            log.info("bound ipv4");

            if (this.configuration.isIpv6Support()) {
                endpoints.add(this.bootstrapIpv6.bind(this.configuration.getBind()).syncUninterruptibly());
                log.info("bound ipv6");
            }

            for (ChannelFuture endpoint : endpoints) {
                if (!endpoint.isSuccess()) {
                    log.error("failed to bind endpoint", endpoint.exceptionNow());
                    this.shutdown(true);
                    return;
                }
            }

            console.start();
        } catch (Throwable throwable) {
            log.error("failed to start hyproxy", throwable);
            System.exit(1);
        }
    }

    private void registerProvidedCommands() {
        commandManager.registerCloudAnnotationCommand(new SendCommand());
        commandManager.registerCloudAnnotationCommand(new ReloadCommand(this));
        commandManager.registerCloudAnnotationCommand(new ShutdownCommand());
    }

    /**
     * reloads the proxy configuration
     * @return if the reload was successful or not
     */
    public boolean reloadConfig() {
        try {
            HyProxyConfiguration newConfig = HyProxyConfiguration.load(this, Path.of("config.toml"));
            if (!newConfig.validate()) {
                return false;
            }

            if (!newConfig.getBind().equals(this.configuration.getBind())) {
                log.warn("hyproxy does not yet support changing bind via reloading config, your bind will not change until a restart!");
            }

            if (newConfig.isIpv6Support() != this.configuration.isIpv6Support()) {
                log.warn("hyproxy does not yet support enabling/disabling ipv6 via reloading config, ipv6 support will not change until a restart!");
            }

            for (Map.Entry<String, String> entry : newConfig.getBackends().entrySet()) {
                BackendInfo newInfo = new BackendInfo(
                        entry.getKey(),
                        AddressUtil.parseAndResolveAddress(entry.getValue())
                );
                HyProxyBackend existingBackend = this.getBackendById(newInfo.id());

                if (existingBackend == null) {
                    this.registerBackend(newInfo);
                    continue;
                }

                if (existingBackend.getInfo().equals(newInfo)) {
                    continue;
                }

                log.warn("existing backend {} will not be updated via config reload, please restart if you want to change the address of an existing server", existingBackend.getInfo().id());
            }

            this.configuration = newConfig;

            return true;
        } catch (Exception ex) {
            log.error("error while reloading config", ex);
            return false;
        }
    }

    /**
     * @return the initial backend as defined in the proxy configuration
     */
    public @NonNull HyProxyBackend getInitialBackend() {
        return backendsById.get(configuration.getInitialBackend().toLowerCase(Locale.ROOT));
    }

    public @Nullable HyProxyBackend getBackendById(String id) {
        return backendsById.get(id.toLowerCase(Locale.ROOT));
    }

    /**
     * registers a backend to be used by the proxy
     * @param info the backend info
     * @throws IllegalArgumentException if a backend with the same id is registered already
     */
    public void registerBackend(BackendInfo info) {
        if (this.getBackendById(info.id()) != null) {
            throw new IllegalArgumentException("backend id " + info.id() + " is already registered");
        }

        this.backendsById.put(info.id().toLowerCase(Locale.ROOT), new HyProxyBackend(info));
    }

    /**
     * unregisters a backend so it is no longer used by the proxy.
     * please transfer the players connected to this backend before calling this, or they will be disconnected.
     * @param backend the backend to unregister
     * @throws IllegalArgumentException if the backend is not registered
     */
    public void unregisterBackend(HyProxyBackend backend) {
        if (this.getBackendById(backend.getInfo().id()) == null) {
            throw new IllegalArgumentException("backend id " + backend.getInfo().id() + " is not registered");
        }

        for (HyProxyPlayer player : backend.getPlayersConnected()) {
            player.disconnect("proxy backend unregistered");
        }

        this.backendsById.remove(backend.getInfo().id().toLowerCase(Locale.ROOT));
    }

    private void registerInitialConfigBackends() {
        int i = 0;
        for (Map.Entry<String, String> backendEntry : this.configuration.getBackends().entrySet()) {
            this.registerBackend(new BackendInfo(
                    backendEntry.getKey(),
                    AddressUtil.parseAndResolveAddress(backendEntry.getValue())
            ));
            i++;
        }
        log.info("registered {} config backends", i);
    }

    /**
     * gracefully shuts down the proxy
     */
    public void shutdown() {
        this.shutdown(false);
    }

    /**
     * gracefully shuts down the proxy
     * @param error if the shutdown was because of an error. please log the error before calling this function
     */
    public void shutdown(boolean error) {
        log.info("hyproxy is shutting down");


        for (HyProxyPlayer player : this.getPlayers(true)) {
            player.disconnect("Proxy shutting down");
        }

        for (ChannelFuture endpoint : this.endpoints) {
            endpoint.channel().close();
        }

        System.exit(error ? 1 : 0);
    }

    /**
     * adds a {@link PlayerPermissionProvider} to the list of permission providers
     * @param provider the provider to add
     */
    public void addPlayerPermissionProvider(PlayerPermissionProvider provider) {
        this.playerPermissionProviders.add(provider);
        provider.initialize(this);
        // re-sort
        this.playerPermissionProviders.sort(Comparator.comparingInt(PlayerPermissionProvider::priority).reversed());
    }

    /**
     * removes a {@link PlayerPermissionProvider} to the list of permission providers
     * @param provider the provider to remove
     * @return if the provider was successfully removed or not
     */
    public boolean removePlayerPermissionProvider(PlayerPermissionProvider provider) {
        boolean removed = this.playerPermissionProviders.remove(provider);
        // re-sort
        this.playerPermissionProviders.sort(Comparator.comparingInt(PlayerPermissionProvider::priority).reversed());

        return removed;
    }

    public List<PlayerPermissionProvider> getPlayerPermissionProviders() {
        return ImmutableList.copyOf(this.playerPermissionProviders);
    }

    /**
     * @return all the authenticated players on the proxy
     */
    public List<HyProxyPlayer> getPlayers() {
        return this.getPlayers(false);
    }

    /**
     * @param includeNonAuthenticated if we should include non-authenticated players or not
     * @return all the players on the proxy
     */
    public List<HyProxyPlayer> getPlayers(boolean includeNonAuthenticated) {
        Collection<HyProxyPlayer> allPlayers = this.playersByProfileId.values();
        if (includeNonAuthenticated) return ImmutableList.copyOf(allPlayers);

        List<HyProxyPlayer> activePlayers = new ArrayList<>();
        for (HyProxyPlayer player : allPlayers) {
            if (!player.isAuthenticated()) continue;
            activePlayers.add(player);
        }

        return ImmutableList.copyOf(activePlayers);
    }

    /**
     * gets an authenticated online player by hytale game profile id
     * @param profileId the game profile id
     * @return the player if authenticated and online. if not, null
     */
    public @Nullable HyProxyPlayer getPlayerByProfileId(UUID profileId) {
        return this.getPlayerByProfileId(profileId, false);
    }

    /**
     * gets an online player by hytale game profile id
     * @param profileId the game profile id
     * @param includeNonAuthenticated if we should allow a non-authenticated player or not
     * @return the player if online. if not, null
     */
    public @Nullable HyProxyPlayer getPlayerByProfileId(UUID profileId, boolean includeNonAuthenticated) {
        HyProxyPlayer player = this.playersByProfileId.get(profileId);
        if (player == null) {
            return null;
        }

        if (includeNonAuthenticated) return player;
        if (player.isAuthenticated()) return player;

        return null;
     }

    /**
     * gets an authenticated online player by hytale game profile username
     * @param username the game profile username
     * @return the player if authenticated and online. if not, null
     */
    public @Nullable HyProxyPlayer getPlayerByUsername(String username) {
        return this.getPlayerByUsername(username, false);
    }

    /**
     * gets an online player by hytale game profile username
     * @param username the game profile username
     * @param includeNonAuthenticated if we should allow a non-authenticated player or not
     * @return the player if online. if not, null
     */
    public @Nullable HyProxyPlayer getPlayerByUsername(String username, boolean includeNonAuthenticated) {
        HyProxyPlayer player = this.playersByUsername.get(username.toLowerCase(Locale.ROOT));
        if (player == null) {
            return null;
        }

        if (includeNonAuthenticated) return player;
        if (player.isAuthenticated()) return player;

        return null;
    }

    /**
     * internal: please don't call this!
     */
    public void registerPlayer(HyProxyPlayer player) {
        if (this.getPlayerByProfileId(player.getProfileId(), true) != null) {
            throw new IllegalArgumentException("player profile id " + player.getProfileId() + " already registered");
        }
        this.playersByProfileId.put(player.getProfileId(), player);
        this.playersByUsername.put(player.getUsername().toLowerCase(Locale.ROOT), player);
    }

    /**
     * internal: please don't call this!
     */
    public void unregisterPlayer(HyProxyPlayer player) {
        if (this.getPlayerByProfileId(player.getProfileId(), true) == null) {
            return;
        }
        
        this.playersByProfileId.remove(player.getProfileId());
        if (player.getUsername() != null) {
            this.playersByUsername.remove(player.getUsername().toLowerCase(Locale.ROOT));
        }
    }

    public String getServerCertFingerprint() {
        return CertificateUtil.computeCertificateFingerprint(this.certificate.cert());
    }
}
