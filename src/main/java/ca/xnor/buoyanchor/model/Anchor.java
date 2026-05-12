package ca.xnor.buoyanchor.model;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * Anchor at the seabed end of the rode. The kind is informational/visual; what matters
 * to the physics is massKg (for both dry-weight and holding-capacity calculations), mdf
 * (in-water weight fraction), and holdingFactor (× weight in N = max horizontal pull
 * before drag).
 */
public final class Anchor {

    public enum Kind { PYRAMID, BLOCK, MUSHROOM, OTHER }

    public final ObjectProperty<Kind> kind         = new SimpleObjectProperty<>(Kind.BLOCK);
    public final DoubleProperty       massKg        = new SimpleDoubleProperty(11.5);
    public final DoubleProperty       mdf           = new SimpleDoubleProperty(0.87);
    public final DoubleProperty       holdingFactor = new SimpleDoubleProperty(7.0);

    public Anchor() {}

    public Anchor(Kind kind, double massKg, double mdf, double holdingFactor) {
        this.kind.set(kind);
        this.massKg.set(massKg);
        this.mdf.set(mdf);
        this.holdingFactor.set(holdingFactor);
    }

    public Anchor copy() {
        return new Anchor(kind.get(), massKg.get(), mdf.get(), holdingFactor.get());
    }
}
