package ca.xnor.buoyanchor.sim;

import ca.xnor.buoyanchor.model.RodeSegment;
import java.util.List;

/**
 * Discrete rode: an ordered list of {@link RodeSegment}s discretized as a chain of point masses
 * connected by stiff distance constraints. Verlet integration + Position-Based Dynamics with
 * inverse-mass weighting, seabed contact, anchor pin at node 0, and the buoy occupying the top node
 * (its mass is set externally).
 *
 * <p>Coordinates: world, y-up, metres. Anchor at (anchorX, anchorY).
 *
 * <p>Node 0 = anchor end. Node n-1 = buoy. Segments are laid down anchor → buoy.
 */
public final class Rode {

  public static final double TARGET_LINK_M = 0.10;

  /**
   * PBD iterations per step with bidirectional Gauss-Seidel sweeps so info propagates both ways
   * each iteration. 64 was empirically sufficient — the trace at 200 was identical, so the system
   * converges well before that. Most rode links are slack and skipped, so this is cheaper than the
   * worst case.
   */
  public static final int CONSTRAINT_ITERATIONS = 64;

  public static final double GRAVITY = 9.81;
  public static final double CD_LINE = 1.2;
  public static final double WATER_DENSITY = 1000.0;

  public int n;
  public double[] x = new double[0];
  public double[] y = new double[0];
  public double[] px = new double[0];
  public double[] py = new double[0];
  public double[] mass = new double[0];
  public double[] invMass = new double[0];
  public double[] accelX = new double[0];
  public double[] accelY = new double[0];

  /** Material kind at each node (from the segment that node lives in). */
  public RodeSegment.Kind[] kind = new RodeSegment.Kind[0];

  /** Hydrodynamic line diameter at each node (from the segment that node lives in). */
  public double[] nodeDiam = new double[0];

  /** True if a killick has been attached at this node. */
  public boolean[] killick = new boolean[0];

  public double[] linkLen = new double[0];
  public boolean[] pinned = new boolean[0];

  public double anchorX, anchorY;
  public double seabedY;
  public double bottomMu = 0.6;
  public double linDamping = 0.999; // tiny numerical floor; real damping is hydrodynamic now

  /**
   * Constraint reaction recorded at the top node after the last step. This is the force the rode
   * exerts on the buoy (Newtons, world frame).
   */
  public double topConstraintFx, topConstraintFy;

  /**
   * Tension the bottom rode link applies to the anchor pin (Newtons, world frame). +Fy = pulled
   * upward (lifting); large |Fx| = pulled sideways (drag). Derived from the cumulative PBD
   * corrections that link 0-1 applies to node 1 across all iterations: the impulse needed to keep
   * node 1 at rest length from the (infinite-mass) anchor pin is the reaction the pin feeds back
   * into the chain.
   */
  public double anchorPullFx, anchorPullFy;

