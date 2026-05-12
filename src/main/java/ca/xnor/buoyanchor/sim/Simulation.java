package ca.xnor.buoyanchor.sim;

import ca.xnor.buoyanchor.model.BuoyPreset;
import ca.xnor.buoyanchor.model.RodeSegment;
import ca.xnor.buoyanchor.model.SimParams;

/**
 * Top-level simulation orchestrator. Tight coupling: the buoy occupies the top rode node, so
 * the rode's PBD distance-constraint solver handles force transfer between buoy and line.
 *
 * Per step:
 *   1. Recompute waves and re-discretize rode if the segment list changed.
 *   2. Compute buoy non-tension forces and torques (gravity, buoyancy, wind/wave drag).
 *   3. Push buoy mass and net acceleration to the top rode node.
 *   4. Step the rode (Verlet + PBD). Buoy translation falls out of this.
 *   5. Read buoy position back from top node.
 *   6. Take rode's constraint reaction (= line tension on buoy) and combine its torque with the
 *      non-tension torque. Integrate buoy rotation.
 */
public final class Simulation {

    public final Rode rode = new Rode();
    public final Buoy buoy = new Buoy();
    public Waves waves = Waves.fromWind(0, 1, 1);
    public double simTime = 0;
    /** Slew-limited mean wind that waves develop against. */
    public double meanWindKnots = 0;
    /** Mean + Ornstein–Uhlenbeck fluctuation; this is what the buoy actually sees as drag input. */
    public double actualWindKnots = 0;
    /** OU state for wind fluctuation. */
    private double windDelta = 0;
    /** RNG for gust noise; seeded per Simulation instance so analysis runs are reproducible. */
    private final java.util.Random rng = new java.util.Random(0xB0017C8AL);

    // Smoothed actual wave state (Hs/Tp) — waves take time to develop after a wind shift.
    public double actualHs = 0;
    public double actualTp = 1.0;
    public double actualUOrb = 0;
    public double actualDesignDepth = 1.0;

    // Continuously-evolving wave phase. Increments by ω·dt each step so it never jumps when ω
    // (Tp) changes — without this, the waves visually warp when wind speed changes.
    public double wavePhase = 0;

    public static final double WIND_RAMP_KT_PER_S = 5.0;
    /** Time constant for wave field to approach equilibrium after a wind change.
     *  Real seas take 10–30 min for full development; we use 90 s as a usable compromise that's
     *  long enough to look right but fast enough to see during a session. */
    public static final double WAVE_DEVELOPMENT_TAU_S = 90.0;

    private double[] lastSegLengths = null;
    private double lastDepth    = -1;
    private BuoyPreset lastPreset = null;
    private int lastKillickCount = -1;

    public static final double ANCHOR_RING_HEIGHT_M = 0.0;

