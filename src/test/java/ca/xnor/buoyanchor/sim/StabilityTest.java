package ca.xnor.buoyanchor.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.xnor.buoyanchor.model.Anchor;
import ca.xnor.buoyanchor.model.BuoyPreset;
import ca.xnor.buoyanchor.model.KillickSpec;
import ca.xnor.buoyanchor.model.RodeSegment;
import ca.xnor.buoyanchor.model.SimParams;
import org.junit.jupiter.api.Test;

/** Stability of the rigid-body buoy in light wind, and slew-limit on wind changes. */
class StabilityTest {

  private SimParams baseParams(BuoyPreset preset) {
    SimParams p = new SimParams();
    p.killicks.clear();
    p.windKnots.set(5.0);
    p.depthM.set(8.0);
    p.fetchKm.set(50);
    p.segments.setAll(
        new RodeSegment(RodeSegment.Kind.CHAIN, 15.0, 2.5, 0.87),
        new RodeSegment(RodeSegment.Kind.ROPE, 12.0, 0.15, 0.12));
    p.buoy.set(preset);
    return p;
  }

  /** In dead calm (no wind, no waves), a stable spar buoy should sit nearly upright. */
  @Test
  void spar_uprightInCalm() {
    for (BuoyPreset preset :
        new BuoyPreset[] {BuoyPreset.TDL_025M_S, BuoyPreset.TDL_030M_S, BuoyPreset.SUR_MARK}) {
      SimParams p = baseParams(preset);
      p.windKnots.set(0);
      Simulation sim = new Simulation();
      double dt = 1.0 / 240.0;
      for (int i = 0; i < 60 * 240; i++) sim.step(p, dt); // 60 s to settle
      double tilt = Math.abs(Math.toDegrees(sim.buoy.theta));
      assertTrue(
          tilt < 5.0, preset.displayName + " calm-water tilt = " + tilt + "°, expected < 5°");
    }
  }

  /**
   * A spar buoy in 5 kt of wind should not lay down — mean tilt should be under 20°, with peak
   * under 30°. (Real fluid damping is light at these wind speeds, so we expect meaningful
   * oscillation from wave forcing — but a stable spar should not flip past 30°.)
   */
  @Test
  void spar_doesntFlipIn5ktWind() {
    for (BuoyPreset preset : new BuoyPreset[] {BuoyPreset.TDL_025M_S, BuoyPreset.TDL_030M_S}) {
      SimParams p = baseParams(preset);
      Simulation sim = new Simulation();
      double dt = 1.0 / 240.0;
      for (int i = 0; i < 5 * 240; i++) sim.step(p, dt); // pre-ramp
      double sumTilt = 0;
      double maxTilt = 0;
      int samples = 30 * 240;
      for (int i = 0; i < samples; i++) {
        sim.step(p, dt);
        double tilt = Math.abs(Math.toDegrees(sim.buoy.theta));
        sumTilt += tilt;
        if (tilt > maxTilt) maxTilt = tilt;
      }
      double meanTilt = sumTilt / samples;
      assertTrue(meanTilt < 20.0, preset.displayName + " mean tilt in 5 kt = " + meanTilt + "°");
      assertTrue(maxTilt < 30.0, preset.displayName + " peak tilt in 5 kt = " + maxTilt + "°");
    }
  }

  /** Wind ramps up at no more than the configured slew rate. */
  @Test
  void wind_rampsAtConfiguredRate() {
    SimParams p = baseParams(BuoyPreset.TDL_025M_S);
    p.windKnots.set(20.0);
    Simulation sim = new Simulation();
    double dt = 1.0 / 240.0;
    // After 1 second of stepping, actual wind should have risen by no more than 5 kt.
    for (int i = 0; i < 240; i++) sim.step(p, dt);
    assertTrue(
        sim.meanWindKnots <= 5.0 + 0.1,
        "After 1 s with 5 kt/s slew, wind should be ≤ 5 kt, was " + sim.meanWindKnots);
    // After 5 seconds total, wind should have reached 20 kt.
    for (int i = 0; i < 4 * 240; i++) sim.step(p, dt);
    assertEquals(
        20.0, sim.meanWindKnots, 0.5, "After 5 s, wind should have reached the 20 kt target");
  }

