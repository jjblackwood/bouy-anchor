# AGENTS.md

Notes for AI coding agents working on this repository. Read this before making
non-trivial changes.

This project was developed iteratively with AI assistance. Most of the source
files have been touched many times during physics calibration; the patterns
below are the result of that process and are worth preserving.

## What it is

A continuously-running 2D side-view dynamic simulator for a moored buoy on a
chain-and-rope rode. The buoy is a JavaFX rigid body modelled as a stack of
cylindrical segments; the rode is a Verlet/PBD particle chain; wave and wind
forcing are derived from a Bretschneider sea-state model.

The build is Maven, the JDK target is 21, and JavaFX 22+ is required. Run with
`mvn javafx:run`. Tests are JUnit 5 under `src/test/java`.

## Source tree

```
src/main/java/ca/xnor/buoyanchor/
  App.java                — JavaFX entry, nested SplitPane: canvas + readout
                            stacked vertically on the left, controls full-height on the right
  model/
    SimParams.java        — observable parameters bound to the UI
    BuoyPreset.java       — preset library (TDL-0.25M-S, TDL-0.30M-S, Sur-Mark)
    KillickSpec.java      — observable killick (mass + distance + fromAnchor flag + MDF)
  sim/
    Simulation.java       — orchestrates step(): buoy forces + rode PBD + rotation
    Buoy.java             — segmented rigid body, force / torque computation
    Rode.java             — Verlet + PBD chain with per-node drag and rope stretch
    Waves.java             — Bretschneider Hs/Tp, surface velocity / acceleration
    Analysis.java         — headless multi-wind analysis + text/markdown formatting
  ui/
    SimView.java          — Canvas, AnimationTimer, world↔screen transform
    ReadoutPanel.java     — live state readout in 4 horizontal columns
                            (Mark / Environment / Rode / State), with tooltips
                            explaining each abbreviation
    ControlPanel.java     — right-hand inputs + analysis button + force toggle
    KillickPanel.java     — add/remove/edit killicks
    AnalysisDialog.java   — modeless window with progress bar + export
```

(Maven artifact: `buoy-anchor-simulator`. Java package: `ca.xnor.buoyanchor`.)

## Physics model — what's modelled and why

### Buoy as a stack of segments

Each preset's `Section`s (cylindrical sub-bodies) are sliced into ~10 cm axial
segments at `Buoy.configure()`. Each segment carries:

- Its axial position range and radius (from the preset geometry).
- A mass: `(slice_volume / total_volume) × shell_mass`, plus the **ballast lump**
  (typically ~90% of dry mass) is added to whichever slice contains
  `ballastHeightAboveKeelM`.

Mass, CG (along body axis), and rotational inertia about CG are derived from the
segment list rather than from analytical formulas. This makes the spar's
self-righting behaviour fall out of the geometry correctly — for the TDL spars
the CG ends up at 13–17 cm above the keel.

Buoyancy is computed **per segment** via `submergedAxisRange()`, which slices
each segment against the local water surface plane (accounting for tilt). This
gives a tilt-invariant total buoyancy (a horizontal cylinder displaces the same
water as a vertical one) and the correct per-segment centroid for torque.

### Forces on the buoy

For each segment, `computeNonTensionForcesAndTorque()` accumulates:

- **Gravity** at the segment centroid.
- **Buoyancy** = `ρ × area × submerged_axis_length × g` at the submerged
  centroid.
- **Hydrodynamic drag** against `v_segment − v_water`, where `v_segment`
  combines the buoy's translational velocity and the rotation-induced tangential
  velocity `ω × r`, and `v_water` is the wave orbital velocity inside the top
  Hs band and zero deeper down. This is the **only source of damping** —
  there is no fictional `ANG_DAMP` or `LIN_DAMP` term in this file.
- **Morison inertial force** `ρ × Cm × V × du/dt` on submerged-and-near-surface
  segments. `Cm = 1.0` is correct for slim surface-piercing cylinders (one
  displaced volume to accelerate, not two as for a fully-submerged cylinder).
- **Wind drag** on the emerged portion of each segment, using relative velocity
  `v_segment − v_wind` so a buoy drifting downwind experiences reduced drag.

Wave-radiation damping for pitch is added in `integrateRotation()`, scaled from
the second moment of the displaced volume about the CG over a shallow-water
timescale. For heave a slim cylinder doesn't radiate much (axisymmetric motion),
so we do not model heave radiation damping.

There is intentionally **no end-cap axial drag**: the TDL-series buoys are
rotomoulded with smooth rounded bottoms, so axial flow stays attached and the
contribution is negligible.

### Sign convention for tilt

`theta = 0` is upright. `+theta` rotates the body axis from `+y` (world up)
toward `+x` (downwind). That is a clockwise rotation in the standard 2D frame,
so `ω_my = -ω_std`. The rotational integrator therefore **negates the standard
CCW-positive torque** before applying it to `omega`. Multiple physics bugs in
the project's history came from getting this wrong; don't change the
`tau = -tauStd` line without thinking it through.

### Rope and chain (Rode.java)

Verlet integration with inverse-mass-weighted Position-Based Dynamics distance
constraints. Each step does **bidirectional Gauss-Seidel** passes (alternating
forward/backward) so constraint information propagates both ways through the
rode in one step.

- The buoy **is** the top rode node. `Simulation.setBuoyNode(mass, ax, ay)`
  pushes the buoy's mass and net non-tension acceleration onto the top node;
  PBD then handles the buoy's translation as a side effect of the constraint
  solve. After the step, the buoy's tilt-torque comes from the constraint
  reaction force `Rode.topConstraintFx/Fy` (computed by tracking the position
  correction from pre-PBD to post-PBD).
