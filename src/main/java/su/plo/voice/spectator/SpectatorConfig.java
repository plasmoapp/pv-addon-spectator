package su.plo.voice.spectator;

import lombok.Data;
import su.plo.config.Config;
import su.plo.config.ConfigField;

@Config
@Data
public final class SpectatorConfig {

    @ConfigField
    private boolean showIcon = true;
}
