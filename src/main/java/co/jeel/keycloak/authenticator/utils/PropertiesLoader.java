package co.jeel.keycloak.authenticator.utils;

import io.smallrye.config.SmallRyeConfig;
import org.eclipse.microprofile.config.ConfigProvider;

public class PropertiesLoader {
    private static final SmallRyeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);

    public static final String HMAC_KEY = config.getValue("hmac-key", String.class);

    public static String getProperty(String key) {
        return config.getValue(key, String.class);
    }
}
