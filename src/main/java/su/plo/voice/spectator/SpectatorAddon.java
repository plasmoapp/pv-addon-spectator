package su.plo.voice.spectator;

import com.google.common.collect.Maps;
import com.google.inject.Inject;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import su.plo.config.provider.ConfigurationProvider;
import su.plo.config.provider.toml.TomlConfiguration;
import su.plo.lib.api.server.entity.MinecraftServerPlayerEntity;
import su.plo.lib.api.server.world.ServerPos3d;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.AddonLoaderScope;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.event.EventCancellableBase;
import su.plo.voice.api.event.EventSubscribe;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.capture.SelfActivationInfo;
import su.plo.voice.api.server.audio.source.ServerEntitySource;
import su.plo.voice.api.server.audio.source.ServerPlayerSource;
import su.plo.voice.api.server.audio.source.ServerPositionalSource;
import su.plo.voice.api.server.audio.source.ServerStaticSource;
import su.plo.voice.api.server.event.audio.source.ServerSourceAudioPacketEvent;
import su.plo.voice.api.server.event.audio.source.ServerSourcePacketEvent;
import su.plo.voice.api.server.event.config.VoiceServerConfigReloadedEvent;
import su.plo.voice.api.server.event.connection.UdpClientDisconnectedEvent;
import su.plo.voice.api.server.player.VoiceServerPlayer;
import su.plo.voice.proto.data.audio.codec.opus.OpusDecoderInfo;
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

@Addon(id = "pv-addon-spectator", scope = AddonLoaderScope.SERVER, version = "1.0.0", authors = {"Apehum"})
public final class SpectatorAddon implements AddonInitializer {

    private static final ConfigurationProvider toml = ConfigurationProvider.getProvider(TomlConfiguration.class);

    @Inject
    private PlasmoVoiceServer voiceServer;
    private SelfActivationInfo selfActivationInfo;
    private SpectatorConfig config;

    private final Map<SourceLineKey, ServerStaticSource> staticSourceById = Maps.newConcurrentMap();
    private final Map<SourceLineKey, ServerEntitySource> entitySourceById = Maps.newConcurrentMap();
    private final Map<UUID, Long> lastPlayerPositionTimestampById = Maps.newConcurrentMap();

    @Override
    public void onAddonInitialize() {
        this.selfActivationInfo = new SelfActivationInfo(voiceServer.getUdpConnectionManager());
        loadConfig();
    }

    @EventSubscribe
    public void onConfigLoaded(@NotNull VoiceServerConfigReloadedEvent event) {
        loadConfig();
    }

    @EventSubscribe
    public void onClientDisconnect(@NotNull UdpClientDisconnectedEvent event) {
        VoiceServerPlayer player = (VoiceServerPlayer) event.getConnection().getPlayer();
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

    private void loadConfig() {
        try {
            File addonFolder = new File(voiceServer.getConfigsFolder(), "pv-addon-spectator");
            File configFile = new File(addonFolder, "config.toml");

            this.config = toml.load(SpectatorConfig.class, configFile, false);
            toml.save(SpectatorConfig.class, config, configFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config", e);
        }

        staticSourceById.forEach((playerId, source) -> source.setIconVisible(config.showIcon()));
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
        if (staticSource != null) staticSource.getLine().removeSource(staticSource);

        ServerEntitySource entitySource = entitySourceById.remove(player.getInstance().getUUID());
        if (entitySource != null) entitySource.getLine().removeSource(entitySource);
    }

    private ServerStaticSource getStaticSource(@NotNull ServerPlayerSource playerSource,
                                               @NotNull VoiceServerPlayer player) {
        PlayerSourceInfo sourceInfo = playerSource.getSourceInfo();
        SourceLineKey sourceLineKey = new SourceLineKey(player.getInstance().getUUID(), playerSource.getLine().getId());

        ServerStaticSource staticSource = staticSourceById.computeIfAbsent(
                sourceLineKey,
                (sourceId) -> {
                    ServerStaticSource source = playerSource.getLine().createStaticSource(
                            player.getInstance().getServerPosition(),
                            sourceInfo.isStereo(),
                            new OpusDecoderInfo()
                    );
                    source.setIconVisible(config.showIcon());

                    source.addFilter((listener) ->
                            !listener.equals(player) && !((MinecraftServerPlayerEntity) listener.getInstance()).isSpectator()
                    );
                    return source;
                }
        );
        staticSource.setStereo(sourceInfo.isStereo());
        staticSource.setName(player.getInstance().getName());
        updateSourcePosition(player, staticSource);

        return staticSource;
    }

    private ServerEntitySource getEntitySource(@NotNull ServerPlayerSource playerSource,
                                               @NotNull VoiceServerPlayer player) {
        PlayerSourceInfo sourceInfo = playerSource.getSourceInfo();
        SourceLineKey sourceLineKey = new SourceLineKey(player.getInstance().getUUID(), playerSource.getLine().getId());

        ServerEntitySource entitySource = entitySourceById.computeIfAbsent(
                sourceLineKey,
                (sourceId) -> {
                    ServerEntitySource source = playerSource.getLine().createEntitySource(
                            player.getInstance().getSpectatorTarget().get(),
                            sourceInfo.isStereo(),
                            new OpusDecoderInfo()
                    );
                    source.setIconVisible(config.showIcon());

                    source.addFilter((listener) -> !listener.equals(player));
                    return source;
                }
        );
        entitySource.setStereo(sourceInfo.isStereo());
        entitySource.setName(player.getInstance().getName());

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

    @EqualsAndHashCode
    @RequiredArgsConstructor
    private class SourceLineKey {

        private final UUID playerId;
        private final UUID sourceLineId;
    }
}
