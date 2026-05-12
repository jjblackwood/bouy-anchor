package ca.xnor.buoyanchor.sim;

import ca.xnor.buoyanchor.model.BuoyPreset;

import java.util.ArrayList;
import java.util.List;

/**
 * 2D rigid-body buoy modelled as a stack of cylindrical segments along the body axis.
 *
 * Each segment carries its own mass (shell distributed by volume + ballast lump assigned to the
 * slice containing the ballast height) and is the unit of force application: buoyancy is computed
 * per-segment against the local water surface, wind drag per emerged segment, wave drag per
 * top-Hs segment. Forces are summed and torqued about the buoy's CG to drive the rigid-body
 * rotation. Translation is performed by the rode (the buoy is the top rode node, with its mass
 * set externally each step).
 *
 * Sign convention: theta = 0 is upright, +theta tips the body axis from +y toward +x ("downwind").
 * That makes +theta a CW rotation in the standard 2D frame, so the rotational integrator negates
 * the standard CCW-positive torque before applying it.
 */
public final class Buoy {

    public static final double GRAVITY = 9.81;
    public static final double CD_AIR  = 1.2;
    public static final double CD_WATER = 1.4;       // close to clean-cylinder for a spar buoy
    private static final double SEGMENT_TARGET_M = 0.10;

    /** A cylindrical slice of the buoy. */
    public static final class Segment {
        public final double sBottom;     // axis position of slice bottom (m above keel)
        public final double length;      // axis length (m)
        public final double radius;      // cross-section radius (m)
        public final double mass;        // total mass of this slice (kg) — includes any ballast lump assigned here
        Segment(double sBottom, double length, double radius, double mass) {
            this.sBottom = sBottom; this.length = length; this.radius = radius; this.mass = mass;
        }
        public double sCentroid() { return sBottom + length / 2.0; }
        public double area() { return Math.PI * radius * radius; }
        public double diameter() { return 2 * radius; }
    }

    /** Estimated wave-radiation damping for pitch (N·m·s/rad). For a slim cylinder there's very
     *  little heave radiation (axisymmetric motion radiates poorly), so we don't model it —
     *  end-cap drag is the dominant heave damper. */
    public double pitchRadiationDamping;

    public BuoyPreset preset;
    public List<Segment> segments;
    public double mass;                              // dry mass (kg)
    public double cgAboveKeelM;
    public double inertia;                           // dry inertia about CG
    public double effectiveMass;                     // dry + hydrodynamic added mass, recomputed each step
    public double effectiveInertia;                  // dry + hydrodynamic added moment of inertia, recomputed each step

    public double x, y;     // keel world position (m)
    public double vx, vy;   // translational velocity at the keel (set externally each step from rode Verlet)
    public double theta;    // tilt from vertical, radians (+ = CW = downwind in our convention)
    public double omega;    // angular velocity (in same +CW convention)

    // Bookkeeping for legend / debug.
    public double Fwind, Fwave;
    public double FbuoyancyTotal;
    public double submersionM;

    public static final class Forces {
        public double Fx, Fy, tau;     // tau in standard CCW-positive convention
    }

    public void configure(BuoyPreset p) {
        this.preset = p;
        this.segments = buildSegments(p);
        this.mass = 0;
        for (Segment s : segments) mass += s.mass;
        double moment = 0;
        for (Segment s : segments) moment += s.mass * s.sCentroid();
        this.cgAboveKeelM = moment / Math.max(1e-9, mass);
        double I = 0;
        for (Segment s : segments) {
            double d = s.sCentroid() - cgAboveKeelM;
            I += s.mass * d * d;
        }
        this.inertia = Math.max(1e-3, I);
        this.effectiveMass = mass;
        this.effectiveInertia = inertia;
    }

    /** Effective mass / inertia for the integrators. We use DRY values: hydrodynamic added mass
     *  is implicit in the per-segment drag forces (which include the body's resistance to
     *  accelerating through water). Adding it to the inertia AS WELL would double-count and tends
     *  to put the natural pitch period right on top of the wave period for typical spar buoys,
     *  causing unphysical resonance. Drag through water is what really damps the motion. */
    public void computeEffectiveMassAndInertia(double localSurfaceY, double waterDensity, double depthM) {
        effectiveMass = mass;
        effectiveInertia = inertia;

        // Wave radiation damping. For a slim spar in shallow water, energy is carried away by the
        // small surface waves the buoy generates as it pitches. Scale with the displaced volume
        // distributed by lever arm² (= contribution to moment of inertia of the displaced water)
        // and a shallow-water timescale √(d/g).
        double cosT = Math.cos(theta);
        double sumIDisp = 0;
        for (Segment seg : segments) {
            double[] sub = submergedAxisRange(seg.sBottom, seg.length, y, localSurfaceY, cosT);
            double subLen = sub[1] - sub[0];
            if (subLen > 1e-6) {
                double Vsub = seg.area() * subLen;
                double rArm = (sub[0] + sub[1]) / 2.0 - cgAboveKeelM;
                sumIDisp += Vsub * rArm * rArm;
            }
        }
        double timescale = Math.sqrt(Math.max(0.5, depthM) / GRAVITY);
        // Empirical scaling: matches order-of-magnitude estimates for spar buoys at typical Tp.
        pitchRadiationDamping = waterDensity * sumIDisp / timescale;
    }

