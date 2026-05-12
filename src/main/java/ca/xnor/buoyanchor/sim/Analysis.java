package ca.xnor.buoyanchor.sim;

import ca.xnor.buoyanchor.model.Anchor;
import ca.xnor.buoyanchor.model.BuoyPreset;
import ca.xnor.buoyanchor.model.KillickSpec;
import ca.xnor.buoyanchor.model.RodeSegment;
import ca.xnor.buoyanchor.model.SimParams;

import java.util.List;

/** Headless simulation runner that produces a written-report style analysis: CCG verdict,
 *  per-wind-speed steady-state metrics (force on rode, anchor drag risk, drift, submersion, etc.),
 *  and parameters at the top. The output is formatted text suitable for export. */
public final class Analysis {

    public static final double[] WIND_SPEEDS_KT = {5, 10, 15, 25, 50};
    private static final double G = 9.81;
    private static final double LB_PER_KG = 1.0 / 0.45359237;
    private static final double SF_DEFAULT = 2.5;       // CCG safety factor

    public static class WindResult {
        public double windKt;
        public double hs;
        public double tp;
        public double uOrb;
        public double meanTilt, maxTilt;            // maxTilt = max |tilt|
        public double minTiltSigned, maxTiltSigned; // signed extremes (for tilt-range arc)
        public double meanKeelY, minKeelY, maxKeelY;
        public double meanDrift;
        public double meanFwind;
        public double meanFlineMag;
        public double meanFlineX, meanFlineY;
        public double anchorUplift;    // 1.0 if anchor lifted at any point
        public boolean adequate;
    }

    public static class Result {
        public final List<WindResult> winds;
        public final double swKg;       // sinking weight
        public final double owKg;       // total dry weight (buoy + rode segments + killicks + anchor)
        public final double reserveKg;  // SW - OW (reserve buoyancy at full submersion)
        public final double mMinEffKg;  // required mooring in-water weight = reserve × SF
        public final double mActualEffKg;
        public final double mActualEffLb;
        public final double marginKg;
        public final double safetyFactor;
        public final double anchorHoldingN;  // anchor mass × holding factor × g
        public Result(List<WindResult> winds, double sw, double ow, double res,
                      double mMin, double mAct, double margin, double sf, double holdN) {
            this.winds = winds;
            this.swKg = sw; this.owKg = ow; this.reserveKg = res;
            this.mMinEffKg = mMin; this.mActualEffKg = mAct;
            this.mActualEffLb = mAct * LB_PER_KG;
            this.marginKg = margin; this.safetyFactor = sf;
            this.anchorHoldingN = holdN;
        }
    }

    /** Reports analysis progress in [0.0, 1.0]. */
    public interface ProgressCallback {
        void report(double fraction);
    }

    public static Result run(SimParams in) { return run(in, null); }

    /** Compute the generalized CCG check (per-material MDFs) plus the per-wind sim metrics. */
    public static Result run(SimParams in, ProgressCallback progress) {
        BuoyPreset preset = in.buoy.get();
        Anchor anchor = in.anchor.get();
        double swKg = preset.sinkingWeightKg;

        double segDryKg = 0;
        double segEffKg = 0;
        for (RodeSegment seg : in.segments) {
            double kg = seg.lengthM.get() * seg.kgPerM.get();
            segDryKg += kg;
            segEffKg += kg * seg.mdf.get();
        }
        double killicksTotalKg = 0;
        double killicksEffKg = 0;
        for (KillickSpec k : in.killicks) {
            killicksTotalKg += k.massKg.get();
            killicksEffKg   += k.massKg.get() * k.mdf.get();
        }

        double owKg = preset.dryMassKg + segDryKg + killicksTotalKg + anchor.massKg.get();
        double reserveKg = swKg - owKg;
        double sf = SF_DEFAULT;
        double mMinEffKg = reserveKg * sf;

        double mActualEffKg = anchor.massKg.get() * anchor.mdf.get()
                            + segEffKg
                            + killicksEffKg;

        double marginKg = mActualEffKg - mMinEffKg;
        double anchorHoldingN = anchor.massKg.get() * G * anchor.holdingFactor.get();

        // Run a simulation at each wind speed.
        java.util.List<WindResult> winds = new java.util.ArrayList<>();
        for (int i = 0; i < WIND_SPEEDS_KT.length; i++) {
            double w = WIND_SPEEDS_KT[i];
            int idx = i;
            ProgressCallback windCb = progress == null ? null :
                    f -> progress.report((idx + f) / WIND_SPEEDS_KT.length);
            winds.add(runAtWind(in, w, anchorHoldingN, windCb));
        }
        if (progress != null) progress.report(1.0);
        return new Result(winds, swKg, owKg, reserveKg, mMinEffKg, mActualEffKg, marginKg, sf, anchorHoldingN);
    }

