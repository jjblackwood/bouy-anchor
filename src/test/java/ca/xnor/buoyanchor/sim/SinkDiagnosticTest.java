package ca.xnor.buoyanchor.sim;

import ca.xnor.buoyanchor.model.BuoyPreset;
import ca.xnor.buoyanchor.model.RodeSegment;
import ca.xnor.buoyanchor.model.SimParams;
import org.junit.jupiter.api.Test;

/** Trace how the buoy sinks to the seabed over time. Not asserting — just printing. */
class SinkDiagnosticTest {

  @Test
  void traceTdl030_in_5kt() {
    SimParams p = new SimParams();
    // Use user's actual defaults — 4 killicks, 12 kt, 20 km fetch
    p.windKnots.set(12.0);
    p.depthM.set(8.0);
    p.fetchKm.set(20);
    p.segments.setAll(
        new RodeSegment(RodeSegment.Kind.CHAIN, 12.0, 1.0, 0.87),
        new RodeSegment(RodeSegment.Kind.ROPE, 16.0, 0.15, 0.12));
    p.buoy.set(BuoyPreset.TDL_025M_S);

    Simulation sim = new Simulation();
    double dt = 1.0 / 240.0;
    double[] cps = {0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0};
    int next = 0;
    double t = 0;
    double maxTilt = 0;
    for (int s = 0; s < 60 * 240; s++) {
      sim.step(p, dt);
      t += dt;
      double tilt = Math.toDegrees(sim.buoy.theta);
      if (Math.abs(tilt) > Math.abs(maxTilt)) maxTilt = tilt;
      if (next < cps.length && t >= cps[next]) {
        System.out.printf(
            "t=%5.1fs  tilt=%+6.1f°  y=%6.2f  Fwind=%5.2f N  Fline=(%+6.1f, %+6.1f)  Fb=%6.1f%n",
            t,
            tilt,
            sim.buoy.y,
            sim.buoy.Fwind,
            sim.rode.topConstraintFx,
            sim.rode.topConstraintFy,
            sim.buoy.FbuoyancyTotal);
        next++;
      }
    }
    System.out.printf("MAX TILT seen: %+.1f°%n", maxTilt);
  }

  @Test
  void traceSinking() {
    SimParams p = new SimParams();
    p.killicks.clear();
    p.windKnots.set(0);
    p.depthM.set(8.0);
    p.fetchKm.set(50);
    p.segments.setAll(
        new RodeSegment(RodeSegment.Kind.CHAIN, 15.0, 2.5, 0.87),
        new RodeSegment(RodeSegment.Kind.ROPE, 12.0, 0.15, 0.12));
    p.buoy.set(BuoyPreset.TDL_025M_S);

    Simulation sim = new Simulation();
    double dt = 1.0 / 240.0;

    double[] checkpoints = {0.0, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0};
    int next = 0;
    double t = 0;
    for (int step = 0; step < 60 * 240; step++) {
      sim.step(p, dt);
      t += dt;
      if (next < checkpoints.length && t >= checkpoints[next]) {
        int onBottom = 0;
        double minRodeY = 0;
        for (int i = 0; i < sim.rode.n; i++) {
          if (sim.rode.y[i] <= sim.rode.seabedY + 0.05) onBottom++;
          if (sim.rode.y[i] < minRodeY) minRodeY = sim.rode.y[i];
        }
        System.out.printf(
            "t=%6.2fs   y=%7.3f   tilt=%+6.1f°   Fb=%+7.1f   mg=%6.1f   Fy=%+7.1f   Fline_y=%+7.1f   onBot=%3d%n",
            t,
            sim.buoy.y,
            Math.toDegrees(sim.buoy.theta),
            sim.buoy.FbuoyancyTotal,
            sim.buoy.mass * 9.81,
            sim.buoy.FbuoyancyTotal - sim.buoy.mass * 9.81,
            sim.rode.topConstraintFy,
            onBottom);
        next++;
      }
    }
  }
}
