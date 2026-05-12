package ca.xnor.buoyanchor.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Live, observable simulation parameters. UI controls bind to these;
 * the physics step reads them at the top of each substep.
 *
 * The rode is described as an ordered list of {@link RodeSegment}s from the
 * anchor end (index 0) to the buoy end (last). Killicks may be attached at any
 * distance from the buoy along the full rode. The anchor is its own object
 * carrying mass, MDF, and holding factor.
 */
public class SimParams {

    public final ObjectProperty<BuoyPreset> buoy =
            new SimpleObjectProperty<>(BuoyPreset.TDL_025M_S);

    public final DoubleProperty windKnots     = new SimpleDoubleProperty(15.0);
    /** Autocorrelation time (s) for wind fluctuations — controls how "twitchy" the gusts feel
     *  and the window over which the actual wind averages back to the setpoint.
     *  ~20–40 s is typical for marine boundary layer; 30 s is a reasonable default. */
    public final DoubleProperty gustTauS      = new SimpleDoubleProperty(30.0);
    /** Wind variability as a fraction of the mean (1σ). Real marine BL turbulence intensity is
     *  ~10–20 %; 0.20 keeps gust factor (peak / mean ≈ 1 + 2.5σ/μ) at a credible ~1.5. */
    public static final double WIND_SIGMA_FRACTION = 0.20;
    public final DoubleProperty depthM        = new SimpleDoubleProperty(8.0);
    public final DoubleProperty fetchKm       = new SimpleDoubleProperty(50.0);
    public final DoubleProperty waterDensity  = new SimpleDoubleProperty(1000.0);
    public final DoubleProperty airDensity    = new SimpleDoubleProperty(1.22);

    /** Anchor end first, buoy end last. */
    public final ObservableList<RodeSegment> segments = FXCollections.observableArrayList(
            RodeSegment.ofGauge(RodeSegment.Kind.CHAIN, "1/4", 12.0),
            RodeSegment.ofGauge(RodeSegment.Kind.ROPE,  "1/2", 12.0));

    public final ObjectProperty<Anchor> anchor =
            new SimpleObjectProperty<>(new Anchor(Anchor.Kind.PYRAMID, 11.5, 0.85, 7.0));

    public final DoubleProperty bottomMu      = new SimpleDoubleProperty(0.6);

    /** When true, the SimView overlays net-force arrows at key points along the rode and buoy. */
    public final BooleanProperty showForces = new SimpleBooleanProperty(false);

    public final ObservableList<KillickSpec> killicks = FXCollections.observableArrayList(
            new KillickSpec(2.0, 3.0, 0.87),
            new KillickSpec(2.0, 6.0, 0.87),
            new KillickSpec(2.0, 9.0, 0.87),
            new KillickSpec(2.0, 12.0, 0.87));

    /** Sum of segment lengths. */
    public double totalRodeLengthM() {
        double s = 0;
        for (RodeSegment seg : segments) s += seg.lengthM.get();
        return s;
    }
}
