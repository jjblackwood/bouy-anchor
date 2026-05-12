package ca.xnor.buoyanchor.sim;

import ca.xnor.buoyanchor.model.BuoyPreset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Buoy force model in isolation — no rode, no waves, no wind. The buoy at its preset draught
 *  (with the ~1 kg "min mooring" load implicit) should produce buoyancy ≈ (mass + min-load) × g. */
class BuoyancyTest {

    private static final double G = 9.81;
    private static final double WATER_RHO = 1000.0;
    private static final double AIR_RHO = 1.22;

    private static Buoy buoyAt(BuoyPreset preset, double yKeel) {
        Buoy b = new Buoy();
        b.configure(preset);
        b.x = 0;
        b.y = yKeel;
        b.theta = 0;
        b.omega = 0;
        return b;
    }

    /** Simulates only the buoy under gravity + buoyancy, no rode, no wind. Expect it to settle
     *  at a draught where buoyancy ≈ mass × g (i.e. it floats at its own weight). */
    @Test
    void freeBuoy_settlesAtFloatingDraught() {
        for (BuoyPreset p : BuoyPreset.LIBRARY) {
            Buoy b = buoyAt(p, -p.draughtAtMinMooringM);
            double vy = 0;
            double dt = 1.0 / 240.0;
            for (int step = 0; step < 30 * 240; step++) {     // 30 simulated seconds
                Buoy.Forces f = b.computeNonTensionForcesAndTorque(0, WATER_RHO, AIR_RHO, 0, 0, 0, 0);
                double accelY = f.Fy / b.mass;
                vy += accelY * dt;
                vy *= 0.99;                                   // mild damping to settle
                b.y += vy * dt;
            }
            // After settling, gravity should equal buoyancy.
            Buoy.Forces fEq = b.computeNonTensionForcesAndTorque(0, WATER_RHO, AIR_RHO, 0, 0, 0, 0);
            double netN = fEq.Fy;
            assertEquals(0.0, netN, 5.0,
                    p.displayName + ": free buoy should reach static equilibrium (net Fy ≈ 0). Settled y = " + b.y);

            // Buoy should float (some part above water, some below).
            assertTrue(b.y < 0,
                    p.displayName + ": settled keel y should be below the surface, was " + b.y);
            double topY = b.y + p.overallHeightM;
            assertTrue(topY > 0,
                    p.displayName + ": top of buoy should be above the surface, was " + topY);
        }
    }

    /** A buoy with a heavy line load should submerge deeper but still float (until load exceeds
     *  the buoy's maximum buoyancy). */
    @Test
    void buoyancyResponse_increasesWithSubmersion() {
        BuoyPreset p = BuoyPreset.TDL_025M_S;
        Buoy b = buoyAt(p, -p.draughtAtMinMooringM);
        double Fb_shallow = b.computeNonTensionForcesAndTorque(0, WATER_RHO, AIR_RHO, 0, 0, 0, 0).Fy + b.mass * G;
        b.y = -1.5;     // deeper
        double Fb_deep = b.computeNonTensionForcesAndTorque(0, WATER_RHO, AIR_RHO, 0, 0, 0, 0).Fy + b.mass * G;
        assertTrue(Fb_deep > Fb_shallow,
                "Buoyancy should increase with deeper submersion: shallow=" + Fb_shallow + " deep=" + Fb_deep);
    }

    /** With no submersion (buoy keel above water), buoyancy is zero. */
    @Test
    void buoyancyZero_whenAbove() {
        BuoyPreset p = BuoyPreset.TDL_025M_S;
        Buoy b = buoyAt(p, 0.5);   // keel 0.5 m above the surface
        double Fb = b.computeNonTensionForcesAndTorque(0, WATER_RHO, AIR_RHO, 0, 0, 0, 0).Fy + b.mass * G;
        assertEquals(0.0, Fb, 0.1, "No submersion → no buoyancy");
    }

