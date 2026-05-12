package ca.xnor.buoyanchor.ui;

import ca.xnor.buoyanchor.model.BuoyPreset;
import ca.xnor.buoyanchor.model.SimParams;
import ca.xnor.buoyanchor.sim.Buoy;
import ca.xnor.buoyanchor.sim.Rode;
import ca.xnor.buoyanchor.sim.Simulation;
import ca.xnor.buoyanchor.sim.Waves;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

/** Live readout — laid out horizontally below the canvas so the viewport stays wide. */
public class ReadoutPanel extends HBox {

    private final SimParams params;
    private final Simulation sim;

    private final Label markLine    = new Label();
    private final Label massLine    = new Label();
    private final Label cgLine      = new Label();
    private final Label windLine    = new Label();
    private final Label depthLine   = new Label();
    private final Label hsLine      = new Label();
    private final Label orbLine     = new Label();
    private final Label rodeLine    = new Label();
    private final Label nodesLine   = new Label();
    private final Label buoyLine    = new Label();
    private final Label tiltLine    = new Label();
    private final Label fwindLine   = new Label();
    private final Label fwaveLine   = new Label();
    private final Label flineLine   = new Label();

    public ReadoutPanel(SimParams params, Simulation sim) {
        this.params = params;
        this.sim = sim;
        setPadding(new Insets(8, 12, 8, 12));
        setSpacing(24);

        Font mono = Font.font("Monospaced", 11);
        for (Label l : new Label[]{markLine, massLine, cgLine, windLine, depthLine, hsLine, orbLine,
                                   rodeLine, nodesLine, buoyLine, tiltLine, fwindLine, fwaveLine, flineLine}) {
            l.setFont(mono);
        }

        // Tooltips spell out the abbreviations and units used in the lines themselves so the
        // monospaced lines can stay compact.
        tip(massLine,  "Dry mass / ballast mass / ballast height above keel.");
        tip(cgLine,    "Centre of gravity above the keel, and the number of axial segments the buoy is sliced into.");
        tip(windLine,  "Instantaneous wind (kt) — the buoy actually sees this — then the slewed mean and the user setpoint it's slewing toward.");
        tip(depthLine, "Water depth and effective fetch (the upwind distance over which waves develop).");
        tip(hsLine,    "Hs = significant wave height (mean of the highest 1/3 of waves, m).\n"
                     + "Tp = peak wave period (period of the dominant spectral component, s).");
        tip(orbLine,   "u_orb = surface orbital velocity amplitude (m/s) of water particles under the waves.\n"
                     + "d_dsgn = design depth used by the Bretschneider depth-limited wave model (m). "
                     + "Not buoy drift — see the State column's keel position for horizontal offset from the anchor.");
        tip(rodeLine,  "Rode segment composition, anchor end → buoy end.");
        tip(nodesLine, "Total rode length, number of Verlet/PBD nodes the rode is discretised into, plus anchor.");
        tip(buoyLine,  "drift = horizontal distance of the buoy keel from the anchor (m, +x downwind).\n"
                     + "keel y = vertical position of the keel in world coords (y-up, seabed at the depth setting).");
        tip(tiltLine,  "Buoy tilt from vertical. +θ leans the body axis downwind.");
        tip(fwindLine, "Net wind drag force on the buoy (N).");
        tip(fwaveLine, "Net wave-related force (Morison inertial + wave-orbital drag) on the buoy (N).");
        tip(flineLine, "Rode reaction on the buoy: horizontal Fx, vertical Fy, and total tension |T| (N).");

        getChildren().addAll(
                column("Mark",        markLine, massLine, cgLine),
                column("Environment", windLine, depthLine, hsLine, orbLine),
                column("Rode",        rodeLine, nodesLine),
                column("State",       buoyLine, tiltLine, fwindLine, fwaveLine, flineLine));
    }

    private static void tip(Label l, String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(javafx.util.Duration.millis(250));
        t.setWrapText(true);
        t.setMaxWidth(360);
        Tooltip.install(l, t);
    }

    private static VBox column(String header, Label... lines) {
        Label h = new Label(header);
        h.setStyle("-fx-font-weight: bold;");
        VBox col = new VBox(2);
        col.getChildren().add(h);
        col.getChildren().addAll(lines);
        HBox.setHgrow(col, Priority.SOMETIMES);
        col.setMinWidth(Region.USE_PREF_SIZE);
        return col;
    }

    /** Pull current state from sim/params and refresh labels. Call from the AnimationTimer. */
    public void refresh() {
        BuoyPreset preset = params.buoy.get();
        Buoy bu = sim.buoy;
        Rode r = sim.rode;
        Waves waves = sim.waves;

        // Width specifiers everywhere so labels don't visibly twitch as numbers grow a digit.
        // Combined with a monospaced font and fixed column prefWidth, every column edge stays put.
        markLine.setText(preset.toString());
        massLine.setText(String.format("%5.1f kg dry, %5.1f kg ballast @%3.0f cm",
                preset.dryMassKg, preset.ballastMassKg, preset.ballastHeightAboveKeelM * 100));
        cgLine.setText(String.format("CG %3.0f cm,  %2d segs", bu.cgAboveKeelM * 100, bu.segments.size()));

        double instantKt = sim.actualWindKnots;
        double meanKt    = sim.meanWindKnots;
        windLine.setText(String.format("wind  %4.1f kt  (mean %4.1f → %4.1f)",
                instantKt, meanKt, params.windKnots.get()));
        depthLine.setText(String.format("depth %5.2f m   fetch %4.0f km",
                params.depthM.get(), params.fetchKm.get()));
        hsLine.setText(String.format("Hs    %5.2f m   Tp    %5.2f s", waves.hs, waves.tp));
        orbLine.setText(String.format("u_orb %5.2f m/s   d_dsgn %5.2f m",
                waves.uOrb, waves.designDepth));

        StringBuilder seg = new StringBuilder();
        for (int i = 0; i < params.segments.size(); i++) {
            ca.xnor.buoyanchor.model.RodeSegment s = params.segments.get(i);
            seg.append(String.format("%s %.1fm", s.kind.get().name().toLowerCase(), s.lengthM.get()));
            if (i < params.segments.size() - 1) seg.append(" + ");
        }
        rodeLine.setText(seg.toString());
        nodesLine.setText(String.format("total %5.1f m,  %3d nodes   anchor %s %5.1f kg",
                params.totalRodeLengthM(), r.n,
                params.anchor.get().kind.get(), params.anchor.get().massKg.get()));

        // Anchor sits at world x = 0, so the keel's x coordinate is the buoy's horizontal drift
        // from the anchor; y is keel height in world (y-up) coordinates.
        buoyLine.setText(String.format("drift %+6.2f m  keel y %+6.2f m", bu.x, bu.y));
        tiltLine.setText(String.format("tilt %+5.1f°", Math.toDegrees(bu.theta)));
        fwindLine.setText(String.format("Fwind %+7.1f N", bu.Fwind));
        fwaveLine.setText(String.format("Fwave %+7.1f N", bu.Fwave));
        flineLine.setText(String.format("Fline (%+7.1f, %+7.1f) N  |T|=%6.1f",
                r.topConstraintFx, r.topConstraintFy,
                Math.hypot(r.topConstraintFx, r.topConstraintFy)));
    }
}