  /** Allocate / re-discretize for the given ordered segments (anchor → buoy). */
  public void rebuild(
      List<RodeSegment> segments, double initialBuoyAttachX, double initialBuoyAttachY) {
    // Per-segment discretization: at least one link, aiming for TARGET_LINK_M per link.
    int segCount = segments.size();
    int[] segLinks = new int[segCount];
    double[] segStep = new double[segCount];
    int totalLinks = 0;
    double totalLen = 0;
    for (int s = 0; s < segCount; s++) {
      double L = Math.max(0, segments.get(s).lengthM.get());
      int links = Math.max(1, (int) Math.round(L / TARGET_LINK_M));
      segLinks[s] = links;
      segStep[s] = (links > 0) ? L / links : 0;
      totalLinks += links;
      totalLen += L;
    }

    n = totalLinks + 1;
    x = new double[n];
    y = new double[n];
    px = new double[n];
    py = new double[n];
    mass = new double[n];
    invMass = new double[n];
    accelX = new double[n];
    accelY = new double[n];
    kind = new RodeSegment.Kind[n];
    nodeDiam = new double[n];
    killick = new boolean[n];
    linkLen = new double[n - 1];
    pinned = new boolean[n];

    // Walk segments anchor→buoy, filling linkLen[] and the node kind[].
    // Node i is at arc-length s[i] from the anchor; nodes 0 and n-1 are end caps.
    int idx = 0;
    if (segCount > 0) {
      kind[0] = segments.get(0).kind.get();
      nodeDiam[0] = segments.get(0).diameterM.get();
    } else {
      kind[0] = RodeSegment.Kind.CHAIN;
      nodeDiam[0] = 0.010;
    }
    for (int s = 0; s < segCount; s++) {
      RodeSegment.Kind k = segments.get(s).kind.get();
      double d = segments.get(s).diameterM.get();
      for (int j = 0; j < segLinks[s]; j++) {
        linkLen[idx] = segStep[s];
        idx++;
        kind[idx] = k;
        nodeDiam[idx] = d;
      }
    }

    // Lay nodes along the straight line anchor → initial buoy attach.
    double dxA = initialBuoyAttachX - anchorX;
    double dyA = initialBuoyAttachY - anchorY;
    double s = 0;
    for (int i = 0; i < n; i++) {
      if (i > 0) s += linkLen[i - 1];
      double t = (totalLen > 0) ? s / totalLen : 0;
      x[i] = anchorX + t * dxA;
      y[i] = anchorY + t * dyA;
      px[i] = x[i];
      py[i] = y[i];
    }
    pinned[0] = true;
  }

  /**
   * Recompute per-node mass, inverse-mass, and net downward acceleration for non-top nodes. Top
   * node (buoy) is left alone — caller sets it via setBuoyNode.
   *
   * <p>Killicks attach at the node closest to the requested distance, measured along the rode from
   * either the buoy or the anchor depending on each killick's {@code fromAnchor} flag.
   */
  // The four killick arrays are parallel (entry i describes one killick); making the last one
  // varargs would mislead callers into thinking they could pass per-killick mdf values inline.
  @SuppressWarnings("PMD.UseVarargs")
  public void recomputeMasses(
      List<RodeSegment> segments,
      double[] killickMassKg,
      double[] killickDistM,
      boolean[] killickFromAnchor,
      double[] killickMdf) {
    // Build per-node kgPerM and MDF by walking the segment list once.
    double[] nodeKgPerM = new double[n];
    double[] nodeMdf = new double[n];
    int nodeIdx = 0;
    for (int s = 0; s < segments.size(); s++) {
      RodeSegment seg = segments.get(s);
      double kg = seg.kgPerM.get();
      double mdf = seg.mdf.get();
      // Re-derive this segment's link count the same way rebuild() does, then advance nodeIdx.
      // Total node count for this segment = links + (s==0 ? 1 : 0) (start node shared).
      double L = Math.max(0, seg.lengthM.get());
      int links = Math.max(1, (int) Math.round(L / TARGET_LINK_M));
      int nodesInThisSeg = (s == 0) ? links + 1 : links;
      for (int j = 0; j < nodesInThisSeg; j++) {
        int nIdx = nodeIdx + j;
        if (nIdx >= n) break;
        nodeKgPerM[nIdx] = kg;
        nodeMdf[nIdx] = mdf;
      }
      nodeIdx += nodesInThisSeg;
    }

    // Reset killick flags before re-applying.
    for (int i = 0; i < n; i++) killick[i] = false;

    for (int i = 0; i < n - 1; i++) {
      double len = 0;
      if (i > 0) len += linkLen[i - 1] * 0.5;
      if (i < n - 1) len += linkLen[i] * 0.5;
      mass[i] = Math.max(1e-6, nodeKgPerM[i] * len);
      invMass[i] = pinned[i] ? 0 : 1.0 / mass[i];
      accelX[i] = 0;
      accelY[i] = -nodeMdf[i] * GRAVITY;
    }

    if (killickMassKg != null && killickMassKg.length > 0) {
      // Cumulative arc-length from the buoy (node n-1) back toward the anchor (node 0):
      // distFromBuoy[i] = sum of linkLen[j] for j in [i..n-2].
      double totalLen = 0;
      for (int j = 0; j < n - 1; j++) totalLen += linkLen[j];
      double[] distFromBuoy = new double[n];
      distFromBuoy[n - 1] = 0;
      for (int i = n - 2; i >= 0; i--) {
        distFromBuoy[i] = distFromBuoy[i + 1] + linkLen[i];
      }

      for (int k = 0; k < killickMassKg.length; k++) {
        boolean fromAnchor =
            killickFromAnchor != null && k < killickFromAnchor.length && killickFromAnchor[k];
        double rawDist = killickDistM[k];
        double d =
            fromAnchor
                ? Math.max(0, Math.min(totalLen, totalLen - rawDist))
                : Math.max(0, Math.min(totalLen, rawDist));
        // Find node with closest distFromBuoy to d.
        int idxK = n - 1;
        double bestErr = Math.abs(distFromBuoy[n - 1] - d);
        for (int i = n - 2; i >= 0; i--) {
          double err = Math.abs(distFromBuoy[i] - d);
          if (err < bestErr) {
            bestErr = err;
            idxK = i;
          }
        }
        if (idxK <= 0) idxK = 1; // not on the anchor
        if (idxK >= n - 1) idxK = n - 2; // not on the buoy
        double extra = killickMassKg[k];
        double kMdf = (killickMdf != null && k < killickMdf.length) ? killickMdf[k] : 0.87;
        double oldDownForce = (-accelY[idxK]) * mass[idxK];
        mass[idxK] += extra;
        invMass[idxK] = 1.0 / mass[idxK];
        killick[idxK] = true;
        double presentForce = oldDownForce + kMdf * GRAVITY * extra;
        accelY[idxK] = -presentForce / mass[idxK];
      }
    }
  }

