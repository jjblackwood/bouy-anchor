package ca.xnor.buoyanchor.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.xnor.buoyanchor.model.BuoyPreset;
import ca.xnor.buoyanchor.model.RodeSegment;
import ca.xnor.buoyanchor.model.SimParams;
import org.junit.jupiter.api.Test;

/**
 * End-to-end: full Simulation with buoy + rode in calm conditions. After settling, the buoy should
 * be FLOATING (some part above the surface), the rode should NOT have lifted the buoy to the
 * seabed, and tension should be modest. This is the test that should catch the "mark just sinks"
 * bug.
 */
class SimulationFloatingTest {

  private SimParams calmParams() {
    SimParams p = new SimParams();
    p.killicks.clear();
    p.windKnots.set(0); // calm
    p.depthM.set(8.0);
    p.fetchKm.set(50);
    p.segments.setAll(
        new RodeSegment(RodeSegment.Kind.CHAIN, 15.0, 2.5, 0.87),
        new RodeSegment(RodeSegment.Kind.ROPE, 12.0, 0.15, 0.12));
    return p;
  }

  private static void runFor(Simulation sim, SimParams p, double seconds) {
    double dt = 1.0 / 240.0;
    int steps = (int) (seconds / dt);
    for (int i = 0; i < steps; i++) sim.step(p, dt);
  }

  @Test
  void calmWater_tdl025_floats() {
    SimParams p = calmParams();
    p.buoy.set(BuoyPreset.TDL_025M_S);
    Simulation sim = new Simulation();
    runFor(sim, p, 60.0);

    BuoyPreset preset = BuoyPreset.TDL_025M_S;

    // Top of buoy must remain above the calm waterline (y = 0).
    double topY = sim.buoy.y + preset.overallHeightM;
    assertTrue(
        topY > 0,
        "TDL-0.25M-S top should be above water after settling, but topY="
            + topY
            + " (keel y="
            + sim.buoy.y
            + "). Buoy has SUNK.");

    // Buoy must NOT be resting on the seabed.
    assertTrue(
        sim.buoy.y > -p.depthM.get() + 0.5,
        "Buoy keel should not be near the seabed. y=" + sim.buoy.y);

    // Submersion should be reasonable (roughly the preset draught + extra from suspended chain).
    double sub = -sim.buoy.y;
    assertTrue(
        sub > 0.3 && sub < preset.overallHeightM,
        "TDL-0.25M-S submersion should be 0.3 m to overall height (1.83 m), was " + sub);

    // Vertical force balance: |Fline_y| ≈ buoyancy − gravity.
    double Fy_buoyancy_minus_gravity = sim.buoy.FbuoyancyTotal - sim.buoy.mass * 9.81;
    double Fy_line = sim.rode.topConstraintFy;
    // In calm steady state, line tension's vertical component should equal buoyancy excess
    // (sign-flipped).
    // Tolerance is loose because the system isn't yet perfectly settled.
    assertEquals(
        -Fy_buoyancy_minus_gravity,
        Fy_line,
        100.0,
        "Vertical force balance: -|Fy_buoy_excess| should ≈ Fline_y. Buoyancy="
            + sim.buoy.FbuoyancyTotal
            + " mg="
            + (sim.buoy.mass * 9.81)
            + " Fline_y="
            + Fy_line);
  }

  @Test
  void calmWater_tdl030_floats() {
    SimParams p = calmParams();
    p.buoy.set(BuoyPreset.TDL_030M_S);
    Simulation sim = new Simulation();
    runFor(sim, p, 60.0);

    BuoyPreset preset = BuoyPreset.TDL_030M_S;
    double topY = sim.buoy.y + preset.overallHeightM;
    assertTrue(topY > 0, "TDL-0.30M-S top should be above water; topY=" + topY);
  }

  @Test
  void calmWater_surMark_floats() {
    SimParams p = calmParams();
    p.buoy.set(BuoyPreset.SUR_MARK);
    Simulation sim = new Simulation();
    runFor(sim, p, 60.0);

    BuoyPreset preset = BuoyPreset.SUR_MARK;
    double topY = sim.buoy.y + preset.overallHeightM;
    assertTrue(topY > 0, "Sur-Mark top should be above water; topY=" + topY);
  }

  @Test
  void calmWater_buoyDoesntSlamIntoBottom() {
    SimParams p = calmParams();
    p.buoy.set(BuoyPreset.TDL_025M_S);
    Simulation sim = new Simulation();
    // Track minimum y the buoy ever reaches in the first 10 seconds.
    double dt = 1.0 / 240.0;
    double minY = 0;
    for (int i = 0; i < 10 * 240; i++) {
      sim.step(p, dt);
      if (sim.buoy.y < minY) minY = sim.buoy.y;
    }
    assertTrue(
        minY > -p.depthM.get() + 0.5,
        "Buoy should never reach the seabed during transient. minY=" + minY);
  }

  @Test
  void calmWater_rodeHasSlackOnBottom() {
    SimParams p = calmParams();
    p.buoy.set(BuoyPreset.TDL_025M_S);
    Simulation sim = new Simulation();
    runFor(sim, p, 60.0);

    // With 15 m chain and 8 m depth + ~0.85 m buoy submersion, much chain should rest on bottom.
    int onBottom = 0;
    for (int i = 0; i < sim.rode.n; i++) {
      if (sim.rode.y[i] <= sim.rode.seabedY + 0.05) onBottom++;
    }
    assertTrue(
        onBottom > sim.rode.n / 5,
        "At least 20% of rode nodes should rest on bottom in slack rig. onBottom="
            + onBottom
            + " of "
            + sim.rode.n);
  }

  @Test
  void calmWater_lineTensionIsReasonable() {
    SimParams p = calmParams();
    p.buoy.set(BuoyPreset.TDL_025M_S);
    Simulation sim = new Simulation();
    runFor(sim, p, 60.0);

    double T = Math.hypot(sim.rode.topConstraintFx, sim.rode.topConstraintFy);
    // In calm conditions, suspended rode weight is at most a few hundred N.
    // Anything over 5000 N indicates a constraint blow-up.
    assertFalse(Double.isNaN(T) || Double.isInfinite(T), "Line tension should be finite, was " + T);
    assertTrue(T < 5000, "Line tension in calm should be small (<5000 N), was " + T);
  }
}
