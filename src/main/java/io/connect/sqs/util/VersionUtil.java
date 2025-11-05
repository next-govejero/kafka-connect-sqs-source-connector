package io.connect.sqs.util;

import java.io.InputStream;
import java.util.Properties;

/**
 * Utility class for version information.
 */
public class VersionUtil {

    private static final String VERSION;

    static {
        String version = "unknown";
        try (InputStream stream = VersionUtil.class.getResourceAsStream("/version.properties")) {
            if (stream != null) {
                Properties props = new Properties();
                props.load(stream);
                version = props.getProperty("version", "unknown");
            }
        } catch (Exception e) {
            // Ignore and use default
        }
        VERSION = version;
    }

    public static String getVersion() {
        return VERSION;
    }

    private VersionUtil() {
        // Utility class
    }
}
