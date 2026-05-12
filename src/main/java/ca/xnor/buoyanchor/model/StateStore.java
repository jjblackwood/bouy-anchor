package ca.xnor.buoyanchor.model;

import javafx.animation.PauseTransition;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.collections.ListChangeListener;
import javafx.util.Duration;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the current SimParams to a YAML state file in the user config dir, and restores it on
 * startup. Edits to any wired property trigger a debounced save (300 ms quiet period) so we don't
 * hammer the disk while a slider is being dragged.
 *
 * State lives in {@link ConfigPaths#appConfigDir()}/state.yaml.
 */
public final class StateStore {

    public static final String FILENAME = "state.yaml";

    private final SimParams params;
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));

    private StateStore(SimParams params) {
        this.params = params;
        debounce.setOnFinished(e -> saveNow());
    }

    /** Build a store for {@code params}, restore any previously saved state into it, and wire up
     *  listeners so future changes auto-save. Returns the store (mostly for tests). */
    public static StateStore attach(SimParams params) {
        StateStore s = new StateStore(params);
        s.restoreIfPresent();
        s.wireListeners();
        return s;
    }

    public Path stateFile() { return ConfigPaths.appConfigDir().resolve(FILENAME); }

    // -------------------- restore --------------------

    private void restoreIfPresent() {
        Path f = stateFile();
        if (!Files.isRegularFile(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object o = yaml.load(in);
            if (o instanceof Map<?, ?> raw) applyState(raw);
        } catch (IOException | RuntimeException e) {
            System.err.println("StateStore: failed to load " + f + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void applyState(Map<?, ?> raw) {
        Map<String, Object> m = (Map<String, Object>) raw;

        Object buoyName = m.get("buoy");
        if (buoyName != null) {
            BuoyPreset p = BuoyPreset.byName(buoyName.toString());
            if (p != null) params.buoy.set(p);
        }
        setIfNum(m, "wind",         params.windKnots);
        setIfNum(m, "gustTau",      params.gustTauS);
        setIfNum(m, "depth",        params.depthM);
        setIfNum(m, "fetch",        params.fetchKm);
        setIfNum(m, "waterDensity", params.waterDensity);
        setIfNum(m, "airDensity",   params.airDensity);
        setIfNum(m, "bottomMu",     params.bottomMu);
        Object sf = m.get("showForces");
        if (sf instanceof Boolean b) params.showForces.set(b);

        Object anchorRaw = m.get("anchor");
        if (anchorRaw instanceof Map) {
            Map<String, Object> am = (Map<String, Object>) anchorRaw;
            Anchor a = new Anchor(
                    parseAnchorKind(am.get("kind")),
                    num(am.get("massKg"), params.anchor.get().massKg.get()),
                    num(am.get("mdf"),    params.anchor.get().mdf.get()),
                    num(am.get("holdingFactor"), params.anchor.get().holdingFactor.get()));
            params.anchor.set(a);
        }

        Object segsRaw = m.get("segments");
        if (segsRaw instanceof List) {
            List<RodeSegment> segs = new ArrayList<>();
            for (Object item : (List<?>) segsRaw) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> sm = (Map<String, Object>) item;
                RodeSegment.Kind kind = parseSegmentKind(sm.get("kind"));
                double len = num(sm.get("lengthM"), 0);
                String gauge = sm.get("gauge") != null ? sm.get("gauge").toString() : "";
                RodeSegment.Gauge g = RodeSegment.findGauge(kind, gauge);
                if (g != null) {
                    segs.add(RodeSegment.ofGauge(kind, gauge, len));
                } else {
                    RodeSegment s = new RodeSegment(kind,
                            sm.get("name") != null ? sm.get("name").toString() : "",
                            len,
                            num(sm.get("kgPerM"), 0),
                            num(sm.get("mdf"),    kind == RodeSegment.Kind.CHAIN ? 0.85 : 0.12));
                    s.diameterM.set(num(sm.get("diameterM"),
                            kind == RodeSegment.Kind.CHAIN ? 0.006 : 0.010));
                    segs.add(s);
                }
            }
            if (!segs.isEmpty()) params.segments.setAll(segs);
        }

        Object killRaw = m.get("killicks");
        if (killRaw instanceof List) {
            List<KillickSpec> ks = new ArrayList<>();
            for (Object item : (List<?>) killRaw) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> km = (Map<String, Object>) item;
                KillickSpec kk = new KillickSpec(
                        num(km.get("massKg"), 0),
                        num(km.get("distM"), 0),
                        num(km.get("mdf"), 0.87));
                Object fa = km.get("fromAnchor");
                if (fa instanceof Boolean b) kk.fromAnchor.set(b);
                ks.add(kk);
            }
            params.killicks.setAll(ks);
        }
    }

    // -------------------- wire listeners --------------------

    private void wireListeners() {
        InvalidationListener triggerSave = obs -> debounce.playFromStart();

        params.buoy.addListener(triggerSave);
        params.windKnots.addListener(triggerSave);
        params.gustTauS.addListener(triggerSave);
        params.depthM.addListener(triggerSave);
        params.fetchKm.addListener(triggerSave);
        params.waterDensity.addListener(triggerSave);
        params.airDensity.addListener(triggerSave);
        params.bottomMu.addListener(triggerSave);
        params.showForces.addListener(triggerSave);

        // Anchor: listen to the outer object swap AND to the inner properties of whichever
        // Anchor instance is currently held. When swapped, re-attach.
        attachAnchor(params.anchor.get(), triggerSave);
        params.anchor.addListener((o, oldA, newA) -> {
            triggerSave.invalidated(o);
            if (newA != null) attachAnchor(newA, triggerSave);
        });

        // Segments list: re-attach to all elements on any list change.
        for (RodeSegment seg : params.segments) attachSegment(seg, triggerSave);
        params.segments.addListener((ListChangeListener<RodeSegment>) c -> {
            while (c.next()) {
                for (RodeSegment added : c.getAddedSubList()) attachSegment(added, triggerSave);
            }
            triggerSave.invalidated(c.getList());
        });

        // Killicks list: same pattern.
        for (KillickSpec k : params.killicks) attachKillick(k, triggerSave);
        params.killicks.addListener((ListChangeListener<KillickSpec>) c -> {
            while (c.next()) {
                for (KillickSpec added : c.getAddedSubList()) attachKillick(added, triggerSave);
            }
            triggerSave.invalidated(c.getList());
        });
    }

    private static void attachAnchor(Anchor a, InvalidationListener l) {
        a.kind.addListener(l);
        a.massKg.addListener(l);
        a.mdf.addListener(l);
        a.holdingFactor.addListener(l);
    }
    private static void attachSegment(RodeSegment s, InvalidationListener l) {
        s.kind.addListener(l);
        s.name.addListener(l);
        s.gauge.addListener(l);
        s.lengthM.addListener(l);
        s.kgPerM.addListener(l);
        s.mdf.addListener(l);
        s.diameterM.addListener(l);
    }
    private static void attachKillick(KillickSpec k, InvalidationListener l) {
        k.massKg.addListener(l);
        k.distM.addListener(l);
        k.fromAnchor.addListener(l);
        k.mdf.addListener(l);
    }

    // -------------------- save --------------------

    public void saveNow() {
        Path target = stateFile();
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            System.err.println("StateStore: cannot create config dir: " + e.getMessage());
            return;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buoy", params.buoy.get() != null ? params.buoy.get().displayName : null);
        out.put("wind",         params.windKnots.get());
        out.put("gustTau",      params.gustTauS.get());
        out.put("depth",        params.depthM.get());
        out.put("fetch",        params.fetchKm.get());
        out.put("waterDensity", params.waterDensity.get());
        out.put("airDensity",   params.airDensity.get());
        out.put("bottomMu",     params.bottomMu.get());
        out.put("showForces",   params.showForces.get());

        Anchor a = params.anchor.get();
        Map<String, Object> am = new LinkedHashMap<>();
        am.put("kind",          a.kind.get().name());
        am.put("massKg",        a.massKg.get());
        am.put("mdf",           a.mdf.get());
        am.put("holdingFactor", a.holdingFactor.get());
        out.put("anchor", am);

        List<Map<String, Object>> segs = new ArrayList<>();
        for (RodeSegment s : params.segments) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("kind",      s.kind.get().name());
            if (s.gauge.get() != null && !s.gauge.get().isEmpty()) {
                sm.put("gauge", s.gauge.get());
            }
            sm.put("lengthM",   s.lengthM.get());
            sm.put("kgPerM",    s.kgPerM.get());
            sm.put("mdf",       s.mdf.get());
            sm.put("diameterM", s.diameterM.get());
            segs.add(sm);
        }
        out.put("segments", segs);

        List<Map<String, Object>> ks = new ArrayList<>();
        for (KillickSpec k : params.killicks) {
            Map<String, Object> km = new LinkedHashMap<>();
            km.put("massKg",     k.massKg.get());
            km.put("distM",      k.distM.get());
            km.put("fromAnchor", k.fromAnchor.get());
            km.put("mdf",        k.mdf.get());
            ks.add(km);
        }
        out.put("killicks", ks);

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        Yaml yaml = new Yaml(opts);

        Path tmp = target.resolveSibling(FILENAME + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp)) {
            w.write("# Auto-saved bouy-anchor state — overwritten on every change.\n");
            yaml.dump(out, w);
        } catch (IOException e) {
            System.err.println("StateStore: write failed: " + e.getMessage());
            return;
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Atomic move can fail on some filesystems; fall back to plain move.
            try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException e2) { System.err.println("StateStore: replace failed: " + e2.getMessage()); }
        }
    }

    // -------------------- helpers --------------------

    private static void setIfNum(Map<String, Object> m, String key, javafx.beans.property.DoubleProperty p) {
        Object v = m.get(key);
        if (v instanceof Number n) p.set(n.doubleValue());
        else if (v != null) {
            try { p.set(Double.parseDouble(v.toString())); } catch (NumberFormatException ignored) {}
        }
    }
    private static double num(Object o, double fallback) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return fallback;
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return fallback; }
    }
    private static Anchor.Kind parseAnchorKind(Object o) {
        if (o == null) return Anchor.Kind.OTHER;
        try { return Anchor.Kind.valueOf(o.toString().trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return Anchor.Kind.OTHER; }
    }
    private static RodeSegment.Kind parseSegmentKind(Object o) {
        if (o == null) return RodeSegment.Kind.OTHER;
        try { return RodeSegment.Kind.valueOf(o.toString().trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return RodeSegment.Kind.OTHER; }
    }
}