    /** Run a headless sim at one wind speed and gather steady-state metrics. */
    private static WindResult runAtWind(SimParams base, double windKt, double anchorHoldingN,
                                        ProgressCallback progress) {
        SimParams p = copyParams(base);
        p.windKnots.set(windKt);

        Simulation sim = new Simulation();
        double dt = 1.0 / 240.0;
        // Warm-up: let wind ramp and rode settle. With 5 kt/s slew, max scenario reaches 50 kt at 10s.
        // Run 30 s warm-up then 30 s sampling.
        int warmSteps = 30 * 240;
        int sampleSteps = 30 * 240;
        int total = warmSteps + sampleSteps;
        for (int i = 0; i < warmSteps; i++) {
            sim.step(p, dt);
            if (progress != null && (i & 1023) == 0) progress.report((double) i / total);
        }

        double sumTilt = 0;
        double maxTiltAbs = 0;
        double minTiltSigned = 0, maxTiltSigned = 0;
        double sumY = 0, minY = 0, maxY = -1e9;
        double sumFwind = 0;
        double sumFlineMag = 0;
        double sumFlineX = 0, sumFlineY = 0;
        double maxAnchorUp = 0;
        for (int i = 0; i < sampleSteps; i++) {
            sim.step(p, dt);
            if (progress != null && (i & 1023) == 0) {
                progress.report((double) (warmSteps + i) / total);
            }
            double tilt = Math.toDegrees(sim.buoy.theta);
            sumTilt += tilt;
            if (Math.abs(tilt) > maxTiltAbs) maxTiltAbs = Math.abs(tilt);
            if (tilt < minTiltSigned) minTiltSigned = tilt;
            if (tilt > maxTiltSigned) maxTiltSigned = tilt;
            sumY += sim.buoy.y;
            if (sim.buoy.y < minY) minY = sim.buoy.y;
            if (sim.buoy.y > maxY) maxY = sim.buoy.y;
            sumFwind += sim.buoy.Fwind;
            sumFlineX += sim.rode.topConstraintFx;
            sumFlineY += sim.rode.topConstraintFy;
            sumFlineMag += Math.hypot(sim.rode.topConstraintFx, sim.rode.topConstraintFy);

            // Anchor uplift proxy: node just above anchor has lifted off the seabed.
            if (sim.rode.n > 2 && sim.rode.y[1] > sim.rode.seabedY + 0.10) maxAnchorUp = 1.0;
        }

        WindResult r = new WindResult();
        r.windKt = windKt;
        r.hs = sim.waves.hs;
        r.tp = sim.waves.tp;
        r.uOrb = sim.waves.uOrb;
        r.meanTilt = sumTilt / sampleSteps;
        r.maxTilt = maxTiltAbs;
        r.minTiltSigned = minTiltSigned;
        r.maxTiltSigned = maxTiltSigned;
        r.meanKeelY = sumY / sampleSteps;
        r.minKeelY = minY;
        r.maxKeelY = maxY;
        r.meanDrift = sim.buoy.x;
        r.meanFwind = sumFwind / sampleSteps;
        r.meanFlineMag = sumFlineMag / sampleSteps;
        r.meanFlineX = sumFlineX / sampleSteps;
        r.meanFlineY = sumFlineY / sampleSteps;
        r.anchorUplift = maxAnchorUp;
        double horizForceN = Math.abs(r.meanFlineX);
        r.adequate = (horizForceN < anchorHoldingN) && (maxAnchorUp < 0.5);
        return r;
    }