- Constraints are **one-way**: a link only corrects when stretched
  (`dist > rest_length`). Slack chain/rope doesn't push — it just goes slack.
- **Rope** is treated with the same hard distance constraint as chain. At
  typical mooring tensions (~100 N) real nylon rope stretches < 1% — it's
  effectively rigid in normal operation. The 20%-at-half-break stretch only
  matters near catastrophic load, which we don't model.
- **Per-node line drag** computes hydrodynamic drag against each node's
  velocity using `CD_LINE × A_side × |v| × v`. With this, the artificial
  `linDamping = 0.999` is only a tiny numerical floor.
- **Killicks** are realised as extra mass on the rope node closest to the
  specified distance — measured from either the buoy or the anchor end
  depending on each killick's `fromAnchor` flag. The killick's MDF blends into
  the node's effective in-water acceleration weighted by mass.
- **Seabed contact** clamps any node to `y ≥ seabedY` and applies horizontal
  friction (`bottomMu × 0.05` per iteration) to that node's implied velocity.
- **Anchor pull estimate.** Node 0 is hard-pinned (`invMass = 0`) so the
  anchor's reaction force isn't recoverable from a position correction. Instead
  `Rode.anchorPullFx/Fy` is computed by accumulating the per-iteration PBD
  corrections that link 0-1 applies to node 1 across all sweeps; that summed
  displacement × `mass[1] / dt²` is the impulse the pin had to feed back. The
  SimView turns the anchor red when `|Fx| > mass × g × holdingFactor` (drag) or
  `Fy > mass × g × mdf` (lift). It's a heuristic, not a fully-rigorous tension
  reading, but it tracks the analysis report's thresholds.

### Wave model

`Waves.fromWind(U10, depth, fetch)` returns Hs, Tp, surface orbital velocity,
and design depth using SPM/CERC depth- and fetch-limited Bretschneider:

```
xd = g·d / U²
xF = g·F / U²
Hs = min(0.283 × tanh(0.530·xd^0.75) × tanh(0.0125·xF^0.42 / A) × U² / g , 2.0)
Tp = 7.54 × tanh(0.833·xd^0.375) × tanh(0.0379·xF^(1/3) / C) × U / g
```

Wind ramps at max 5 kt/s. The actual Hs and Tp **slew toward the target** with
τ = 90 s (so the sea takes ~3 minutes to fully develop after a wind shift —
shorter than reality, long enough to look right). The wave phase `wavePhase` is
accumulated continuously as `Σ ω·dt`, so changes in Tp don't make waves visually
warp/jump.

### Wind fluctuation

Around the slew-limited mean, an Ornstein–Uhlenbeck process adds turbulence:

- σ = `WIND_SIGMA_FRACTION × meanWindKnots` (currently 20 %), so a 5 kt setpoint
  has σ ≈ 1 kt and a 50 kt setpoint has σ ≈ 10 kt. This replaced an earlier
  "Gust (kt)" peak-spec input — turbulence intensity is the more honest knob.
- The autocorrelation time is `SimParams.gustTauS` (default 30 s); the actual
  wind averages back to the setpoint over roughly that window.

The buoy's wind drag reads `waves.u10`, which carries the *instantaneous*
(mean + OU) wind — gusts produce real spikes in Fwind. Hs/Tp/uOrb on the same
record are derived from the slow-moving mean only; brief gusts don't grow
waves, sustained wind does.

### MDFs

Per-material `MDF` (fraction of dry weight retained as a downward force in
water) lives in `SimParams`:

- Chain / killick / anchor steel: 0.87
- Nylon rope: 0.12

These are used both in the live simulation (each rode node's gravity term is
`-MDF × g`) and in the CCG check (M_actual_eff sums each component × its MDF).

## Behaviours to preserve

- **No fictional damping.** All energy dissipation comes from physical drag.
  Don't add `ANG_DAMP × ω` or velocity-multiplication terms unless they trace
  to a real fluid-mechanics mechanism.
- **No fudged geometry.** Mark dimensions match the manufacturer specs (where
  published) or are explicitly back-derived from a stated buoyancy. Don't
  shrink a section to make the simulation "look better"; either accept the
  physics or add the physical mechanism that explains the discrepancy.
- **Per-material MDFs.** Steel chain, nylon rope, steel killicks, steel anchor
  each have their own MDF. CCG calculations sum these component-by-component
  rather than applying a single MDF to the total.
- **Wave continuity.** The wave model accumulates phase. Don't replace
  `wavePhase` with `omega × simTime` — that re-introduces the visible warp on
  wind changes.
- **Bidirectional PBD sweeps.** Forward-only Gauss-Seidel takes far too many
  iterations to propagate force changes through a 270-node rode.

## Testing

JUnit 5 tests under `src/test/java/ca/xnor/buoyanchor/`:

- `model/BuoyPresetTest` — preset volumes match manufacturer figures within
  tolerance, ballast is ~90% of dry mass, CG is below geometric mid.
- `sim/BuoySegmentationTest` — segments sum to preset mass / volume / height,
  CG is computed correctly.
- `sim/BuoyancyTest` — free-buoy equilibrium, buoyancy increases with
  submersion, fully-submerged buoyancy is tilt-invariant, tilted spar produces
  restoring torque.
- `sim/SimulationFloatingTest` — end-to-end coupled buoy + rode in calm water.
  This is the regression test that catches "buoy sinks to seabed" bugs.
- `sim/StabilityTest` — buoy stays upright in calm and doesn't flip past 30°
  in 5 kt; wind ramps at the configured rate.
- `sim/SinkDiagnosticTest` — non-asserting traces used for debugging.

Always `mvn test` after physics changes. The full suite is fast (≈ 1 minute).
