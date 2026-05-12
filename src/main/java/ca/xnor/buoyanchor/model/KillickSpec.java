package ca.xnor.buoyanchor.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;

/** A single killick (heavy weight) attached along the rode at a specified distance from one end.
 *  When {@code fromAnchor} is false the distance is measured from the buoy down the rode; when
 *  true it's measured from the anchor up the rode. The simulation realises the killick as extra
 *  mass on the nearest rode node — it may land on any segment kind (chain, rope, or other). Each
 *  killick carries its own MDF. */
public final class KillickSpec {

    public final DoubleProperty  massKg     = new SimpleDoubleProperty(5.0);
    public final DoubleProperty  distM      = new SimpleDoubleProperty(3.0);
    public final BooleanProperty fromAnchor = new SimpleBooleanProperty(false);
    public final DoubleProperty  mdf        = new SimpleDoubleProperty(0.87);

    public KillickSpec() {}

    public KillickSpec(double massKg, double distM) {
        this.massKg.set(massKg);
        this.distM.set(distM);
    }

    public KillickSpec(double massKg, double distM, double mdf) {
        this(massKg, distM);
        this.mdf.set(mdf);
    }

    public KillickSpec(double massKg, double distM, double mdf, boolean fromAnchor) {
        this(massKg, distM, mdf);
        this.fromAnchor.set(fromAnchor);
    }
}