  /** Set the buoy node's mass and per-step external acceleration (world frame, m/s²). */
  public void setBuoyNode(double buoyMass, double accelXBuoy, double accelYBuoy) {
    int top = n - 1;
    mass[top] = buoyMass;
    invMass[top] = 1.0 / buoyMass;
    accelX[top] = accelXBuoy;
    accelY[top] = accelYBuoy;
  }

  public void step(double dt) {
    if (n < 2) return;
    double dt2 = dt * dt;

    int top = n - 1;

    // Verlet integrate, with hydrodynamic drag on chain/rope nodes computed from their
    // previous-step velocity. (Buoy node = top: already has its acceleration set externally
    // including its own per-segment drag.)
    for (int i = 0; i < n; i++) {
      if (pinned[i]) {
        px[i] = x[i];
        py[i] = y[i];
        continue;
      }
      double vx = (x[i] - px[i]) * linDamping;
      double vy = (y[i] - py[i]) * linDamping;
      double aX = accelX[i];
      double aY = accelY[i];
      if (i != top) {
        // Hydrodynamic drag: F = 0.5 ρ Cd A_side |v| v. Applied in *semi-implicit*
        // form — we damp the velocity multiplicatively rather than as an explicit
        // acceleration. The explicit form fails when drag*dt/mass exceeds 1.0 (drag
        // flips velocity sign, then amplifies it next step). That happens routinely on
        // light line like 1/8" rope at 0.0014 kg per node. The implicit form
        //
        //     v_new = v / (1 + q*dt/m)
        //
        // is unconditionally stable: even infinite drag only brings v to 0.
        double diam = nodeDiam[i] > 0 ? nodeDiam[i] : 0.010;
        double linkLength = (i > 0 ? linkLen[i - 1] * 0.5 : 0) + (i < n - 1 ? linkLen[i] * 0.5 : 0);
        double Aside = linkLength * diam;
        double speed = Math.hypot(vx, vy) / dt;
        if (speed > 1e-5) {
          double q = 0.5 * WATER_DENSITY * CD_LINE * Aside * speed;
          double damp = 1.0 / (1.0 + q * dt / mass[i]);
          vx *= damp;
          vy *= damp;
        }
      }
      double newX = x[i] + vx + aX * dt2;
      double newY = y[i] + vy + aY * dt2;
      px[i] = x[i];
      py[i] = y[i];
      x[i] = newX;
      y[i] = newY;
    }

    // Snapshot top node's position immediately after Verlet (= position with NO tension applied).
    // The PBD correction below moves it to its constrained position; the displacement times
    // mass/dt² is the constraint reaction force.
    double topVerletX = x[top];
    double topVerletY = y[top];

    // Accumulate the corrections that link 0-1 specifically applies to node 1 across all PBD
    // sweeps. Node 0 is pinned (invMass=0), so the link's full impulse is delivered to node 1;
    // total displacement × mass[1] / dt² is then the force the anchor pin had to feed back.
    double link01CorrX = 0, link01CorrY = 0;

    for (int iter = 0; iter < CONSTRAINT_ITERATIONS; iter++) {
      // Bidirectional Gauss-Seidel: alternate forward and backward sweeps so constraint
      // information propagates both ways each iteration. Forward propagates anchor→buoy;
      // backward propagates buoy→anchor. Without this, force-propagation distance is
      // ~iter/2 nodes per step, leading to a "rode catching up to buoy" lag.
      int iStart = (iter & 1) == 0 ? 0 : (n - 2);
      int iStep = (iter & 1) == 0 ? +1 : -1;
      int iEnd = (iter & 1) == 0 ? (n - 1) : -1;
      for (int i = iStart; i != iEnd; i += iStep) {
        double dx = x[i + 1] - x[i];
        double dy = y[i + 1] - y[i];
        double dist = Math.hypot(dx, dy);
        if (dist < 1e-9) continue;
        if (dist <= linkLen[i]) continue; // slack
        double w0 = invMass[i];
        double w1 = invMass[i + 1];
        double wsum = w0 + w1;
        if (wsum < 1e-12) continue;
        double k0 = w0 / wsum;
        double k1 = w1 / wsum;

        // Treat all material as a hard distance constraint at rest length. Real nylon at
        // typical operating tensions stretches < 1% — only at near-break loads (not
        // modelled here) would stretch matter.
        double targetLen = linkLen[i];
        double diff = (dist - targetLen) / dist;
        x[i] += dx * diff * k0;
        y[i] += dy * diff * k0;
        x[i + 1] -= dx * diff * k1;
        y[i + 1] -= dy * diff * k1;
        if (i == 0) {
          link01CorrX -= dx * diff * k1;
          link01CorrY -= dy * diff * k1;
        }
      }
      // Anchor pin.
      x[0] = anchorX;
      y[0] = anchorY;

      // Seabed contact.
      for (int i = 0; i < n; i++) {
        if (y[i] < seabedY) {
          double clampedY = seabedY;
          y[i] = clampedY;
          double vx = x[i] - px[i];
          double frictionStep = bottomMu * 0.05;
          px[i] = x[i] - vx * (1.0 - Math.min(1.0, frictionStep));
          py[i] = clampedY;
        }
      }
    }

    // Constraint reaction on the top node (= force the rode applies to the buoy this step).
    double dxCorr = x[top] - topVerletX;
    double dyCorr = y[top] - topVerletY;
    topConstraintFx = mass[top] * dxCorr / dt2;
    topConstraintFy = mass[top] * dyCorr / dt2;

    // Anchor pull = reaction to the impulse link 0-1 applied to node 1. node 1 was pulled
    // toward node 0 by (link01CorrX, link01CorrY); the equal-and-opposite reaction on the
    // anchor points toward node 1. (n >= 2 is guaranteed by the early return above.)
    if (dt2 > 0) {
      anchorPullFx = -mass[1] * link01CorrX / dt2;
      anchorPullFy = -mass[1] * link01CorrY / dt2;
    } else {
      anchorPullFx = 0;
      anchorPullFy = 0;
    }
  }
}
