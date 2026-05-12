package ca.xnor.buoyanchor.sim;

/**
 * Wind/wave model (SPM/CERC depth- and fetch-limited Bretschneider), capped at 2 m for our Lake
 * Erie usage. See design.md §4.1.
 */
public final class Waves {

  public static final double G = 9.81;
  public static final double KNOTS_TO_MS = 0.514444;
  public static final double HS_CAP_M = 2.0;

  public final double u10; // wind speed at 10 m, m/s
  public final double hs; // significant wave height, m
  public final double tp; // peak period, s
  public final double uOrb; // peak surface horizontal orbital velocity, m/s
  public final double designDepth; // calm depth + Hs/2, m

  public Waves(double u10, double hs, double tp, double uOrb, double designDepth) {
    this.u10 = u10;
    this.hs = hs;
    this.tp = tp;
    this.uOrb = uOrb;
    this.designDepth = designDepth;
  }

  public static Waves fromWind(double windKnots, double depthM, double fetchKm) {
    double u10 = Math.max(0.01, windKnots * KNOTS_TO_MS);
    double depth = Math.max(0.1, depthM);
    double fetchM = Math.max(1.0, fetchKm * 1000.0);

    double xd = G * depth / (u10 * u10);
    double xF = G * fetchM / (u10 * u10);

    double A = Math.tanh(0.530 * Math.pow(xd, 0.75));
    double B = Math.tanh(0.0125 * Math.pow(xF, 0.42) / A);
    double hs = Math.min(0.283 * A * B * u10 * u10 / G, HS_CAP_M);

    double C = Math.tanh(0.833 * Math.pow(xd, 0.375));
    double D = Math.tanh(0.0379 * Math.pow(xF, 1.0 / 3.0) / C);
    double tp = 7.54 * C * D * u10 / G;

    double uOrb = (hs / 2.0) * Math.sqrt(G / depth);
    double designDepth = depth + hs / 2.0;

    return new Waves(u10, hs, tp, uOrb, designDepth);
  }

  /** Time-varying horizontal orbital velocity at the surface (peak amplitude uOrb, period tp). */
  public double surfaceVelocity(double tSeconds) {
    if (tp <= 1e-6) return 0.0;
    return uOrb * Math.sin(2.0 * Math.PI * tSeconds / tp);
  }

  /** Time-varying horizontal orbital acceleration at the surface (= du/dt). */
  public double surfaceAcceleration(double tSeconds) {
    if (tp <= 1e-6) return 0.0;
    double omega = 2.0 * Math.PI / tp;
    return uOrb * omega * Math.cos(omega * tSeconds);
  }
}
