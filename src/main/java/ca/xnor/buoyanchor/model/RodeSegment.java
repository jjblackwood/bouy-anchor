package ca.xnor.buoyanchor.model;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.List;

/**
 * One ordered section of the anchoring rode (between anchor and buoy). The rode is built as a
 * list of these — anchor end first, buoy end last.
 *
 * For chain and rope, a {@code gauge} string identifies a standard size in {@link #CHAIN_GAUGES}
 * / {@link #ROPE_GAUGES} and the corresponding linear density, line diameter, and MDF are
 * looked up from the table. For {@code OTHER} segments the gauge is empty and the user supplies
 * the per-meter mass, MDF, and diameter directly.
 */
public final class RodeSegment {

    public enum Kind { CHAIN, ROPE, OTHER }

    /** Standard gauge: a labelled commodity size with known specs. */
    public record Gauge(String label, double kgPerM, double diameterM, double mdf) {}

    // Galvanized proof-coil chain. Ordered ascending by wire / nominal diameter.
    public static final List<Gauge> CHAIN_GAUGES = List.of(
            new Gauge("5/32", 0.27, 0.00397, 0.85),
            new Gauge("3/16", 0.40, 0.00476, 0.85),
            new Gauge("1/4",  0.74, 0.00635, 0.85),
            new Gauge("5/16", 1.13, 0.00794, 0.85),
            new Gauge("3/8",  1.66, 0.00953, 0.85),
            new Gauge("1/2",  2.83, 0.01270, 0.85)
    );

    // Three-strand nylon rope. Ordered ascending by outer diameter.
    public static final List<Gauge> ROPE_GAUGES = List.of(
            new Gauge("1/8",  0.014, 0.00318, 0.12),
            new Gauge("3/8",  0.080, 0.00953, 0.12),
            new Gauge("1/2",  0.140, 0.01270, 0.12),
            new Gauge("5/8",  0.210, 0.01588, 0.12)
    );

    public static List<Gauge> gaugesFor(Kind k) {
        return switch (k) {
            case CHAIN -> CHAIN_GAUGES;
            case ROPE  -> ROPE_GAUGES;
            case OTHER -> List.of();
        };
    }

    public static Gauge findGauge(Kind k, String label) {
        if (label == null) return null;
        for (Gauge g : gaugesFor(k)) {
            if (g.label.equals(label)) return g;
        }
        return null;
    }

    public final ObjectProperty<Kind> kind   = new SimpleObjectProperty<>(Kind.CHAIN);
    public final StringProperty       name   = new SimpleStringProperty("");
    /** Gauge label, e.g. "1/4" for chain or rope. Empty when kind == OTHER. */
    public final StringProperty       gauge  = new SimpleStringProperty("");
    public final DoubleProperty       lengthM   = new SimpleDoubleProperty(0);
    public final DoubleProperty       kgPerM    = new SimpleDoubleProperty(0);
    public final DoubleProperty       mdf       = new SimpleDoubleProperty(0.85);
    /** Effective hydrodynamic diameter for drag. For chain this is the wire / nominal size. */
    public final DoubleProperty       diameterM = new SimpleDoubleProperty(0.010);

    public RodeSegment() {}

    /** Convenience: build a chain or rope segment by gauge label. Looks up specs from the tables. */
    public static RodeSegment ofGauge(Kind kind, String gaugeLabel, double lengthM) {
        Gauge g = findGauge(kind, gaugeLabel);
        if (g == null) throw new IllegalArgumentException(
                "Unknown gauge '" + gaugeLabel + "' for " + kind);
        RodeSegment s = new RodeSegment();
        s.kind.set(kind);
        s.gauge.set(gaugeLabel);
        s.name.set(kind.name().toLowerCase());
        s.lengthM.set(lengthM);
        s.kgPerM.set(g.kgPerM);
        s.mdf.set(g.mdf);
        s.diameterM.set(g.diameterM);
        return s;
    }

    /** Convenience: build an OTHER segment with explicit specs. */
    public static RodeSegment ofOther(double lengthM, double kgPerM, double mdf, double diameterM) {
        RodeSegment s = new RodeSegment();
        s.kind.set(Kind.OTHER);
        s.gauge.set("");
        s.lengthM.set(lengthM);
        s.kgPerM.set(kgPerM);
        s.mdf.set(mdf);
        s.diameterM.set(diameterM);
        return s;
    }

    /** Legacy constructor — assumes OTHER-style explicit specs (kind is honoured but no gauge
     *  lookup happens). Kept for tests and for loading old saved state. */
    public RodeSegment(Kind kind, double lengthM, double kgPerM, double mdf) {
        this(kind, kind.name().toLowerCase(), lengthM, kgPerM, mdf);
    }

    public RodeSegment(Kind kind, String name, double lengthM, double kgPerM, double mdf) {
        this.kind.set(kind);
        this.name.set(name);
        this.lengthM.set(lengthM);
        this.kgPerM.set(kgPerM);
        this.mdf.set(mdf);
        // Default diameter follows kind; user / loader may override.
        this.diameterM.set(switch (kind) {
            case CHAIN -> 0.006;
            case ROPE  -> 0.010;
            case OTHER -> 0.010;
        });
    }

    /** Apply a gauge to this segment, overwriting kg/m, MDF, and diameter from the table. Sets
     *  kind to match if needed. */
    public void applyGauge(String label) {
        Gauge g = findGauge(kind.get(), label);
        if (g == null) return;
        gauge.set(label);
        kgPerM.set(g.kgPerM);
        mdf.set(g.mdf);
        diameterM.set(g.diameterM);
    }

    public RodeSegment copy() {
        RodeSegment c = new RodeSegment(kind.get(), name.get(), lengthM.get(), kgPerM.get(), mdf.get());
        c.gauge.set(gauge.get());
        c.diameterM.set(diameterM.get());
        return c;
    }
}