  /**
   * No matter how punishing the configuration (heavy killicks on very light rope, gusty wind, the
   * works), the simulation must never blow up to NaN or fly outside a sane bounding box. This
   * catches numerical instabilities in the rode integrator.
   */
  @Test
  void simulationStaysBounded_evenWithHeavyKillicksOnLightRope() {
    SimParams p = new SimParams();
    p.windKnots.set(15.0);
    p.gustTauS.set(30.0);
    p.depthM.set(9.0);
    p.fetchKm.set(50);
    p.buoy.set(BuoyPreset.TDL_025M_S);
    p.segments.setAll(
        RodeSegment.ofGauge(RodeSegment.Kind.CHAIN, "5/32", 12.0),
        RodeSegment.ofGauge(RodeSegment.Kind.ROPE, "1/8", 9.0));
    p.killicks.setAll(new KillickSpec(7.0, 6.0, 0.87), new KillickSpec(7.0, 10.0, 0.87));
    p.anchor.set(new Anchor(Anchor.Kind.MUSHROOM, 11.5, 0.87, 7.0));

    Simulation sim = new Simulation();
    double dt = 1.0 / 240.0;
    double totalRode = p.totalRodeLengthM();
    // Reasonable bounding box: buoy can drift up to ~total rode length horizontally either
    // way of the anchor, and y must stay between the seabed and ~5 m above the surface.
    double maxAbsX = totalRode * 2.0;
    double maxAboveSurfaceY = 5.0;

    for (int i = 0; i < 60 * 240; i++) {
      sim.step(p, dt);
      // Spot-check every few seconds — a NaN/explosion shows up immediately.
      if ((i & 0xFFF) == 0) {
        assertFalse(
            Double.isNaN(sim.buoy.x) || Double.isNaN(sim.buoy.y),
            "Buoy position went NaN at step " + i);
        assertTrue(
            Math.abs(sim.buoy.x) < maxAbsX,
            "Buoy flew outside bounding box: x=" + sim.buoy.x + " at step " + i);
        assertTrue(
            sim.buoy.y > -p.depthM.get() - 1 && sim.buoy.y < maxAboveSurfaceY,
            "Buoy y left the world: y=" + sim.buoy.y + " at step " + i);
      }
    }

    // Final check across the entire rode.
    for (int i = 0; i < sim.rode.n; i++) {
      assertFalse(
          Double.isNaN(sim.rode.x[i]) || Double.isNaN(sim.rode.y[i]),
          "Rode node " + i + " went NaN");
      assertTrue(
          Math.abs(sim.rode.x[i]) < maxAbsX, "Rode node " + i + " flew off in x: " + sim.rode.x[i]);
      assertTrue(
          sim.rode.y[i] > -p.depthM.get() - 1 && sim.rode.y[i] < maxAboveSurfaceY,
          "Rode node " + i + " out of y range: " + sim.rode.y[i]);
    }
  }

  /** Wind ramps DOWN at the configured rate too. */
  @Test
  void wind_rampsDown() {
    SimParams p = baseParams(BuoyPreset.TDL_025M_S);
    p.windKnots.set(30.0);
    Simulation sim = new Simulation();
    double dt = 1.0 / 240.0;
    for (int i = 0; i < 10 * 240; i++) sim.step(p, dt); // ramp up to 30
    // Now drop the target.
    p.windKnots.set(0.0);
    for (int i = 0; i < 240; i++) sim.step(p, dt);
    assertTrue(
        sim.meanWindKnots >= 25.0 - 0.1,
        "After 1 s of dropping wind, should be ≥ 25 kt, was " + sim.meanWindKnots);
  }
}
