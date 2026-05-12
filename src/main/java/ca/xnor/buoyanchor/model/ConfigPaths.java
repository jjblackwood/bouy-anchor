package ca.xnor.buoyanchor.model;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * OS-appropriate user config directory for bouy-anchor. Subdirectories under this hold YAML
 * overrides for {@code marks/}, {@code rodes/}, {@code anchors/}, etc.
 */
public final class ConfigPaths {

  public static final String APP_NAME = "bouy-anchor";

  private ConfigPaths() {}

  /** Root config dir. Created lazily by the loader if needed. */
  public static Path appConfigDir() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String home = System.getProperty("user.home", ".");
    if (os.contains("win")) {
      String appData = System.getenv("APPDATA");
      if (appData == null || appData.isBlank()) appData = home;
      return Paths.get(appData, APP_NAME);
    }
    if (os.contains("mac") || os.contains("darwin")) {
      return Paths.get(home, "Library", "Application Support", APP_NAME);
    }
    // Linux / *nix: respect XDG_CONFIG_HOME.
    String xdg = System.getenv("XDG_CONFIG_HOME");
    if (xdg != null && !xdg.isBlank()) return Paths.get(xdg, APP_NAME);
    return Paths.get(home, ".config", APP_NAME);
  }

  public static Path marksDir() {
    return appConfigDir().resolve("marks");
  }

  public static Path rodesDir() {
    return appConfigDir().resolve("rodes");
  }

  public static Path anchorsDir() {
    return appConfigDir().resolve("anchors");
  }
}
