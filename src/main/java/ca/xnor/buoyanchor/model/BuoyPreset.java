package ca.xnor.buoyanchor.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Buoy preset library entry. Spar buoys are described as a stack of cylindrical sections from the
 * keel up; the buoyancy curve is derived from these by integrating displaced volume vs. submersion.
 *
 * <p>Library entries come from YAML files: embedded resources under {@code marks/} on the
 * classpath, plus an optional user override directory at {@link ConfigPaths#marksDir()}. User-side
 * files override embedded ones by matching {@code displayName}.
 */
public final class BuoyPreset {

  private static final System.Logger LOG = System.getLogger(BuoyPreset.class.getName());

  public static final double LB_TO_KG = 0.45359237;
  public static final double IN_TO_M = 0.0254;

  /** A cylindrical slice of the buoy, measured bottom-up. */
  public record Section(double heightM, double diameterM) {}

  public final String displayName;
  public final double dryMassKg; // dry mass (manufacturer or measured)
  public final double sinkingWeightKg; // SW: full-submersion buoyancy
  public final double overallHeightM; // including any topmark/tower
  public final List<Section> sections; // bottom-up; sum of heights ≈ overallHeightM
  public final double draughtAtMinMooringM; // for default visual placement
  public final double ballastMassKg; // concentrated mass (internal ballast lump)
  public final double ballastHeightAboveKeelM; // height of the ballast lump's CG
  public final double attachAboveKeelM; // mooring eye height above keel
  public final boolean swEstimated; // if true, UI shows "(SW est.)"

  public BuoyPreset(
      String displayName,
      double dryMassKg,
      double sinkingWeightKg,
      double overallHeightM,
      List<Section> sections,
      double draughtAtMinMooringM,
      double ballastMassKg,
      double ballastHeightAboveKeelM,
      double attachAboveKeelM,
      boolean swEstimated) {
    this.displayName = displayName;
    this.dryMassKg = dryMassKg;
    this.sinkingWeightKg = sinkingWeightKg;
    this.overallHeightM = overallHeightM;
    this.sections = List.copyOf(sections);
    this.draughtAtMinMooringM = draughtAtMinMooringM;
    this.ballastMassKg = ballastMassKg;
    this.ballastHeightAboveKeelM = ballastHeightAboveKeelM;
    this.attachAboveKeelM = attachAboveKeelM;
    this.swEstimated = swEstimated;
  }

  /** Maximum diameter of any section (used for tilt cos-area calc, etc.). */
  public double maxDiameterM() {
    double d = 0;
    for (Section s : sections) d = Math.max(d, s.diameterM);
    return d;
  }

  /**
   * Mass of the shell/foam: total dry minus the ballast lump. Distributed proportionally to section
   * volumes (foam fills the inside; shell wraps it; both scale with volume).
   */
  public double shellMassKg() {
    return Math.max(0, dryMassKg - ballastMassKg);
  }

  /** Composite CG height above keel, computed from ballast + shell distribution. */
  public double cgAboveKeelM() {
    double shellMass = shellMassKg();
    double totalVol = 0;
    for (Section s : sections) {
      totalVol += Math.PI * Math.pow(s.diameterM() / 2.0, 2) * s.heightM();
    }
    double shellMoment = 0;
    if (totalVol > 0) {
      double yOff = 0;
      for (Section s : sections) {
        double vol = Math.PI * Math.pow(s.diameterM() / 2.0, 2) * s.heightM();
        double sectionCg = yOff + s.heightM() / 2.0;
        shellMoment += (shellMass * vol / totalVol) * sectionCg;
        yOff += s.heightM();
      }
    }
    double ballastMoment = ballastMassKg * ballastHeightAboveKeelM;
    return (ballastMoment + shellMoment) / Math.max(1e-6, dryMassKg);
  }

  @Override
  public String toString() {
    return swEstimated ? displayName + "  (SW est.)" : displayName;
  }

  // -------------------- Library --------------------

  public static final List<BuoyPreset> LIBRARY = loadLibrary();

  /** First preset whose displayName matches, or null. */
  public static BuoyPreset byName(String name) {
    for (BuoyPreset p : LIBRARY) if (p.displayName.equals(name)) return p;
    return null;
  }

  // Convenience handles for code that still references the well-known marks. These resolve
  // out of the YAML library at class init.
  public static final BuoyPreset TDL_025M_S = required("TDL-0.25M-S");
  public static final BuoyPreset TDL_030M_S = required("TDL-0.30M-S");
  public static final BuoyPreset SUR_MARK = required("Sur-Mark");

  private static BuoyPreset required(String name) {
    BuoyPreset p = byName(name);
    if (p == null) {
      throw new IllegalStateException(
          "Required mark preset not found: " + name + " (check src/main/resources/marks/)");
    }
    return p;
  }

  /** Parse all YAML mark files (embedded + user config) into a stable, override-aware list. */
  private static List<BuoyPreset> loadLibrary() {
    List<PresetLoader.Doc> docs = PresetLoader.loadAll("marks", ConfigPaths.marksDir());
    Map<String, BuoyPreset> byName = new LinkedHashMap<>();
    for (PresetLoader.Doc d : docs) {
      try {
        BuoyPreset p = fromMap(d.data);
        byName.put(p.displayName, p);
      } catch (IllegalArgumentException | ClassCastException | YAMLException e) {
        LOG.log(
            System.Logger.Level.WARNING, "Skipping mark YAML {0}: {1}", d.source, e.getMessage());
      }
    }
    return new ArrayList<>(byName.values());
  }

  @SuppressWarnings("unchecked")
  private static BuoyPreset fromMap(Map<String, Object> m) {
    String displayName = requireString(m, "displayName");
    double dryMass = requireDouble(m, "dryMassKg");
    double sw = requireDouble(m, "sinkingWeightKg");
    double overallH = requireDouble(m, "overallHeightM");
    List<Map<String, Object>> sectionsRaw = (List<Map<String, Object>>) m.get("sections");
    if (sectionsRaw == null || sectionsRaw.isEmpty()) {
      throw new IllegalArgumentException("missing 'sections'");
    }
    List<Section> sections = new ArrayList<>();
    for (Map<String, Object> sm : sectionsRaw) {
      sections.add(new Section(requireDouble(sm, "heightM"), requireDouble(sm, "diameterM")));
    }
    double draught = requireDouble(m, "draughtAtMinMooringM");
    double ballast = requireDouble(m, "ballastMassKg");
    double ballastH = requireDouble(m, "ballastHeightAboveKeelM");
    double attach = optDouble(m, "attachAboveKeelM", 0.0);
    boolean estimated = m.get("swEstimated") instanceof Boolean b && b;
    return new BuoyPreset(
        displayName,
        dryMass,
        sw,
        overallH,
        sections,
        draught,
        ballast,
        ballastH,
        attach,
        estimated);
  }

  private static String requireString(Map<String, Object> m, String k) {
    Object v = m.get(k);
    if (v == null) throw new IllegalArgumentException("missing '" + k + "'");
    return v.toString();
  }

  private static double requireDouble(Map<String, Object> m, String k) {
    Object v = m.get(k);
    if (v == null) throw new IllegalArgumentException("missing '" + k + "'");
    if (v instanceof Number n) return n.doubleValue();
    return Double.parseDouble(v.toString());
  }

  private static double optDouble(Map<String, Object> m, String k, double fallback) {
    Object v = m.get(k);
    if (v == null) return fallback;
    if (v instanceof Number n) return n.doubleValue();
    return Double.parseDouble(v.toString());
  }
}
