package ca.xnor.buoyanchor.ui;

import ca.xnor.buoyanchor.model.KillickSpec;
import ca.xnor.buoyanchor.model.SimParams;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Killicks as a table: # kg dist (m) from MDF ✕. */
public class KillickPanel extends VBox {

  private final SimParams params;
  private final VBox rows = new VBox(2);

  private static final double W_IDX = 22;
  private static final double W_KG = 75;
  private static final double W_DIST = 60;
  private static final double W_FROM = 70;
  private static final double W_MDF = 65;
  private static final double W_DEL = 28;

  public KillickPanel(SimParams params) {
    this.params = params;
    setSpacing(6);
    setPadding(new Insets(0));

    Label header = new Label("Killicks");
    Button add = new Button("+");
    add.setOnAction(
        e -> {
          params.killicks.add(new KillickSpec(5.0, 3.0, 0.87));
          rebuild();
        });
    HBox top = new HBox(8, header, add);
    top.setAlignment(Pos.CENTER_LEFT);

    HBox columnHeader =
        new HBox(
            4,
            RodePanel.headerCell("#", W_IDX),
            RodePanel.headerCell("mass kg", W_KG),
            RodePanel.headerCell("dist m", W_DIST),
            RodePanel.headerCell("anchor", W_FROM),
            RodePanel.headerCell("MDF", W_MDF),
            RodePanel.headerCell("", W_DEL));
    columnHeader.setAlignment(Pos.CENTER_LEFT);

    getChildren().addAll(top, columnHeader, rows);
    rebuild();
  }

  private void rebuild() {
    rows.getChildren().clear();
    for (int i = 0; i < params.killicks.size(); i++) {
      final int idx = i;
      KillickSpec k = params.killicks.get(i);

      Label num = new Label(String.format("%d.", i + 1));
      num.setMinWidth(W_IDX);
      num.setPrefWidth(W_IDX);

      TextField mass = RodePanel.numField(k.massKg.get(), W_KG, v -> k.massKg.set(v));
      TextField dist = RodePanel.numField(k.distM.get(), W_DIST, v -> k.distM.set(v));
      TextField mdf = RodePanel.numField(k.mdf.get(), W_MDF, v -> k.mdf.set(v));

      // Header is "anchor": checked = distance measured from the anchor end; unchecked
      // (default) = from the buoy end.
      CheckBox from = new CheckBox();
      from.setSelected(k.fromAnchor.get());
      from.setMinWidth(W_FROM);
      from.setPrefWidth(W_FROM);
      from.selectedProperty().addListener((o, oldV, newV) -> k.fromAnchor.set(newV));

      Button del = new Button("×");
      del.setMinWidth(W_DEL);
      del.setPrefWidth(W_DEL);
      del.setOnAction(
          e -> {
            params.killicks.remove(idx);
            rebuild();
          });

      HBox row = new HBox(4, num, mass, dist, from, mdf, del);
      row.setAlignment(Pos.CENTER_LEFT);
      rows.getChildren().add(row);
    }
  }
}