    private static SimParams copyParams(SimParams in) {
        SimParams p = new SimParams();
        p.buoy.set(in.buoy.get());
        p.windKnots.set(in.windKnots.get());
        p.depthM.set(in.depthM.get());
        p.fetchKm.set(in.fetchKm.get());
        p.waterDensity.set(in.waterDensity.get());
        p.airDensity.set(in.airDensity.get());
        p.bottomMu.set(in.bottomMu.get());

        p.segments.clear();
        for (RodeSegment seg : in.segments) p.segments.add(seg.copy());
        p.anchor.set(in.anchor.get().copy());

        p.killicks.clear();
        for (KillickSpec k : in.killicks) {
            p.killicks.add(new KillickSpec(k.massKg.get(), k.distM.get(), k.mdf.get(), k.fromAnchor.get()));
        }
        return p;
    }

    /** Format the analysis result as a plain-text report. */
    public static String format(SimParams in, Result r) {
        StringBuilder sb = new StringBuilder();
        BuoyPreset preset = in.buoy.get();
        Anchor anchor = in.anchor.get();
        sb.append("Buoy Anchor Simulator — Analysis Report\n");
        sb.append("=============================\n\n");
        sb.append("PARAMETERS\n");
        sb.append(String.format("  Mark:               %s%n", preset.toString()));
        sb.append(String.format("    Dry mass:         %.1f kg  (%.0f lb)%n",
                preset.dryMassKg, preset.dryMassKg * LB_PER_KG));
        sb.append(String.format("    Sinking weight:   %.1f kg  (%.0f lb)%n",
                preset.sinkingWeightKg, preset.sinkingWeightKg * LB_PER_KG));
        sb.append(String.format("  Depth:              %.1f m%n", in.depthM.get()));
        sb.append(String.format("  Fetch:              %.0f km%n", in.fetchKm.get()));
        sb.append(String.format("  Water density:      %.0f kg/m³%n", in.waterDensity.get()));
        sb.append(String.format("  Anchor:             %s %.1f kg, MDF %.2f, holding factor %.1f → holds %.0f N (%.0f lb)%n",
                anchor.kind.get(), anchor.massKg.get(), anchor.mdf.get(),
                anchor.holdingFactor.get(),
                r.anchorHoldingN, r.anchorHoldingN / 4.448));
        sb.append("  Rode (anchor → buoy):\n");
        for (RodeSegment seg : in.segments) {
            double kg = seg.lengthM.get() * seg.kgPerM.get();
            sb.append(String.format("    %-6s %5.1f m × %.2f kg/m (MDF %.2f)  =  %.1f kg dry%n",
                    seg.kind.get(), seg.lengthM.get(), seg.kgPerM.get(), seg.mdf.get(), kg));
        }
        sb.append(String.format("  Killicks (%d):       ", in.killicks.size()));
        double killTotal = 0;
        for (int i = 0; i < in.killicks.size(); i++) {
            KillickSpec k = in.killicks.get(i);
            killTotal += k.massKg.get();
            sb.append(String.format("%.1f kg @ %.1f m from %s (MDF %.2f)",
                    k.massKg.get(), k.distM.get(),
                    k.fromAnchor.get() ? "anchor" : "buoy", k.mdf.get()));
            if (i < in.killicks.size() - 1) sb.append(", ");
        }
        sb.append(String.format("  =  %.1f kg dry%n", killTotal));
        sb.append(String.format("  Bottom μ:           %.2f%n", in.bottomMu.get()));
        sb.append("\n");

        sb.append("CCG ANCHORING CHECK (per-segment MDFs)\n");
        sb.append(String.format("  SW (sinking weight): %.1f kg  (%.0f lb)%n", r.swKg, r.swKg * LB_PER_KG));
        sb.append(String.format("  OW (total dry):      %.1f kg  (%.0f lb)%n", r.owKg, r.owKg * LB_PER_KG));
        sb.append(String.format("  Reserve = SW - OW:   %.1f kg  (%.0f lb)%n",
                r.reserveKg, r.reserveKg * LB_PER_KG));
        sb.append(String.format("  Safety factor:       %.2f%n", r.safetyFactor));
        sb.append(String.format("  M_min effective:     %.1f kg  (%.0f lb)   [Reserve × SF]%n",
                r.mMinEffKg, r.mMinEffKg * LB_PER_KG));
        sb.append(String.format("  M_actual effective:  %.1f kg  (%.0f lb)   [Σ component × MDF]%n",
                r.mActualEffKg, r.mActualEffLb));
        sb.append(String.format("  Margin:              %+.1f kg  (%+.0f lb)%n",
                r.marginKg, r.marginKg * LB_PER_KG));
        sb.append(String.format("  Verdict:             %s%n",
                r.marginKg >= 0 ? "PASS (mooring adequate per CCG)" : "FAIL (mooring underweight)"));
        sb.append("\n");

        sb.append("PER-WIND SIMULATION (steady state after 30 s warm-up, 30 s sample)\n");
        sb.append(String.format("%-7s %-13s %-13s %-11s %-13s %-13s %-10s %s%n",
                "Wind", "Hs / Tp", "Mean tilt", "Max tilt",
                "Mean Fwind", "Mean |Fline|", "Fline_h", "Verdict"));
        for (WindResult w : r.winds) {
            String holdN = String.format("%.0f / %.0f N", Math.abs(w.meanFlineX), r.anchorHoldingN);
            String verdict;
            if (w.anchorUplift > 0.5) verdict = "ANCHOR UPLIFT";
            else if (Math.abs(w.meanFlineX) > r.anchorHoldingN) verdict = "DRAGS";
            else verdict = "HOLDS";
            sb.append(String.format("%4.0f kt  %4.2fm/%4.2fs  %+5.1f°       %5.1f°       %6.1f N      %7.1f N    %s     %s%n",
                    w.windKt, w.hs, w.tp,
                    w.meanTilt, w.maxTilt,
                    w.meanFwind, w.meanFlineMag, holdN, verdict));
        }
        sb.append("\n");
        sb.append("  Notes:\n");
        sb.append("  - Wind ramps at max 5 kt/s.\n");
        sb.append("  - Fline_h is the time-averaged horizontal pull on the anchor through the rode.\n");
        sb.append("  - HOLDS = horizontal pull < anchor holding capacity ("
                + String.format("%.0f N", r.anchorHoldingN) + ").\n");
        sb.append("  - DRAGS = horizontal pull exceeds anchor capacity; anchor would slide.\n");
        sb.append("  - ANCHOR UPLIFT = chain near anchor is lifted off the bottom — anchor pulls out.\n");
        return sb.toString();
    }

