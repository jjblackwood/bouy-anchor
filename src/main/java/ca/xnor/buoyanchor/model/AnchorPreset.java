package ca.xnor.buoyanchor.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named, immutable anchor configuration loaded from YAML. Applying a preset to a SimParams
 * replaces its anchor with a fresh Anchor copy.
 */
public final class AnchorPreset {

    public final String displayName;
    public final Anchor template;

    public AnchorPreset(String displayName, Anchor template) {
        this.displayName = displayName;
        this.template = template;
    }

    public Anchor instantiate() { return template.copy(); }

    @Override public String toString() { return displayName; }

    public static final List<AnchorPreset> LIBRARY = loadLibrary();

    public static AnchorPreset byName(String name) {
        for (AnchorPreset p : LIBRARY) if (p.displayName.equals(name)) return p;
        return null;
    }

    private static List<AnchorPreset> loadLibrary() {
        List<PresetLoader.Doc> docs = PresetLoader.loadAll("anchors", ConfigPaths.anchorsDir());
        Map<String, AnchorPreset> byName = new LinkedHashMap<>();
        for (PresetLoader.Doc d : docs) {
            try {
                AnchorPreset p = fromMap(d.data);
                byName.put(p.displayName, p);
            } catch (RuntimeException e) {
                System.err.println("Skipping anchor YAML " + d.source + ": " + e.getMessage());
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static AnchorPreset fromMap(Map<String, Object> m) {
        Object nameObj = m.get("displayName");
        if (nameObj == null) throw new IllegalArgumentException("missing 'displayName'");
        Anchor.Kind kind = m.get("kind") != null
                ? Anchor.Kind.valueOf(m.get("kind").toString().trim().toUpperCase())
                : Anchor.Kind.OTHER;
        double mass = num(m.get("massKg"));
        double mdf  = num(m.get("mdf"));
        double hf   = num(m.get("holdingFactor"));
        return new AnchorPreset(nameObj.toString(), new Anchor(kind, mass, mdf, hf));
    }

    private static double num(Object o) {
        if (o == null) throw new IllegalArgumentException("missing numeric field");
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString());
    }
}