    /** Slice each preset section into ~10 cm chunks. Shell mass is distributed proportionally to
     *  slice volume; the ballast lump is added to the slice that contains ballastHeightAboveKeelM. */
    private static List<Segment> buildSegments(BuoyPreset p) {
        double totalVol = 0;
        for (BuoyPreset.Section s : p.sections) {
            totalVol += Math.PI * Math.pow(s.diameterM() / 2.0, 2) * s.heightM();
        }
        double shellMass = Math.max(0, p.dryMassKg - p.ballastMassKg);

        List<Segment> segs = new ArrayList<>();
        double yOff = 0;
        for (BuoyPreset.Section s : p.sections) {
            int nSlices = Math.max(1, (int) Math.round(s.heightM() / SEGMENT_TARGET_M));
            double sliceLen = s.heightM() / nSlices;
            double r = s.diameterM() / 2.0;
            double sliceVol = Math.PI * r * r * sliceLen;
            double sliceShell = (totalVol > 0) ? (sliceVol / totalVol) * shellMass : 0;
            for (int i = 0; i < nSlices; i++) {
                segs.add(new Segment(yOff, sliceLen, r, sliceShell));
                yOff += sliceLen;
            }
        }

        if (p.ballastMassKg > 0 && !segs.isEmpty()) {
            double bH = p.ballastHeightAboveKeelM;
            int idx = 0;
            for (int i = 0; i < segs.size(); i++) {
                Segment s = segs.get(i);
                if (bH >= s.sBottom && bH <= s.sBottom + s.length) { idx = i; break; }
            }
            Segment old = segs.get(idx);
            segs.set(idx, new Segment(old.sBottom, old.length, old.radius, old.mass + p.ballastMassKg));
        }
        return segs;
    }

    public double attachWorldX() { return x + preset.attachAboveKeelM * Math.sin(theta); }
    public double attachWorldY() { return y + preset.attachAboveKeelM * Math.cos(theta); }
    public double cgWorldX() { return x + cgAboveKeelM * Math.sin(theta); }
    public double cgWorldY() { return y + cgAboveKeelM * Math.cos(theta); }

