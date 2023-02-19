package su.plo.voice.spectator;

import com.google.common.collect.Maps;
import org.jetbrains.annotations.NotNull;
import su.plo.config.provider.ConfigurationProvider;
import su.plo.config.provider.toml.TomlConfiguration;
import su.plo.lib.api.server.entity.MinecraftServerPlayerEntity;
import su.plo.lib.api.server.player.MinecraftServerPlayer;
import su.plo.lib.api.server.world.ServerPos3d;
import su.plo.voice.api.addon.AddonScope;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.event.EventCancellableBase;
import su.plo.voice.api.event.EventSubscribe;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.capture.SelfActivationInfo;
import su.plo.voice.api.server.audio.source.ServerEntitySource;
import su.plo.voice.api.server.audio.source.ServerPlayerSource;
import su.plo.voice.api.server.audio.source.ServerPositionalSource;
import su.plo.voice.api.server.audio.source.ServerStaticSource;
import su.plo.voice.api.server.event.VoiceServerInitializeEvent;
import su.plo.voice.api.server.event.audio.source.ServerSourceAudioPacketEvent;
import su.plo.voice.api.server.event.audio.source.ServerSourcePacketEvent;
import su.plo.voice.api.server.event.config.VoiceServerConfigLoadedEvent;
import su.plo.voice.api.server.event.connection.UdpClientDisconnectedEvent;
import su.plo.voice.api.server.player.VoiceServerPlayer;
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo;
import su.plo.voice.proto.packets.Packet;
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket;
import su.plo.voice.proto.packets.tcp.clientbound.SourceInfoPacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Addon(id = "spectator", scope = AddonScope.SERVER, version = "1.0.0", authors = {"Apehum"})
public final class SpectatorAddon {

    private static final ConfigurationProvider toml = ConfigurationProvider.getProvider(TomlConfiguration.class);

    private PlasmoVoiceServer voiceServer;
    private SelfActivationInfo selfActivationInfo;
    private SpectatorConfig config;

    private final Map<UUID, ServerStaticSource> staticSourceById = Maps.newConcurrentMap();
    private final Map<UUID, ServerEntitySource> entitySourceById = Maps.newConcurrentMap();
    private final Map<UUID, Long> lastPlayerPositionTimestampById = Maps.newConcurrentMap();

    @EventSubscribe
    public void onInitialize(@NotNull VoiceServerInitializeEvent event) {
        this.voiceServer = event.getServer();
        this.selfActivationInfo = new SelfActivationInfo(voiceServer.getUdpConnectionManager());
    }

