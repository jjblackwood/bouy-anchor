# Buoy Anchor Simulator

<video src="docs/demo.webm" controls width="900" muted loop>
  Your browser doesn't render embedded video — see <a href="docs/demo.webm">docs/demo.webm</a>.
</video>

A side-on 2D dynamic simulator for evaluating moored buoy rigs. You pick a buoy
from a small library, set the depth, wind, chain, rope, anchor, and killicks,
and the simulator runs in real time so you can see how the rig behaves — how the
mark sits, tilts, and drifts; how the chain catenary forms; how forces propagate
from buoy through rode to anchor.

It also generates a static analysis report (text or Markdown) that runs the rig
at 5, 10, 15, 25, and 50 kt and reports the CCG anchoring verdict for each.

This project was developed with substantial AI assistance — see
[`AGENTS.md`](./AGENTS.md) for the design and architecture notes intended for
AI coding agents working on it.

## Quick start

Requirements: JDK 21, Maven, JavaFX 22+ (resolved by the `javafx-maven-plugin`).

```sh
mvn javafx:run
```

To run the test suite:

```sh
mvn test
```

## What's in the window

- **Top-left** — live side-view of the buoy, rode, and anchor. Toggle
  "Show forces" on the right panel to overlay scaled force vectors at the
  anchor, every 3 m along the chain and rope (plus each killick), and at the
  buoy attachment and wind-load points. The anchor turns red when the
  live chain pull would either drag it horizontally or lift it off the seabed.
- **Bottom-left** — live readout of mark properties, environment, rode
  composition, and the instantaneous state of the buoy (drift, tilt, wind,
  wave, and rode reaction forces). Hover any line for a tooltip explaining the
  abbreviations and units.
- **Right** — input controls running the full window height: mark selector,
  environment (wind setpoint, gust autocorrelation time τ, depth, fetch,
  bottom μ), chain/rope segments (add/remove/edit, per-segment material and
  MDF), anchor (kind, mass, MDF, holding factor), the killick list, and the
  **Run Analysis Report** button.

### Killicks

Killicks are added through the killick table. Each row carries:

- **mass** in kg,
- **dist** in m,
- **anchor** checkbox — when checked, the distance is measured from the anchor
  end of the rode; otherwise it's from the buoy end,
- **MDF** — material density factor (in-water weight fraction), defaulting to
  0.87 for steel.

### Wind

The setpoint is the slewed mean wind (slew rate 5 kt/s). Around that mean, an
Ornstein–Uhlenbeck fluctuation adds turbulence with σ = 20 % of the mean and an
autocorrelation time τ (default 30 s) — so the actual wind averages back to the
setpoint over a 30 s window, but at any instant you might see ±a knot or so on
a 5 kt setpoint and ±10 kt on a 50 kt setpoint.

## Marks in the preset library

- **TDL-0.25M-S** — TIDAL spar buoy, 10″ × 78″, manufacturer-published dimensions
  and sinking weight.
- **TDL-0.30M-S** — TIDAL stepped spar, 14.5″ wide float + 7.25″ tower, 86″ tall.
  Sinking weight is a published estimate.
- **Sur-Mark** — flotation collar + foam-filled top tube. Geometry inferred from
  buoyancy figures; not from a manufacturer datasheet.

Each preset uses real ballast distribution — typically ~90 % of dry mass at the
keel — which puts the centre of gravity within ~15 cm of the bottom and gives
the spar its self-righting behaviour. The simulator slices each section into
~10 cm axial segments, so stepped marks like the TDL-0.30M-S correctly displace
more water at the wider float than at the tower.

## Analysis report

Click **Run Analysis Report** to run a headless simulation at each of 5, 10, 15,
25, and 50 kt (about 30 s of wall time). The report includes:

- All parameters at the top.
- CCG anchoring check using per-material MDFs (chain, rope, killicks, and
  anchor each contribute their in-water weight).
- Per-wind steady-state metrics: tilt range, mean line tension, anchor pull,
  pass/fail verdict.
- Exportable as plain text or Markdown.

## Persistence

Inputs are saved automatically to a local config file (under
`$XDG_CONFIG_HOME/buoy-anchor/` or the platform equivalent) and reloaded on the
next launch.

## License

BSD 3-Clause — see [`LICENSE`](./LICENSE).