    public Forces computeNonTensionForcesAndTorque(double localSurfaceY,
                                                   double waterDensity, double airDensity,
                                                   double windU10, double waveOrbVel, double waveOrbAccel,
                                                   double Hs) {
        Forces out = new Forces();
        double sinT = Math.sin(theta);
        double cosT = Math.cos(theta);
        double cgWX = cgWorldX();
        double cgWY = cgWorldY();
        double Fwind_acc = 0, Fwave_acc = 0, Fb_acc = 0;

        // (Removed end-cap axial drag. These are rotomoulded marks with smooth rounded hulls —
        // no separate flat end-cap. Axial flow stays mostly attached and contributes only minor
        // skin-friction drag, which we leave out.)

        for (Segment seg : segments) {
            // Gravity at segment centroid.
            double sc = seg.sCentroid();
            double scWX = x + sc * sinT;
            double scWY = y + sc * cosT;
            double Fg_y = -seg.mass * GRAVITY;
            out.Fy += Fg_y;
            out.tau += (scWX - cgWX) * Fg_y;

            // Buoyancy on the submerged axis range of this segment.
            double[] sub = submergedAxisRange(seg.sBottom, seg.length, y, localSurfaceY, cosT);
            double subLen = sub[1] - sub[0];
            double sBC = (sub[0] + sub[1]) / 2.0;
            if (subLen > 1e-6) {
                double Vsub = seg.area() * subLen;
                double Fb = waterDensity * Vsub * GRAVITY;
                out.Fy += Fb;
                Fb_acc += Fb;
                double bcWX = x + sBC * sinT;
                double bcWY = y + sBC * cosT;
                out.tau += (bcWX - cgWX) * Fb;
            }

            // Hydrodynamic / aerodynamic drag, against relative velocity of the segment vs. local fluid.
            // Velocity of segment centroid in world frame = translational velocity of the keel (vx, vy)
            // plus tangential velocity from rotation about CG: v_rot = omega_my × (sc - cgH) (axis-tangent)
            double rArm = sc - cgAboveKeelM;       // along body axis, signed
            // Tangential direction (perpendicular to body axis, in the rotation direction): for +omega_my (CW)
            // this is at -90° from the body axis, i.e. (cosT, -sinT).
            double vSegX = vx + omega * rArm * cosT;
            double vSegY = vy + omega * rArm * (-sinT);

            // Submerged drag + Morison inertial force.
            if (subLen > 1e-6) {
                double sliceTopY = y + sub[1] * cosT;
                boolean inWaveBand = Hs > 0.01 && (localSurfaceY - sliceTopY) < Hs;
                double waterVx = inWaveBand ? waveOrbVel : 0.0;
                double waterVy = 0.0;

                // Morison inertial force: F = ρ · Cm · V · du/dt. Cm = 1.0 for a slender
                // surface-piercing cylinder (body diameter ≪ wavelength) — only one displaced
                // volume of water has to be accelerated, not two. Cm = 2 is for fully-submerged
                // cylinders in uniform oscillating flow, which isn't our case.
                if (inWaveBand && Math.abs(waveOrbAccel) > 1e-5) {
                    double Vsub = seg.area() * subLen;
                    double Cm = 1.0;
                    double Finert = waterDensity * Cm * Vsub * waveOrbAccel;
                    out.Fx += Finert;
                    double bcWX = x + sBC * sinT;
                    double bcWY = y + sBC * cosT;
                    out.tau += (bcWX - cgWX) * 0 - (bcWY - cgWY) * Finert;
                    Fwave_acc += Finert;
                }
                double relVx = vSegX - waterVx;
                double relVy = vSegY - waterVy;
                double relSpeed = Math.hypot(relVx, relVy);
                if (relSpeed > 1e-5) {
                    double Aside = subLen * seg.diameter();
                    double q = 0.5 * waterDensity * CD_WATER * Aside * relSpeed;
                    double Fdx = -q * relVx;
                    double Fdy = -q * relVy;
                    out.Fx += Fdx;
                    out.Fy += Fdy;
                    double bcWX = x + sBC * sinT;
                    double bcWY = y + sBC * cosT;
                    out.tau += (bcWX - cgWX) * Fdy - (bcWY - cgWY) * Fdx;
                    if (waterVx != 0) Fwave_acc += Fdx;     // net horizontal force on buoy from waves
                }
            }

            // Emerged drag (wind).
            double emergStart, emergEnd;
            if (cosT >= 0) { emergStart = sub[1]; emergEnd = seg.sBottom + seg.length; }
            else            { emergStart = seg.sBottom; emergEnd = sub[0]; }
            if (emergEnd > emergStart) {
                double emergLen = emergEnd - emergStart;
                double sEC = (emergStart + emergEnd) / 2.0;
                double relVx = vSegX - windU10;
                double relVy = vSegY - 0.0;
                double relSpeed = Math.hypot(relVx, relVy);
                if (relSpeed > 1e-5) {
                    double Aside = emergLen * seg.diameter();
                    double q = 0.5 * airDensity * CD_AIR * Aside * relSpeed;
                    double Fdx = -q * relVx;
                    double Fdy = -q * relVy;
                    out.Fx += Fdx;
                    out.Fy += Fdy;
                    double ecWX = x + sEC * sinT;
                    double ecWY = y + sEC * cosT;
                    out.tau += (ecWX - cgWX) * Fdy - (ecWY - cgWY) * Fdx;
                    Fwind_acc += Fdx;
                }
            }
        }

        Fwind = Fwind_acc;
        Fwave = Fwave_acc;
        FbuoyancyTotal = Fb_acc;
        submersionM = Math.max(0, localSurfaceY - y);
        return out;
    }

    /** Find the axis-coordinate range [sStart, sEnd] of one section that lies below the water plane.
     *  See note in the previous version — independent of the displaced volume direction. */
    static double[] submergedAxisRange(double sBottom, double h, double keelY, double surfaceY, double cosT) {
        double sTop = sBottom + h;
        if (Math.abs(cosT) < 1e-3) {
            return (keelY < surfaceY) ? new double[]{sBottom, sTop} : new double[]{sBottom, sBottom};
        }
        double sSurface = (surfaceY - keelY) / cosT;
        if (cosT > 0) {
            double sStart = sBottom;
            double sEnd = Math.max(sBottom, Math.min(sTop, sSurface));
            return new double[]{sStart, sEnd};
        } else {
            double sStart = Math.max(sBottom, Math.min(sTop, sSurface));
            double sEnd = sTop;
            return new double[]{sStart, sEnd};
        }
    }

    /** tauStd in standard CCW-positive convention. Our theta is CW-positive, so we negate.
     *  No artificial angular damping — rotational damping comes from real fluid drag on the
     *  segments (computed in computeNonTensionForcesAndTorque). */
    public void integrateRotation(double tauStd, double dt) {
        double tau = -tauStd;
        // Wave radiation damping (always opposes ω).
        tau -= pitchRadiationDamping * omega;
        omega += tau / effectiveInertia * dt;
        theta += omega * dt;
        double TILT_LIMIT = Math.PI / 2 - 0.01;
        if (theta > TILT_LIMIT)  { theta = TILT_LIMIT;  if (omega > 0) omega = 0; }
        if (theta < -TILT_LIMIT) { theta = -TILT_LIMIT; if (omega < 0) omega = 0; }
    }
}
