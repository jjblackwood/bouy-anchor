package ca.xnor.buoyanchor.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry sanity: each preset's section volumes should add up to the manufacturer-published
 *  flotation volume. Mass breakdown should match the user-stated 90% ballast rule. */
class BuoyPresetTest {

    private static final double LB_TO_KG = 0.45359237;

    private static double totalSectionVolume(BuoyPreset p) {
        double v = 0;
        for (BuoyPreset.Section s : p.sections) {
            v += Math.PI * Math.pow(s.diameterM() / 2.0, 2) * s.heightM();
        }
        return v;
    }

    @Test
    void tdl025_sectionVolume_matchesManufacturer() {
        // Manufacturer: 0.088 m³ flotation volume.
        double v = totalSectionVolume(BuoyPreset.TDL_025M_S);
        assertEquals(0.088, v, 0.020,
                "TDL-0.25M-S section volume should be near manufacturer 0.088 m³");
    }

    @Test
    void tdl030_sectionVolume_matchesManufacturer() {
        // Manufacturer: 0.142 m³ flotation volume.
        double v = totalSectionVolume(BuoyPreset.TDL_030M_S);
        assertEquals(0.142, v, 0.020,
                "TDL-0.30M-S section volume should be near manufacturer 0.142 m³");
    }

    @Test
    void surMark_sectionVolume_matchesPresetSW() {
        // Stub + ring (80 lb) + tube (113 lb). SW (sinking weight) = ρ × V × g, so
        // V_total should match preset.sinkingWeightKg / 1000.
        double v = totalSectionVolume(BuoyPreset.SUR_MARK);
        double expectedM3 = BuoyPreset.SUR_MARK.sinkingWeightKg / 1000.0;
        assertEquals(expectedM3, v, 0.001,
                "Sur-Mark Σ section volume should match SW kg / 1000.");
    }

    @Test
    void allPresets_ballastIs90PercentOrSpecified() {
        // 90% rule for all marks per user.
        for (BuoyPreset p : BuoyPreset.LIBRARY) {
            double frac = p.ballastMassKg / p.dryMassKg;
            assertTrue(frac >= 0.85 && frac <= 0.95,
                    p.displayName + ": ballast fraction " + frac + " should be ~90%");
        }
    }

    @Test
    void allPresets_cgIsLow() {
        // With ~90% ballast at the keel, CG should sit in the lower quarter of the body for spar marks
        // and below the geometric centre for Sur-Mark.
        for (BuoyPreset p : BuoyPreset.LIBRARY) {
            double cg = p.cgAboveKeelM();
            double half = p.overallHeightM / 2.0;
            assertTrue(cg < half,
                    p.displayName + ": CG " + cg + " m should be well below geometric mid (" + half + ")");
        }
    }
}