    @EventSubscribe
    public void onConfigLoaded(@NotNull VoiceServerConfigLoadedEvent event) {
        try {
            File addonFolder = new File(voiceServer.getConfigFolder(), "addons");
            File configFile = new File(addonFolder, "spectator.toml");

            this.config = toml.load(SpectatorConfig.class, configFile, false);
            toml.save(SpectatorConfig.class, config, configFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config", e);
        }

        staticSourceById.forEach((playerId, source) -> source.setIconVisible(config.showIcon()));
    }

    @EventSubscribe
    public void onClientDisconnect(@NotNull UdpClientDisconnectedEvent event) {
        VoiceServerPlayer player = event.getConnection().getPlayer();
        removeSources(player);
    }

    @EventSubscribe
    public void onSourceAudioPacket(@NotNull ServerSourceAudioPacketEvent event) {
        if (!(event.getSource() instanceof ServerPlayerSource)) return;

        ServerPlayerSource playerSource = (ServerPlayerSource) event.getSource();
        SourceAudioPacket sourcePacket = event.getPacket();
        VoiceServerPlayer player = playerSource.getPlayer();

        getTargetSource(playerSource, player, event).ifPresent((source) -> {
            SourceAudioPacket sourceAudioPacket = new SourceAudioPacket(
                    event.getPacket().getSequenceNumber(),
                    (byte) 0,
                    sourcePacket.getData(),
                    source.getId(),
                    event.getDistance()
            );

            if (source.sendAudioPacket(sourceAudioPacket, event.getDistance()) &&
                    source instanceof ServerEntitySource &&
                    event.getActivationId().isPresent()
            ) {
                selfActivationInfo.sendAudioInfo(
                        player,
                        source,
                        event.getActivationId().get(),
                        sourceAudioPacket
                );
            }
        });
    }

    @EventSubscribe
    public void onSourcePacket(@NotNull ServerSourcePacketEvent event) {
        if (!(event.getSource() instanceof ServerPlayerSource)) return;

        ServerPlayerSource playerSource = (ServerPlayerSource) event.getSource();
        final Packet<?> sourcePacket = event.getPacket();
        VoiceServerPlayer player = playerSource.getPlayer();

        getTargetSource(playerSource, player, event).ifPresent((source) -> {
            if (sourcePacket instanceof SourceInfoPacket && source instanceof ServerEntitySource) {
                selfActivationInfo.updateSelfSourceInfo(player, source, ((SourceInfoPacket) sourcePacket).getSourceInfo());
            } else if (sourcePacket instanceof SourceAudioEndPacket) {
                SourceAudioEndPacket sourceEndPacket = (SourceAudioEndPacket) sourcePacket;
                SourceAudioEndPacket targetSourcePacket = new SourceAudioEndPacket(source.getId(), sourceEndPacket.getSequenceNumber());

                source.sendPacket(targetSourcePacket, event.getDistance());
                if (source instanceof ServerEntitySource) {
                    player.sendPacket(targetSourcePacket);
                }
            }
        });
    }

    private Optional<ServerPositionalSource<?>> getTargetSource(@NotNull ServerPlayerSource source,
                                                                @NotNull VoiceServerPlayer player,
                                                                @NotNull EventCancellableBase event) {
        if (!player.getInstance().isSpectator()) {
            removeSources(player);
            return Optional.empty();
        }

        if (player.getInstance().getSpectatorTarget().isPresent()) {
            event.setCancelled(true);
            return Optional.of(getEntitySource(source, player));
        } else {
            return Optional.of(getStaticSource(source, player));
        }
    }

    private void removeSources(@NotNull VoiceServerPlayer player) {
        lastPlayerPositionTimestampById.remove(player.getInstance().getUUID());
        ServerStaticSource staticSource = staticSourceById.remove(player.getInstance().getUUID());
        if (staticSource != null) voiceServer.getSourceManager().remove(staticSource);

        ServerEntitySource entitySource = entitySourceById.remove(player.getInstance().getUUID());
        if (entitySource != null) voiceServer.getSourceManager().remove(entitySource);
    }

    private ServerStaticSource getStaticSource(@NotNull ServerPlayerSource playerSource,
                                               @NotNull VoiceServerPlayer player) {
        PlayerSourceInfo sourceInfo = playerSource.getInfo();

        ServerStaticSource staticSource = staticSourceById.computeIfAbsent(
                player.getInstance().getUUID(),
                (sourceId) -> {
                    ServerStaticSource source = voiceServer.getSourceManager().createStaticSource(
                            this,
                            player.getInstance().getServerPosition(),
                            playerSource.getLine(),
                            sourceInfo.getCodec(),
                            sourceInfo.isStereo()
                    );
                    source.setIconVisible(config.showIcon());

                    source.addFilter((listener) ->
                            !listener.equals(player) && !((MinecraftServerPlayerEntity) listener.getInstance()).isSpectator()
                    );
                    return source;
                }
        );
        staticSource.setLine(playerSource.getLine());
        staticSource.setStereo(sourceInfo.isStereo());
        updateSourcePosition(player, staticSource);

        return staticSource;
    }

    private ServerEntitySource getEntitySource(@NotNull ServerPlayerSource playerSource,
                                               @NotNull VoiceServerPlayer player) {
        PlayerSourceInfo sourceInfo = playerSource.getInfo();

        ServerEntitySource entitySource = entitySourceById.computeIfAbsent(
                player.getInstance().getUUID(),
                (sourceId) -> {
                    ServerEntitySource source = voiceServer.getSourceManager().createEntitySource(
                            this,
                            player.getInstance().getSpectatorTarget().get(),
                            playerSource.getLine(),
                            sourceInfo.getCodec(),
                            sourceInfo.isStereo()
                    );
                    source.setIconVisible(config.showIcon());

                    source.addFilter((listener) -> !listener.equals(player));
                    return source;
                }
        );
        entitySource.setLine(playerSource.getLine());
        entitySource.setStereo(sourceInfo.isStereo());

        return entitySource;
    }

    private void updateSourcePosition(@NotNull VoiceServerPlayer player, @NotNull ServerStaticSource source) {
        long lastUpdate = lastPlayerPositionTimestampById.getOrDefault(
                player.getInstance().getUUID(),
                0L
        );

        if (System.currentTimeMillis() - lastUpdate < 100L) return;
        ServerPos3d position = player.getInstance().getServerPosition();
        position.setY(position.getY() + player.getInstance().getHitBoxHeight() + 0.5D);
        source.setPosition(position);

        lastPlayerPositionTimestampById.put(player.getInstance().getUUID(), System.currentTimeMillis());
    }
}
