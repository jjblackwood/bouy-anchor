package ca.xnor.buoyanchor.ui;

import ca.xnor.buoyanchor.model.BuoyPreset;
import ca.xnor.buoyanchor.model.SimParams;
import ca.xnor.buoyanchor.sim.Buoy;
import ca.xnor.buoyanchor.sim.Rode;
import ca.xnor.buoyanchor.sim.Simulation;
import ca.xnor.buoyanchor.sim.Waves;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Resizable Canvas + AnimationTimer. Runs physics substeps and redraws
 * each frame on the JavaFX Application Thread.
 */
public class SimView extends Pane {

    private final Canvas canvas = new Canvas(1000, 720);
    private final SimParams params;
    public final Simulation sim = new Simulation();
    private ReadoutPanel readout;
    private AnimationTimer timer;
    private long lastNanos = 0L;

    private static final double PHYSICS_DT = 1.0 / 240.0;
    private double accumulator = 0.0;
    private double simTime = 0.0;

    // Cached per-draw transform (world y-up metres → screen pixels).
    private double pxPerM;
    private double originXpx;   // screen x at world x = 0
    private double surfaceYpx;  // screen y at world y = 0
    private double seabedYpx;   // screen y at world y = -depth

    public SimView(SimParams params) {
        this.params = params;
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((o, a, b) -> draw());
        heightProperty().addListener((o, a, b) -> draw());
    }

    public void setReadout(ReadoutPanel r) { this.readout = r; }

