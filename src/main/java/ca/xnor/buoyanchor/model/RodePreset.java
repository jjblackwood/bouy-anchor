package ca.xnor.buoyanchor.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Named, immutable rode configuration loaded from YAML. Applying a preset to a SimParams replaces
 * its segment list with deep copies of these template segments.
 *
 * <p>Library sources: embedded {@code rodes/*.yaml} resources, overlaid by any file of the same
 * {@code displayName} found in {@link ConfigPaths#rodesDir()}.
 */
public final class RodePreset {

  private static final System.Logger LOG = System.getLogger(RodePreset.class.getName());

  public final String displayName;

  /** Immutable template segments, anchor → buoy. */
  public final List<RodeSegment> segments;

  public RodePreset(String displayName, List<RodeSegment> segments) {
    this.displayName = displayName;
    this.segments = List.copyOf(segments);
  }

  /** Fresh, mutable copies suitable for replacing SimParams.segments. */
  public List<RodeSegment> instantiate() {
    List<RodeSegment> out = new ArrayList<>(segments.size());
    for (RodeSegment s : segments) out.add(s.copy());
    return out;
  }

  @Override
  public String toString() {
    return displayName;
  }

  public static final List<RodePreset> LIBRARY = loadLibrary();

  public static RodePreset byName(String name) {
    for (RodePreset p : LIBRARY) if (p.displayName.equals(name)) return p;
    return null;
  }

  private static List<RodePreset> loadLibrary() {
    List<PresetLoader.Doc> docs = PresetLoader.loadAll("rodes", ConfigPaths.rodesDir());
    Map<String, RodePreset> byName = new LinkedHashMap<>();
    for (PresetLoader.Doc d : docs) {
      try {
        RodePreset p = fromMap(d.data);
        byName.put(p.displayName, p);
      } catch (IllegalArgumentException | ClassCastException | YAMLException e) {
        LOG.log(
            System.Logger.Level.WARNING, "Skipping rode YAML {0}: {1}", d.source, e.getMessage());
      }
    }
    return new ArrayList<>(byName.values());
  }

  @SuppressWarnings("unchecked")
  private static RodePreset fromMap(Map<String, Object> m) {
    Object nameObj = m.get("displayName");
    if (nameObj == null) throw new IllegalArgumentException("missing 'displayName'");
    Object segsObj = m.get("segments");
    if (!(segsObj instanceof List)) throw new IllegalArgumentException("missing 'segments' list");
    List<Map<String, Object>> raw = (List<Map<String, Object>>) segsObj;
    List<RodeSegment> segments = new ArrayList<>();
    for (Map<String, Object> sm : raw) {
      RodeSegment.Kind kind = parseKind(sm.get("kind"));
      double len = num(sm.get("lengthM"));
      String gauge = sm.get("gauge") != null ? sm.get("gauge").toString() : "";
      // If a gauge is given for chain/rope, look it up and ignore any explicit kg/m/mdf/diam.
      RodeSegment.Gauge g = RodeSegment.findGauge(kind, gauge);
      if (g != null) {
        segments.add(RodeSegment.ofGauge(kind, gauge, len));
      } else {
        double kg = numOpt(sm.get("kgPerM"), 0);
        double mdf = numOpt(sm.get("mdf"), kind == RodeSegment.Kind.CHAIN ? 0.85 : 0.12);
        double d = numOpt(sm.get("diameterM"), kind == RodeSegment.Kind.CHAIN ? 0.006 : 0.010);
        RodeSegment seg = new RodeSegment(kind, kind.name().toLowerCase(Locale.ROOT), len, kg, mdf);
        seg.diameterM.set(d);
        segments.add(seg);
      }
    }
    return new RodePreset(nameObj.toString(), segments);
  }

  private static RodeSegment.Kind parseKind(Object o) {
    if (o == null) return RodeSegment.Kind.CHAIN;
    return RodeSegment.Kind.valueOf(o.toString().trim().toUpperCase(Locale.ROOT));
  }

  private static double num(Object o) {
    if (o == null) throw new IllegalArgumentException("missing numeric field");
    if (o instanceof Number n) return n.doubleValue();
    return Double.parseDouble(o.toString());
  }

  private static double numOpt(Object o, double fallback) {
    if (o == null) return fallback;
    if (o instanceof Number n) return n.doubleValue();
    try {
      return Double.parseDouble(o.toString());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
