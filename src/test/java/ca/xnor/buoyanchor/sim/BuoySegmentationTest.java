package ca.xnor.buoyanchor.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.xnor.buoyanchor.model.BuoyPreset;
import org.junit.jupiter.api.Test;

/** Verify the segment decomposition matches the preset (mass, geometry, CG, total volume). */
class BuoySegmentationTest {

  @Test
  void segmentMasses_sumToDryMass() {
    for (BuoyPreset p : BuoyPreset.LIBRARY) {
      Buoy b = new Buoy();
      b.configure(p);
      double sum = 0;
      for (Buoy.Segment s : b.segments) sum += s.mass;
      assertEquals(
          p.dryMassKg,
          sum,
          1e-6,
          p.displayName + ": Σ segment masses should equal preset dry mass");
    }
  }

  @Test
  void segmentLengths_sumToOverallSectionHeight() {
    for (BuoyPreset p : BuoyPreset.LIBRARY) {
      Buoy b = new Buoy();
      b.configure(p);
      double presetTotal = p.sections.stream().mapToDouble(BuoyPreset.Section::heightM).sum();
      double segTotal = 0;
      for (Buoy.Segment s : b.segments) segTotal += s.length;
      assertEquals(
          presetTotal,
          segTotal,
          1e-9,
          p.displayName + ": Σ segment lengths should equal Σ section heights");
    }
  }

  @Test
  void segmentVolume_matchesPresetVolume() {
    for (BuoyPreset p : BuoyPreset.LIBRARY) {
      Buoy b = new Buoy();
      b.configure(p);
      double presetVol = 0;
      for (BuoyPreset.Section s : p.sections) {
        presetVol += Math.PI * Math.pow(s.diameterM() / 2.0, 2) * s.heightM();
      }
      double segVol = 0;
      for (Buoy.Segment s : b.segments) segVol += s.area() * s.length;
      assertEquals(
          presetVol,
          segVol,
          1e-9,
          p.displayName + ": Σ segment volumes should equal Σ section volumes");
    }
  }

  @Test
  void cg_isLowOnSparBuoys_withBallast() {
    Buoy tdl25 = new Buoy();
    tdl25.configure(BuoyPreset.TDL_025M_S);
    Buoy tdl30 = new Buoy();
    tdl30.configure(BuoyPreset.TDL_030M_S);
    // With 90% ballast at 5–10 cm above keel, CG should be in the bottom quarter of the body.
    assertTrue(tdl25.cgAboveKeelM < 0.30, "TDL-0.25M-S CG too high: " + tdl25.cgAboveKeelM + " m");
    assertTrue(tdl30.cgAboveKeelM < 0.40, "TDL-0.30M-S CG too high: " + tdl30.cgAboveKeelM + " m");
  }

  @Test
  void inertia_isPositiveAndReasonable() {
    for (BuoyPreset p : BuoyPreset.LIBRARY) {
      Buoy b = new Buoy();
      b.configure(p);
      assertTrue(b.inertia > 0, p.displayName + ": inertia should be positive");
      // Should be of order m × (L/2)² or smaller for spar buoys with low CG.
      double slenderRod = p.dryMassKg * Math.pow(p.overallHeightM, 2) / 12.0;
      assertTrue(
          b.inertia < slenderRod * 2,
          p.displayName
              + ": inertia ("
              + b.inertia
              + ") shouldn't exceed 2× slender-rod ("
              + slenderRod
              + ")");
    }
  }
}
