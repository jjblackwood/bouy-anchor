package ca.xnor.buoyanchor.ui;

import ca.xnor.buoyanchor.model.BuoyPreset;
import ca.xnor.buoyanchor.model.SimParams;
import javafx.beans.property.DoubleProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Right-side input panel. Inputs are bound bidirectionally to the SimParams model so edits
 * propagate live to the running sim. Wrapped in a ScrollPane because the rode-segment editor can
 * grow tall.
 */
public class ControlPanel extends ScrollPane {

  public ControlPanel(SimParams p) {
    VBox box = new VBox(8);
    box.setPadding(new Insets(12));

    ComboBox<BuoyPreset> markChooser =
        new ComboBox<>(FXCollections.observableArrayList(BuoyPreset.LIBRARY));
    markChooser.setValue(p.buoy.get());
    markChooser
        .valueProperty()
        .addListener(
            (o, a, b) -> {
              if (b != null) p.buoy.set(b);
            });
    markChooser.setPrefWidth(240);

    GridPane env = new GridPane();
    env.setHgap(8);
    env.setVgap(6);
    int row = 0;
    row = addRow(env, row, "Wind (kt)", p.windKnots, 0, 60, 0.5);
    row = addRow(env, row, "Gust τ (s)", p.gustTauS, 1, 120, 1.0);
    row = addRow(env, row, "Depth (m)", p.depthM, 1, 30, 0.5);
    row = addRow(env, row, "Fetch (km)", p.fetchKm, 1, 400, 5.0);
    addRow(env, row, "Bottom μ", p.bottomMu, 0, 2, 0.05);

    CheckBox forces = new CheckBox("Show forces");
    forces.selectedProperty().bindBidirectional(p.showForces);

    javafx.scene.control.Button analyse = new javafx.scene.control.Button("Run Analysis Report…");
    analyse.setMaxWidth(Double.MAX_VALUE);
    analyse.setOnAction(e -> AnalysisDialog.show(analyse.getScene().getWindow(), p));

    box.getChildren()
        .addAll(
            new Label("Mark"),
            markChooser,
            new Separator(),
            new Label("Environment"),
            env,
            new Separator(),
            new AnchorPanel(p),
            new Separator(),
            new RodePanel(p),
            new Separator(),
            new KillickPanel(p),
            new Separator(),
            forces,
            analyse);

    setContent(box);
    setFitToWidth(true);
    setPrefWidth(540);
    setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
  }

  private static void commitEditor(Spinner<Double> spinner) {
    try {
      String text = spinner.getEditor().getText();
      if (text == null || text.isBlank()) return;
      double v = Double.parseDouble(text.trim());
      spinner.getValueFactory().setValue(v);
    } catch (NumberFormatException ignored) {
      spinner.getEditor().setText(spinner.getValue().toString());
    }
  }

  private static int addRow(
      GridPane g, int row, String label, DoubleProperty prop, double min, double max, double step) {
    Spinner<Double> spinner = new Spinner<>();
    spinner.setEditable(true);
    spinner.setValueFactory(
        new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, prop.get(), step));
    spinner
        .getValueFactory()
        .valueProperty()
        .addListener(
            (obs, oldV, newV) -> {
              if (newV != null) prop.set(newV);
            });
    spinner.getEditor().setOnAction(e -> commitEditor(spinner));
    spinner
        .focusedProperty()
        .addListener(
            (o, was, isNow) -> {
              if (!isNow) commitEditor(spinner);
            });
    spinner.setPrefWidth(110);
    g.add(new Label(label), 0, row);
    g.add(spinner, 1, row);
    return row + 1;
  }
}