    public void start() {
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                try {
                    if (lastNanos == 0L) {
                        lastNanos = now;
                        return;
                    }
                    double dt = Math.min((now - lastNanos) * 1e-9, 0.25);
                    lastNanos = now;
                    accumulator += dt;
                    int steps = 0;
                    while (accumulator >= PHYSICS_DT && steps < 8) {
                        step(PHYSICS_DT);
                        accumulator -= PHYSICS_DT;
                        steps++;
                    }
                    draw();
                    if (readout != null) readout.refresh();
                } catch (Throwable t) {
                    // JavaFX silently swallows AnimationTimer exceptions, which can leave the
                    // canvas frozen with nothing visibly wrong. Log it loudly.
                    System.err.println("SimView frame failed: " + t);
                    t.printStackTrace();
                }
            }
        };
        timer.start();
    }

    private void step(double dt) {
        simTime += dt;
        sim.step(params, dt);
    }

    /** Compute world-to-screen mapping based on canvas size, depth, and the rough horizontal extent. */
    private void updateTransform(double w, double h) {
        double depth = Math.max(0.5, params.depthM.get());
        double rodeTotal = params.totalRodeLengthM();
        // Horizontal world span we want visible (anchor at ~10% from left, rest of the field on the right).
        double worldSpanX = Math.max(8.0, rodeTotal * 1.15);
        // Vertical world span: depth below surface + a bit of air above.
        double airAbove = 1.5;
        double worldSpanY = depth + airAbove + 1.0;

        double leftMargin = 40, rightMargin = 30, topMargin = 30, botMargin = 30;
        double pxPerMx = (w - leftMargin - rightMargin) / worldSpanX;
        double pxPerMy = (h - topMargin - botMargin) / worldSpanY;
        pxPerM = Math.min(pxPerMx, pxPerMy);

        originXpx  = leftMargin + 0.05 * (w - leftMargin - rightMargin);
        surfaceYpx = topMargin  + airAbove * pxPerM;
        seabedYpx  = surfaceYpx + depth * pxPerM;
    }

    private double sx(double xWorld) { return originXpx + xWorld * pxPerM; }
    private double sy(double yWorld) { return surfaceYpx - yWorld * pxPerM; }

    /** Local water surface elevation in world metres at world x, accounting for waves.
     *  Uses sim.wavePhase (continuously evolving) so changes to Tp don't visually warp the waves. */
    private double surfaceElevation(double xWorld, Waves waves) {
        return sim.waveElevation(xWorld, params.depthM.get());
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        updateTransform(w, h);

        Waves waves = sim.waves;

        // Sky covers the whole top.
        g.setFill(Color.web("#dff0fa"));
        g.fillRect(0, 0, w, h);

        // Water polygon with a wavy top edge sampled from the same wave function the buoy uses.
        int samples = Math.max(2, (int) (w / 3));
        double[] xs = new double[samples + 3];
        double[] ys = new double[samples + 3];
        for (int i = 0; i <= samples; i++) {
            double xPx = i * w / samples;
            double xWorld = (xPx - originXpx) / pxPerM;
            double yWorld = surfaceElevation(xWorld, waves);
            xs[i] = xPx;
            ys[i] = sy(yWorld);
        }
        xs[samples + 1] = w; ys[samples + 1] = seabedYpx;
        xs[samples + 2] = 0; ys[samples + 2] = seabedYpx;
        g.setFill(Color.web("#5a9fc7"));
        g.fillPolygon(xs, ys, samples + 3);

        // Seabed.
        g.setFill(Color.web("#8a6f4a"));
        g.fillRect(0, seabedYpx, w, h - seabedYpx);

        // Calm waterline as a faint dashed reference.
        g.setStroke(Color.web("#2a5a78", 0.35));
        g.setLineWidth(1);
        g.setLineDashes(4, 4);
        g.strokeLine(0, surfaceYpx, w, surfaceYpx);
        g.setLineDashes(null);

        // Rode polyline. Render each link based on the segment kind of its anchor-ward node.
        Rode r = sim.rode;
        if (r.n > 1) {
            for (int i = 0; i < r.n - 1; i++) {
                ca.xnor.buoyanchor.model.RodeSegment.Kind k = r.kind[i + 1] != null ? r.kind[i + 1] : r.kind[i];
                switch (k) {
                    case CHAIN -> { g.setStroke(Color.web("#2e2e2e")); g.setLineWidth(3.5); }
                    case ROPE  -> { g.setStroke(Color.web("#c9a877")); g.setLineWidth(2.0); }
                    case OTHER -> { g.setStroke(Color.web("#5a8a5a")); g.setLineWidth(2.5); }
                    case null  -> { g.setStroke(Color.web("#2e2e2e")); g.setLineWidth(2.0); }
                }
                g.strokeLine(sx(r.x[i]), sy(r.y[i]), sx(r.x[i + 1]), sy(r.y[i + 1]));
            }

            // Anchor: shape depends on the configured kind. The rode attaches at (anchorX, anchorY),
            // which sits at the seabed surface; the body of the anchor extends below into the bed.
            // Color it red when the chain pull would lift or drag it, so we can see at a glance
            // whether the rode is exceeding the anchor's hold.
            drawAnchor(g, r.anchorX, r.anchorY, anchorOverloaded(r));

            // Killick markers anywhere along the rode.
            g.setFill(Color.web("#3a3a3a"));
            for (int i = 1; i < r.n - 1; i++) {
                if (r.killick[i]) {
                    double kx = sx(r.x[i]), ky = sy(r.y[i]);
                    g.fillOval(kx - 5, ky - 5, 10, 10);
                }
            }

            // Buoy.
            drawBuoy(g, params.buoy.get(), sim.buoy);

            // Optional force overlay.
            if (params.showForces.get()) drawForces(g, r);
        }

        // Legend now lives in ReadoutPanel on the left side, not on the canvas.
    }

    /** Overlay net-force arrows at key points: anchor, every ~3 m along chain & rope (plus killicks),
     *  buoy bottom (line tension), buoy top (wind drag). Magnitudes are physically meaningful;
     *  arrow lengths are scaled so a typical 100 N renders ~50 px. */
    private void drawForces(GraphicsContext g, Rode r) {
        // Pixel-per-Newton for arrow length; tweak to keep arrows readable.
        double pxPerN = 0.6;

        // Pre-compute cumulative suspended (in-water) weight from the buoy down toward the anchor,
        // so we know the vertical tension at each node. Below the suspended region (nodes on the
        // seabed), tension drops off because weight goes into bottom contact rather than line pull.
        double[] vertTension = new double[r.n];
        double cum = 0;
        for (int i = r.n - 1; i >= 0; i--) {
            // -accelY[i] * mass[i] = downward in-water weight (gravity term only).
            boolean suspended = r.y[i] > r.seabedY + 0.02;
            if (suspended) cum += -r.accelY[i] * r.mass[i];
            vertTension[i] = cum;
        }
        double horizPull = sim.rode.topConstraintFx;

        // Helper to draw an arrow at world (wx,wy) for vector (fx,fy) Newtons.
        // colour, label optional.
        java.util.function.DoubleSupplier zero = () -> 0;

        // 1) Buoy bottom — line tension reaction on the buoy (= rode top-node constraint force).
        drawArrow(g, sim.buoy.attachWorldX(), sim.buoy.attachWorldY(),
                  sim.rode.topConstraintFx, sim.rode.topConstraintFy, pxPerN,
                  "#1a5fa8", "Fline");

        // 2) Buoy top — net wind drag.
        // Approximate world position of wind force application: above-water midpoint along body.
        double topAxisH = sim.buoy.preset.overallHeightM;     // approximate top
        double midEmergedAxis = Math.max(0, (topAxisH + (-sim.buoy.y)) / 2.0);  // halfway above water
        double sinT = Math.sin(sim.buoy.theta);
        double cosT = Math.cos(sim.buoy.theta);
        double topWX = sim.buoy.x + midEmergedAxis * sinT;
        double topWY = sim.buoy.y + midEmergedAxis * cosT;
        drawArrow(g, topWX, topWY, sim.buoy.Fwind, 0, pxPerN, "#c44a1f", "Fwind");

        // 3) Anchor — chain pull on the anchor (= tension at node 0, pointing from anchor toward node 1).
        if (r.n > 1) {
            double dx = r.x[1] - r.x[0];
            double dy = r.y[1] - r.y[0];
            double dist = Math.hypot(dx, dy);
            if (dist > 1e-6) {
                double T = Math.hypot(horizPull, vertTension[0]);
                double fxA = T * dx / dist;
                double fyA = T * dy / dist;
                drawArrow(g, r.x[0], r.y[0], fxA, fyA, pxPerN, "#1c1c1c", "Fanchor");
            }
        }

        // 4) Tension marker every ~3 m along the rode + at each killick.
        int stepNodes = Math.max(1, (int) Math.round(3.0 / Rode.TARGET_LINK_M));
        java.util.Set<Integer> marks = new java.util.TreeSet<>();
        for (int i = 1; i < r.n - 1; i++) {
            if (r.killick[i]) marks.add(i);
        }
        for (int i = stepNodes; i < r.n - 1; i += stepNodes) marks.add(i);
        for (int i : marks) {
            String col = switch (r.kind[i] == null ? ca.xnor.buoyanchor.model.RodeSegment.Kind.CHAIN : r.kind[i]) {
                case CHAIN -> "#2e2e2e";
                case ROPE  -> "#8a5a25";
                case OTHER -> "#3a6a3a";
            };
            drawTensionAt(g, r, i, vertTension[i], horizPull, pxPerN, col);
        }
    }

    private void drawTensionAt(GraphicsContext g, Rode r, int i, double Tv, double Th,
                               double pxPerN, String colourHex) {
        if (i <= 0 || i >= r.n - 1) return;
        double dx = r.x[i + 1] - r.x[i];
        double dy = r.y[i + 1] - r.y[i];
        double dist = Math.hypot(dx, dy);
        if (dist < 1e-6) return;
        double T = Math.hypot(Th, Tv);
        double fx = T * dx / dist;
        double fy = T * dy / dist;
        drawArrow(g, r.x[i], r.y[i], fx, fy, pxPerN, colourHex, null);
    }

    /** Draw a force arrow from world (wx, wy) of magnitude (fx, fy) Newtons. */
    private void drawArrow(GraphicsContext g, double wx, double wy, double fx, double fy,
                           double pxPerN, String colourHex, String label) {
        double mag = Math.hypot(fx, fy);
        if (mag < 0.5) return;     // skip near-zero forces (visual noise)
        double sxp = sx(wx);
        double syp = sy(wy);
        // Arrow direction in screen pixels (world y is up; screen y is down).
        double dxPx = fx * pxPerN;
        double dyPx = -fy * pxPerN;
        // Cap arrow length so big forces don't dominate the canvas.
        double maxLen = 120;
        double lenPx = Math.hypot(dxPx, dyPx);
        if (lenPx > maxLen) { dxPx *= maxLen / lenPx; dyPx *= maxLen / lenPx; lenPx = maxLen; }
        double ex = sxp + dxPx;
        double ey = syp + dyPx;
        g.setStroke(Color.web(colourHex));
        g.setLineWidth(1.8);
        g.strokeLine(sxp, syp, ex, ey);
        // Arrowhead
        if (lenPx > 6) {
            double angle = Math.atan2(dyPx, dxPx);
            double ahLen = 6;
            double a1 = angle - Math.PI * 0.85;
            double a2 = angle + Math.PI * 0.85;
            g.strokeLine(ex, ey, ex + ahLen * Math.cos(a1), ey + ahLen * Math.sin(a1));
            g.strokeLine(ex, ey, ex + ahLen * Math.cos(a2), ey + ahLen * Math.sin(a2));
        }
        if (label != null) {
            g.setFill(Color.web(colourHex));
            g.setFont(javafx.scene.text.Font.font("Monospaced", 10));
            g.fillText(String.format("%s %.0fN", label, mag), ex + 4, ey - 2);
        } else {
            g.setFill(Color.web(colourHex));
            g.setFont(javafx.scene.text.Font.font("Monospaced", 9));
            g.fillText(String.format("%.0fN", mag), ex + 3, ey - 2);
        }
    }

    /** Render the anchor body at world (ax, ay). The mooring attach point sits exactly at
     *  (ax, ay) on the seabed; the body of the anchor extends a short way below the bed and
     *  takes a shape characteristic of the chosen anchor kind. */
    private void drawAnchor(GraphicsContext g, double axW, double ayW, boolean overloaded) {
        double ax = sx(axW), ay = sy(ayW);
        ca.xnor.buoyanchor.model.Anchor.Kind k = params.anchor.get().kind.get();
        Color body = overloaded ? Color.web("#c81e1e") : Color.web("#1c1c1c");
        g.setFill(body);
        switch (k) {
            case PYRAMID -> {
                // Square base faces up (sits on / partly buried in the seabed); apex points down
                // into the bed. Rode attaches at the centre of the top face — that's (ax, ay).
                double baseHalf = 0.30 * pxPerM;
                double depth    = 0.40 * pxPerM;
                g.fillPolygon(
                        new double[]{ax - baseHalf, ax + baseHalf, ax},
                        new double[]{ay,            ay,            ay + depth},
                        3);
            }
            case BLOCK -> {
                double half  = 0.32 * pxPerM;
                double depth = 0.32 * pxPerM;
                g.fillRect(ax - half, ay, 2 * half, depth);
            }
            case MUSHROOM -> {
                // Cap is buried in the seabed (the bowl that scoops sediment); stem stands up out
                // of the bed and the rode attaches to its top end — that's (ax, ay).
                double capHalf = 0.32 * pxPerM;
                double capH    = 0.10 * pxPerM;
                double stemHalf= 0.05 * pxPerM;
                double stemH   = 0.28 * pxPerM;
                // Stem rising from the seabed up to the attach point at (ax, ay).
                g.fillRect(ax - stemHalf, ay, 2 * stemHalf, stemH);
                // Dome cap below the bed, opening downward. Draw the visible upper sliver where the
                // cap rim meets the seabed surface, plus the buried portion.
                int N = 14;
                double[] xs = new double[N + 1];
                double[] ys = new double[N + 1];
                double cx = ax;
                double cy = ay + stemH;          // top of cap = where stem meets cap (just below bed)
                // Lower half (buried) of the dome — bowl shape opening down.
                for (int i = 0; i <= N; i++) {
                    double t = Math.PI * i / N;  // 0..π
                    xs[i] = cx + capHalf * Math.cos(t);
                    ys[i] = cy + capH * Math.sin(t);
                }
                g.fillPolygon(xs, ys, N + 1);
            }
            case OTHER -> {
                // Sphere — half above the bed, half below.
                double radius = 0.28 * pxPerM;
                g.fillOval(ax - radius, ay - radius, 2 * radius, 2 * radius);
            }
        }
        // Mooring ring at the attach point on the seabed.
        g.setStroke(body);
        g.setLineWidth(1.5);
        g.strokeOval(ax - 4, ay - 4, 8, 8);
    }

    /** True if the live anchor pull would lift the anchor or exceed its horizontal holding force.
     *  Pulled-upward = anchorPullFy exceeds the anchor's submerged weight; dragging = the
     *  horizontal pull exceeds mass × g × holdingFactor. The thresholds match the figures the
     *  Analysis report uses. */
    private boolean anchorOverloaded(Rode r) {
        ca.xnor.buoyanchor.model.Anchor a = params.anchor.get();
        double g = 9.81;
        double anchorMass = a.massKg.get();
        double holdingN   = anchorMass * g * a.holdingFactor.get();
        double submergedN = anchorMass * g * a.mdf.get();
        boolean lifts = r.anchorPullFy > submergedN;
        boolean drags = Math.abs(r.anchorPullFx) > holdingN;
        return lifts || drags;
    }

    /** Draw the buoy with rotation. Uses the simulation's actual buoy state. */
    private void drawBuoy(GraphicsContext g, BuoyPreset preset, Buoy buoy) {
        double kxScreen = sx(buoy.x);
        double kyScreen = sy(buoy.y);
        g.save();
        g.translate(kxScreen, kyScreen);
        g.rotate(Math.toDegrees(buoy.theta));   // +theta = clockwise visually (matches our convention)

        double yOff = 0;
        for (BuoyPreset.Section s : preset.sections) {
            double dPx = s.diameterM() * pxPerM;
            double hPx = s.heightM() * pxPerM;
            double yTopLocal = -(yOff + s.heightM()) * pxPerM;     // sections go up = -y in screen
            g.setFill(Color.web("#f5d000"));        // safety yellow
            g.fillRect(-dPx / 2.0, yTopLocal, dPx, hPx);
            g.setStroke(Color.web("#8a7300"));
            g.setLineWidth(1.0);
            g.strokeRect(-dPx / 2.0, yTopLocal, dPx, hPx);
            yOff += s.heightM();
        }

        // Mooring eye at keel.
        g.setStroke(Color.web("#1c1c1c"));
        g.setLineWidth(1.5);
        g.strokeOval(-3, -3, 6, 6);

        // CG marker at the segment-based CG height (along body axis).
        double cgPxLocal = -buoy.cgAboveKeelM * pxPerM;
        g.setLineWidth(1.0);
        g.strokeOval(-5, cgPxLocal - 5, 10, 10);
        g.strokeLine(-5, cgPxLocal, 5, cgPxLocal);
        g.strokeLine(0, cgPxLocal - 5, 0, cgPxLocal + 5);

        g.restore();
    }
}
