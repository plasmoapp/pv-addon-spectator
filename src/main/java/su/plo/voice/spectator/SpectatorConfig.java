package su.plo.voice.spectator;

import lombok.Data;
import lombok.experimental.Accessors;
import su.plo.config.Config;
import su.plo.config.ConfigField;

@Config
@Data
@Accessors(fluent = true)
public final class SpectatorConfig {

    @ConfigField
    private boolean showIcon = true;
}
