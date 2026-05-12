package ca.xnor.buoyanchor.ui;

import ca.xnor.buoyanchor.model.RodePreset;
import ca.xnor.buoyanchor.model.RodeSegment;
import ca.xnor.buoyanchor.model.SimParams;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.List;

/** Edit the ordered list of rode segments (anchor → buoy) as a table.
 *  #  kind  gauge  length(m)  kg/m  MDF  diam(mm)  ×
 *  For chain/rope rows the gauge dropdown drives kg/m, MDF, and diameter (those fields show the
 *  looked-up values, disabled). For OTHER rows the gauge dropdown is disabled and the user
 *  edits kg/m, MDF, and diameter directly. */
public class RodePanel extends VBox {

    private final SimParams params;
    private final VBox rows = new VBox(2);

    private static final double W_IDX   = 22;
    private static final double W_KIND  = 100;
    private static final double W_GAUGE = 72;
    private static final double W_LEN   = 62;
    private static final double W_KGM   = 62;
    private static final double W_MDF   = 50;
    private static final double W_DIAM  = 62;
    private static final double W_DEL   = 28;

    public RodePanel(SimParams params) {
        this.params = params;
        setSpacing(6);
        setPadding(new Insets(0));

        Label header = new Label("Rode (anchor → buoy)");
        Button add = new Button("+");
        add.setOnAction(e -> {
            params.segments.add(RodeSegment.ofGauge(RodeSegment.Kind.CHAIN, "1/4", 5.0));
            rebuild();
        });
        HBox top = new HBox(8, header, add);
        top.setAlignment(Pos.CENTER_LEFT);

        ComboBox<RodePreset> presetChooser = new ComboBox<>(
                FXCollections.observableArrayList(RodePreset.LIBRARY));
        presetChooser.setPromptText("Apply preset…");
        presetChooser.setPrefWidth(220);
        presetChooser.valueProperty().addListener((o, oldV, newV) -> {
            if (newV != null) {
                params.segments.setAll(newV.instantiate());
                rebuild();
                presetChooser.getSelectionModel().clearSelection();
            }
        });

        HBox columnHeader = new HBox(4,
                headerCell("#",        W_IDX),
                headerCell("kind",     W_KIND),
                headerCell("gauge",    W_GAUGE),
                headerCell("length m", W_LEN),
                headerCell("kg/m",     W_KGM),
                headerCell("MDF",      W_MDF),
                headerCell("diam mm",  W_DIAM),
                headerCell("",         W_DEL));
        columnHeader.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(top, presetChooser, columnHeader, rows);
        rebuild();
    }

    private void rebuild() {
        rows.getChildren().clear();
        for (int i = 0; i < params.segments.size(); i++) {
            final int idx = i;
            RodeSegment seg = params.segments.get(i);

            Label num = new Label(String.format("%d.", i + 1));
            num.setMinWidth(W_IDX); num.setPrefWidth(W_IDX);

            ComboBox<RodeSegment.Kind> kindCombo = new ComboBox<>(
                    FXCollections.observableArrayList(RodeSegment.Kind.values()));
            kindCombo.setValue(seg.kind.get());
            kindCombo.setMinWidth(W_KIND); kindCombo.setPrefWidth(W_KIND);

            ComboBox<String> gaugeCombo = new ComboBox<>();
            gaugeCombo.setMinWidth(W_GAUGE); gaugeCombo.setPrefWidth(W_GAUGE);

            TextField len  = numField(seg.lengthM.get(),   W_LEN,  v -> seg.lengthM.set(v));
            TextField kg   = numField(seg.kgPerM.get(),    W_KGM,  v -> seg.kgPerM.set(v));
            TextField mdfF = numField(seg.mdf.get(),       W_MDF,  v -> seg.mdf.set(v));
            // Diameter shown / edited in millimetres for user friendliness.
            TextField diam = numField(seg.diameterM.get() * 1000.0, W_DIAM,
                    v -> seg.diameterM.set(v / 1000.0));

            // Sync the gauge dropdown + kg/m + MDF + diameter fields to the current kind.
            Runnable refreshForKind = () -> {
                RodeSegment.Kind k = seg.kind.get();
                List<RodeSegment.Gauge> avail = RodeSegment.gaugesFor(k);
                gaugeCombo.getItems().clear();
                for (RodeSegment.Gauge g : avail) gaugeCombo.getItems().add(g.label());
                boolean isOther = k == RodeSegment.Kind.OTHER;
                gaugeCombo.setDisable(isOther);
                kg.setDisable(!isOther);
                mdfF.setDisable(!isOther);
                diam.setDisable(!isOther);
                // Make sure gauge selection reflects current value, or snap to first gauge for
                // chain/rope if none currently set.
                if (!isOther) {
                    if (seg.gauge.get() == null || RodeSegment.findGauge(k, seg.gauge.get()) == null) {
                        if (!avail.isEmpty()) {
                            seg.applyGauge(avail.get(0).label());
                        }
                    }
                    gaugeCombo.setValue(seg.gauge.get());
                    kg.setText(formatPlain(seg.kgPerM.get()));
                    mdfF.setText(formatPlain(seg.mdf.get()));
                    diam.setText(formatPlain(seg.diameterM.get() * 1000.0));
                } else {
                    gaugeCombo.setValue(null);
                    seg.gauge.set("");
                }
            };
            refreshForKind.run();

            kindCombo.valueProperty().addListener((o, oldV, newV) -> {
                if (newV == null) return;
                seg.kind.set(newV);
                refreshForKind.run();
            });
            gaugeCombo.valueProperty().addListener((o, oldV, newV) -> {
                if (newV == null) return;
                seg.applyGauge(newV);
                kg.setText(formatPlain(seg.kgPerM.get()));
                mdfF.setText(formatPlain(seg.mdf.get()));
                diam.setText(formatPlain(seg.diameterM.get() * 1000.0));
            });

            Button del = new Button("×");
            del.setMinWidth(W_DEL); del.setPrefWidth(W_DEL);
            del.setOnAction(e -> {
                if (params.segments.size() > 1) {
                    params.segments.remove(idx);
                    rebuild();
                }
            });

            HBox row = new HBox(4, num, kindCombo, gaugeCombo, len, kg, mdfF, diam, del);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().add(row);
        }
    }

    static Label headerCell(String text, double width) {
        Label l = new Label(text);
        l.setMinWidth(width); l.setPrefWidth(width);
        l.setFont(Font.font(l.getFont().getFamily(), 10.5));
        l.setStyle("-fx-text-fill: #555;");
        return l;
    }

    /** Plain numeric text field. Commits on Enter or focus loss; reverts on parse failure. */
    static TextField numField(double init, double prefWidth, java.util.function.DoubleConsumer onChange) {
        TextField tf = new TextField(formatPlain(init));
        tf.setMinWidth(prefWidth); tf.setPrefWidth(prefWidth);
        double[] last = { init };
        Runnable commit = () -> {
            String txt = tf.getText();
            if (txt == null || txt.isBlank()) { tf.setText(formatPlain(last[0])); return; }
            try {
                double v = Double.parseDouble(txt.trim());
                last[0] = v;
                onChange.accept(v);
            } catch (NumberFormatException ignored) {
                tf.setText(formatPlain(last[0]));
            }
        };
        tf.setOnAction(e -> commit.run());
        tf.focusedProperty().addListener((o, was, isNow) -> { if (!isNow) commit.run(); });
        return tf;
    }

    static String formatPlain(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return Long.toString((long) v);
        String s = String.format("%.4f", v);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }
}