    /** Markdown variant with tables and inline SVG tilt diagrams for each wind condition. */
    public static String formatMarkdown(SimParams in, Result r) {
        StringBuilder sb = new StringBuilder();
        BuoyPreset preset = in.buoy.get();
        Anchor anchor = in.anchor.get();
        sb.append("# Buoy Anchor Simulator — Analysis Report\n\n");

        sb.append("## Parameters\n\n");
        sb.append("| | |\n|---|---|\n");
        sb.append(String.format("| Mark | %s |%n", preset.toString()));
        sb.append(String.format("| Dry mass | %.1f kg (%.0f lb) |%n",
                preset.dryMassKg, preset.dryMassKg * LB_PER_KG));
        sb.append(String.format("| Sinking weight | %.1f kg (%.0f lb) |%n",
                preset.sinkingWeightKg, preset.sinkingWeightKg * LB_PER_KG));
        sb.append(String.format("| Depth | %.1f m |%n", in.depthM.get()));
        sb.append(String.format("| Fetch | %.0f km |%n", in.fetchKm.get()));
        sb.append(String.format("| Anchor | %s %.1f kg, MDF %.2f, × holding factor %.1f → %.0f N |%n",
                anchor.kind.get(), anchor.massKg.get(), anchor.mdf.get(),
                anchor.holdingFactor.get(), r.anchorHoldingN));
        sb.append("\n");

        sb.append("### Rode (anchor → buoy)\n\n");
        sb.append("| # | Kind | Length | kg/m | MDF | Dry kg |\n|---|---|---|---|---|---|\n");
        for (int i = 0; i < in.segments.size(); i++) {
            RodeSegment seg = in.segments.get(i);
            double kg = seg.lengthM.get() * seg.kgPerM.get();
            sb.append(String.format("| %d | %s | %.1f m | %.2f | %.2f | %.1f kg |%n",
                    i + 1, seg.kind.get(), seg.lengthM.get(), seg.kgPerM.get(), seg.mdf.get(), kg));
        }
        sb.append("\n");

        if (!in.killicks.isEmpty()) {
            sb.append("### Killicks\n\n");
            sb.append("| # | Mass | Dist | From | MDF |\n|---|---|---|---|---|\n");
            for (int i = 0; i < in.killicks.size(); i++) {
                KillickSpec k = in.killicks.get(i);
                sb.append(String.format("| %d | %.1f kg | %.1f m | %s | %.2f |%n",
                        i + 1, k.massKg.get(), k.distM.get(),
                        k.fromAnchor.get() ? "anchor" : "buoy", k.mdf.get()));
            }
            sb.append("\n");
        }

        sb.append("## CCG Anchoring Check\n\n");
        sb.append("Per-segment MDFs applied component-by-component.\n\n");
        sb.append("| Quantity | Value |\n|---|---|\n");
        sb.append(String.format("| SW (sinking weight) | %.1f kg / %.0f lb |%n",
                r.swKg, r.swKg * LB_PER_KG));
        sb.append(String.format("| OW (total dry) | %.1f kg / %.0f lb |%n",
                r.owKg, r.owKg * LB_PER_KG));
        sb.append(String.format("| Reserve (SW − OW) | %.1f kg / %.0f lb |%n",
                r.reserveKg, r.reserveKg * LB_PER_KG));
        sb.append(String.format("| Safety factor | %.2f |%n", r.safetyFactor));
        sb.append(String.format("| **M_min effective** | **%.1f kg / %.0f lb** |%n",
                r.mMinEffKg, r.mMinEffKg * LB_PER_KG));
        sb.append(String.format("| **M_actual effective** | **%.1f kg / %.0f lb** |%n",
                r.mActualEffKg, r.mActualEffLb));
        sb.append(String.format("| Margin | %+.1f kg / %+.0f lb |%n",
                r.marginKg, r.marginKg * LB_PER_KG));
        String verdict = r.marginKg >= 0 ? "**PASS** — mooring adequate per CCG"
                                         : "**FAIL** — mooring underweight";
        sb.append(String.format("| Verdict | %s |%n", verdict));
        sb.append("\n");

        sb.append("## Per-Wind Simulation\n\n");
        sb.append("Steady-state metrics after 30 s warm-up and 30 s sample at each wind.\n\n");
        sb.append("| Wind | Hs | Tp | Mean tilt | Tilt range | Max tilt | Mean F_wind | Mean \\|F_line\\| | F_line horiz | Verdict |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (WindResult w : r.winds) {
            String verd;
            if (w.anchorUplift > 0.5) verd = "**ANCHOR UPLIFT**";
            else if (Math.abs(w.meanFlineX) > r.anchorHoldingN) verd = "**DRAGS**";
            else verd = "HOLDS";
            sb.append(String.format("| %.0f kt | %.2f m | %.2f s | %+.1f° | %+.1f° to %+.1f° | %.1f° | %.1f N | %.1f N | %.1f N | %s |%n",
                    w.windKt, w.hs, w.tp,
                    w.meanTilt, w.minTiltSigned, w.maxTiltSigned, w.maxTilt,
                    w.meanFwind, w.meanFlineMag, Math.abs(w.meanFlineX), verd));
        }
        sb.append("\n");

        return sb.toString();
    }
}
