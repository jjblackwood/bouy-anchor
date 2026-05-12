package ca.xnor.buoyanchor;

import ca.xnor.buoyanchor.model.SimParams;
import ca.xnor.buoyanchor.model.StateStore;
import ca.xnor.buoyanchor.ui.ControlPanel;
import ca.xnor.buoyanchor.ui.ReadoutPanel;
import ca.xnor.buoyanchor.ui.SimView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;

public class App extends Application {

  @Override
  public void start(Stage stage) {
    SimParams params = new SimParams();
    StateStore store = StateStore.attach(params);
    stage.setOnCloseRequest(ev -> store.saveNow());

    SimView view = new SimView(params);
    ReadoutPanel readout = new ReadoutPanel(params, view.sim);
    view.setReadout(readout);
    ControlPanel controls = new ControlPanel(params);

    // Left column: simulation canvas on top, readout below — so the readout only takes the
    // width of the viewport, not the width of the whole window. The control panel sits to
    // the right of that column at full window height.
    SplitPane leftColumn = new SplitPane(view, readout);
    leftColumn.setOrientation(javafx.geometry.Orientation.VERTICAL);
    leftColumn.setDividerPositions(0.78);
    SplitPane.setResizableWithParent(readout, false);

    SplitPane root = new SplitPane(leftColumn, controls);
    root.setDividerPositions(0.65);
    SplitPane.setResizableWithParent(controls, false);

    stage.setTitle("Buoy Anchor Simulator");
    stage.setScene(new Scene(root, 1500, 820));
    stage.show();

    view.start();
  }

  public static void main(String[] args) {
    launch(args);
  }
}