    public void step(SimParams p, double dt) {
        simTime += dt;

        double depth = p.depthM.get();
        BuoyPreset preset = p.buoy.get();

        // Slew-limit the MEAN wind: real conditions don't change instantly, and step changes
        // inject unphysical energy that excites the buoy.
        double targetWind = p.windKnots.get();
        double maxStep = WIND_RAMP_KT_PER_S * dt;
        if (meanWindKnots < targetWind) {
            meanWindKnots = Math.min(targetWind, meanWindKnots + maxStep);
        } else if (meanWindKnots > targetWind) {
            meanWindKnots = Math.max(targetWind, meanWindKnots - maxStep);
        }

        // Ornstein–Uhlenbeck fluctuation around the mean. σ is a fixed fraction of the mean wind
        // (turbulence intensity), so a 5-kt setpoint doesn't swing as widely as a 30-kt one. The
        // OU time constant gustTauS also sets the window over which actualWindKnots averages back
        // to the mean — defaulting to 30 s.
        double sigma = SimParams.WIND_SIGMA_FRACTION * meanWindKnots;
        double tau = Math.max(1.0, p.gustTauS.get());
        if (sigma > 0) {
            double a = Math.exp(-dt / tau);
            double noiseScale = sigma * Math.sqrt(Math.max(0, 1 - a * a));
            windDelta = windDelta * a + noiseScale * rng.nextGaussian();
        } else {
            windDelta = 0;
        }
        actualWindKnots = Math.max(0, meanWindKnots + windDelta);

        // Wave field follows the slow-moving mean, not the gusty instantaneous value: short gusts
        // don't grow waves; sustained wind does. (The 90-s development tau below already smooths,
        // but feeding the mean here keeps the model conceptually clean.)
        Waves target = Waves.fromWind(meanWindKnots, depth, p.fetchKm.get());
        double alpha = 1.0 - Math.exp(-dt / WAVE_DEVELOPMENT_TAU_S);
        actualHs += (target.hs - actualHs) * alpha;
        actualTp += (target.tp - actualTp) * alpha;
        actualUOrb += (target.uOrb - actualUOrb) * alpha;
        actualDesignDepth += (target.designDepth - actualDesignDepth) * alpha;
        // waves.u10 carries the *instantaneous* wind (mean + gust fluctuation) — the buoy's wind
        // drag reads it directly, and a gust should produce a real spike in Fwind. Hs/Tp/uOrb in
        // the same record are the slow-moving sea state derived from the MEAN wind above.
        double instantU10 = actualWindKnots * 0.514444;
        waves = new Waves(instantU10, actualHs, actualTp, actualUOrb, actualDesignDepth);

        // Continuously evolve wave phase so Tp changes don't make waves warp/jump.
        if (actualTp > 0.01) {
            wavePhase += (2 * Math.PI / actualTp) * dt;
            if (wavePhase > 2 * Math.PI * 1e6) wavePhase -= 2 * Math.PI * 1e6;   // prevent overflow
        }

        rode.anchorX = 0;
        rode.anchorY = -depth + ANCHOR_RING_HEIGHT_M;
        rode.seabedY = -depth;
        rode.bottomMu = p.bottomMu.get();

        // (Re)configure buoy if the preset changed.
        if (preset != lastPreset) {
            buoy.configure(preset);
            // Place the buoy somewhere reasonable downwind.
            double scope = p.totalRodeLengthM() * 0.85;
            buoy.x = scope;
            buoy.y = -preset.draughtAtMinMooringM;
            buoy.theta = 0;
            buoy.omega = 0;
            lastPreset = preset;
        }

        // Re-discretize rode if segment lengths / depth / killick count changed.
        double[] segLens = new double[p.segments.size()];
        for (int i = 0; i < p.segments.size(); i++) segLens[i] = p.segments.get(i).lengthM.get();
        boolean segsChanged = lastSegLengths == null || lastSegLengths.length != segLens.length;
        if (!segsChanged) {
            for (int i = 0; i < segLens.length; i++) {
                if (segLens[i] != lastSegLengths[i]) { segsChanged = true; break; }
            }
        }
        if (segsChanged || depth != lastDepth || p.killicks.size() != lastKillickCount) {
            lastKillickCount = p.killicks.size();
            rode.rebuild(p.segments, buoy.attachWorldX(), buoy.attachWorldY());
            // Pre-place the top node to match the buoy.
            rode.x[rode.n - 1] = buoy.attachWorldX();
            rode.y[rode.n - 1] = buoy.attachWorldY();
            rode.px[rode.n - 1] = rode.x[rode.n - 1];
            rode.py[rode.n - 1] = rode.y[rode.n - 1];
            lastSegLengths = segLens;
            lastDepth = depth;
        }

        int kn = p.killicks.size();
        double[]  killMass       = new double[kn];
        double[]  killDist       = new double[kn];
        boolean[] killFromAnchor = new boolean[kn];
        double[]  killMdf        = new double[kn];
        for (int i = 0; i < kn; i++) {
            killMass[i]       = p.killicks.get(i).massKg.get();
            killDist[i]       = p.killicks.get(i).distM.get();
            killFromAnchor[i] = p.killicks.get(i).fromAnchor.get();
            killMdf[i]        = p.killicks.get(i).mdf.get();
        }
        rode.recomputeMasses(p.segments, killMass, killDist, killFromAnchor, killMdf);

        // Read translational velocity of the buoy (= top rode node) from Verlet history.
        int top = rode.n - 1;
        if (rode.n > 0 && dt > 0) {
            buoy.vx = (rode.x[top] - rode.px[top]) / dt;
            buoy.vy = (rode.y[top] - rode.py[top]) / dt;
        }

        // Non-tension forces and torque on the buoy.
        double localSurface = waveElevation(buoy.x, depth);
        double waveOrb = (waves.tp > 0.01) ? waves.uOrb * Math.sin(wavePhase) : 0;
        double waveOrbAccel = (waves.tp > 0.01)
                ? waves.uOrb * (2 * Math.PI / waves.tp) * Math.cos(wavePhase) : 0;

        buoy.computeEffectiveMassAndInertia(localSurface, p.waterDensity.get(), depth);

        Buoy.Forces nonTension = buoy.computeNonTensionForcesAndTorque(
                localSurface,
                p.waterDensity.get(), p.airDensity.get(),
                waves.u10, waveOrb, waveOrbAccel, waves.hs);

        // Push to top node using effective mass so PBD treats the buoy with its full inertia.
        rode.setBuoyNode(buoy.effectiveMass,
                nonTension.Fx / buoy.effectiveMass,
                nonTension.Fy / buoy.effectiveMass);

        // Step the rode — translation of the buoy falls out of this.
        rode.step(dt);

        // Read buoy position back from the top node.
        buoy.x = rode.x[top];
        buoy.y = rode.y[top];

        // Tilt: combine the non-tension torque (already about the CG) with the torque from the
        // actual line tension (from PBD constraint reaction) about the CG.
        double aWX = buoy.attachWorldX();
        double aWY = buoy.attachWorldY();
        double cgWX = buoy.cgWorldX();
        double cgWY = buoy.cgWorldY();
        double rx = aWX - cgWX;
        double ry = aWY - cgWY;
        double tensionTorque = rx * rode.topConstraintFy - ry * rode.topConstraintFx;
        buoy.integrateRotation(nonTension.tau + tensionTorque, dt);
    }

    public double waveElevation(double xWorld, double depth) {
        if (waves.tp <= 0.01 || waves.hs < 0.01) return 0;
        double celerity = Math.sqrt(Waves.G * Math.max(0.5, depth));
        double wavelength = celerity * waves.tp;
        double k = 2 * Math.PI / wavelength;
        return (waves.hs / 2.0) * Math.sin(wavePhase - k * xWorld);
    }
}