    /** Fully submerged buoyancy = section volume × ρ × g (= sinking force capacity). */
    @Test
    void fullSubmersion_buoyancyMatchesVolume() {
        for (BuoyPreset p : BuoyPreset.LIBRARY) {
            Buoy b = buoyAt(p, -p.overallHeightM - 0.5);   // keel well below water; entire body submerged
            double Fb = b.computeNonTensionForcesAndTorque(0, WATER_RHO, AIR_RHO, 0, 0, 0, 0).Fy + b.mass * G;
            double volume = 0;
            for (BuoyPreset.Section s : p.sections) {
                volume += Math.PI * Math.pow(s.diameterM() / 2.0, 2) * s.heightM();
            }
            double expectedFb = volume * WATER_RHO * G;
            assertEquals(expectedFb, Fb, expectedFb * 0.02,
                    p.displayName + ": fully submerged buoyancy should = ρgV (" + expectedFb + " N)");
        }
    }

    /** Buoyancy of a fully-submerged buoy is independent of tilt — it's just ρgV. */
    @Test
    void buoyancy_invariantUnderTilt_whenFullySubmerged() {
        BuoyPreset p = BuoyPreset.TDL_025M_S;
        double[] tilts = {0, Math.PI / 12, Math.PI / 4, Math.PI / 3, Math.PI / 2 - 0.05};
        double Fb_upright = -1;
        for (double tilt : tilts) {
            Buoy b = buoyAt(p, -p.overallHeightM - 1.0);     // well below water
            b.theta = tilt;
            double Fb = b.computeNonTensionForcesAndTorque(0, WATER_RHO, AIR_RHO, 0, 0, 0, 0).Fy
                        + b.mass * G;
            if (Fb_upright < 0) Fb_upright = Fb;
            assertEquals(Fb_upright, Fb, Fb_upright * 0.05,
                    "Fully-submerged buoyancy must be tilt-invariant. tilt=" + Math.toDegrees(tilt) +
                    " Fb=" + Fb + " (upright=" + Fb_upright + ")");
        }
    }

    /** A spar buoy tilted slightly off vertical should produce a restoring (negative-θ in our
     *  convention) torque from the offset between CG and centre of buoyancy. */
    @Test
    void spar_producesRestoringTorqueWhenTilted() {
        for (BuoyPreset p : new BuoyPreset[]{BuoyPreset.TDL_025M_S, BuoyPreset.TDL_030M_S}) {
            Buoy b = buoyAt(p, -p.draughtAtMinMooringM);
            b.theta = Math.toRadians(5);     // small downwind tilt
            Buoy.Forces f = b.computeNonTensionForcesAndTorque(0, WATER_RHO, AIR_RHO, 0, 0, 0, 0);
            // tau is in standard CCW-positive convention; for our θ (CW-positive), restoring requires
            // CCW torque (positive standard tau).
            assertTrue(f.tau > 0,
                    p.displayName + ": tilted spar should produce restoring (positive standard) torque, " +
                    "but tau=" + f.tau);
        }
    }

    /** Wind drag on a typical mark at 15 kt should be in the right ballpark (a few Newtons up to
     *  tens of Newtons depending on exposed area). */
    @Test
    void windDrag_signAndMagnitude() {
        BuoyPreset p = BuoyPreset.TDL_025M_S;
        Buoy b = buoyAt(p, -p.draughtAtMinMooringM);
        double u10 = 15 * 0.514444;
        Buoy.Forces f = b.computeNonTensionForcesAndTorque(
                0, WATER_RHO, AIR_RHO, u10, 0, 0, 0);
        assertTrue(b.Fwind > 0, "Wind drag should be positive (downwind), was " + b.Fwind);
        // Order-of-magnitude check: 0.5 × 1.22 × 1.2 × A × U² with A ≈ 0.25 × 1.0 m² and U=7.7 m/s
        // → 0.732 × 0.25 × 59 ≈ 11 N
        assertTrue(b.Fwind > 1 && b.Fwind < 100,
                "Wind drag at 15 kt should be 1–100 N range, was " + b.Fwind);
    }
}
