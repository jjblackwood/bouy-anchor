package ca.xnor.buoyanchor.ui;

import ca.xnor.buoyanchor.model.Anchor;
import ca.xnor.buoyanchor.model.AnchorPreset;
import ca.xnor.buoyanchor.model.SimParams;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Anchor editor as a single-row table: kind mass (kg) MDF HF. Includes a preset picker. */
public class AnchorPanel extends VBox {

  private final SimParams params;
  private final ComboBox<Anchor.Kind> kindCombo;
  private final TextField massField;
  private final TextField mdfField;
  private final TextField holdField;

  private static final double W_KIND = 115;
  private static final double W_MASS = 80;
  private static final double W_MDF = 65;
  private static final double W_HF = 65;

  public AnchorPanel(SimParams params) {
    this.params = params;
    setSpacing(6);
    setPadding(new Insets(0));

    ComboBox<AnchorPreset> presetChooser =
        new ComboBox<>(FXCollections.observableArrayList(AnchorPreset.LIBRARY));
    presetChooser.setPromptText("Apply preset…");
    presetChooser.setPrefWidth(220);
    presetChooser
        .valueProperty()
        .addListener(
            (o, oldV, newV) -> {
              if (newV != null) {
                params.anchor.set(newV.instantiate());
                refreshEditors();
                presetChooser.getSelectionModel().clearSelection();
              }
            });

    Anchor a = params.anchor.get();
    kindCombo = new ComboBox<>(FXCollections.observableArrayList(Anchor.Kind.values()));
    kindCombo.setValue(a.kind.get());
    kindCombo.setMinWidth(W_KIND);
    kindCombo.setPrefWidth(W_KIND);
    kindCombo
        .valueProperty()
        .addListener(
            (o, oldV, newV) -> {
              if (newV != null) params.anchor.get().kind.set(newV);
            });

    massField = RodePanel.numField(a.massKg.get(), W_MASS, v -> params.anchor.get().massKg.set(v));
    mdfField = RodePanel.numField(a.mdf.get(), W_MDF, v -> params.anchor.get().mdf.set(v));
    holdField =
        RodePanel.numField(
            a.holdingFactor.get(), W_HF, v -> params.anchor.get().holdingFactor.set(v));

    HBox columnHeader =
        new HBox(
            4,
            RodePanel.headerCell("kind", W_KIND),
            RodePanel.headerCell("mass kg", W_MASS),
            RodePanel.headerCell("MDF", W_MDF),
            RodePanel.headerCell("hold fac", W_HF));
    columnHeader.setAlignment(Pos.CENTER_LEFT);

    HBox row = new HBox(4, kindCombo, massField, mdfField, holdField);
    row.setAlignment(Pos.CENTER_LEFT);

    getChildren().addAll(new Label("Anchor"), presetChooser, columnHeader, row);
  }

  private void refreshEditors() {
    Anchor a = params.anchor.get();
    kindCombo.setValue(a.kind.get());
    massField.setText(formatPlain(a.massKg.get()));
    mdfField.setText(formatPlain(a.mdf.get()));
    holdField.setText(formatPlain(a.holdingFactor.get()));
  }

  private static String formatPlain(double v) {
    if (v == Math.rint(v) && Math.abs(v) < 1e9) return Long.toString((long) v);
    String s = String.format("%.4f", v);
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == '0') end--;
    if (end > 0 && s.charAt(end - 1) == '.') end--;
    return s.substring(0, end);
  }
}
