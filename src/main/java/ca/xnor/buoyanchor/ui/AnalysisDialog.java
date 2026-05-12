package ca.xnor.buoyanchor.ui;

import ca.xnor.buoyanchor.model.SimParams;
import ca.xnor.buoyanchor.sim.Analysis;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Modal-ish window that runs the multi-wind analysis on a background thread and shows the result as
 * text with an Export button to save it.
 */
public final class AnalysisDialog {

  private AnalysisDialog() {
    // Utility class — call show(...) directly.
  }

  private static void save(
      Stage stage,
      TextArea text,
      String initialName,
      String filterDesc,
      String filterGlob,
      String content) {
    FileChooser fc = new FileChooser();
    fc.setTitle("Save analysis report");
    fc.setInitialFileName(initialName);
    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(filterDesc, filterGlob));
    java.io.File file = fc.showSaveDialog(stage);
    if (file != null) {
      try {
        Files.writeString(Path.of(file.getAbsolutePath()), content);
      } catch (IOException ex) {
        text.appendText("\n\n[ERROR writing file: " + ex.getMessage() + "]");
      }
    }
  }

  public static void show(Window owner, SimParams params) {
    Stage stage = new Stage();
    stage.initOwner(owner);
    stage.initModality(Modality.NONE);
    stage.setTitle("Mark Anchoring Analysis");

    ProgressBar progress = new ProgressBar(0);
    progress.setPrefWidth(420);
    Label status = new Label("Running multi-wind analysis (5–50 kt)…");

    HBox progBox = new HBox(8, progress, status);
    progBox.setPadding(new Insets(12));

    TextArea text = new TextArea();
    text.setFont(Font.font("Monospaced", 12));
    text.setEditable(false);
    text.setWrapText(false);

    Button exportTxt = new Button("Export as .txt…");
    Button exportMd = new Button("Export as .md…");
    Button close = new Button("Close");
    exportTxt.setDisable(true);
    exportMd.setDisable(true);
    HBox buttons = new HBox(8, exportTxt, exportMd, close);
    buttons.setPadding(new Insets(8, 12, 12, 12));

    BorderPane root = new BorderPane();
    root.setTop(progBox);
    root.setCenter(text);
    root.setBottom(buttons);

    stage.setScene(new Scene(root, 900, 700));
    stage.show();

    close.setOnAction(e -> stage.close());

    final Analysis.Result[] resultHolder = new Analysis.Result[1];
    Task<String> task =
        new Task<>() {
          @Override
          protected String call() {
            Analysis.Result r = Analysis.run(params, frac -> updateProgress(frac, 1.0));
            resultHolder[0] = r;
            return Analysis.format(params, r);
          }
        };
    progress.progressProperty().bind(task.progressProperty());
    task.setOnSucceeded(
        e -> {
          text.setText(task.getValue());
          progBox.getChildren().clear();
          progBox.getChildren().add(new Label("Analysis complete."));
          exportTxt.setDisable(false);
          exportMd.setDisable(false);
        });
    task.setOnFailed(
        e -> {
          Throwable t = task.getException();
          text.setText("Analysis failed: " + (t == null ? "?" : t.getMessage()));
          progBox.getChildren().clear();
          progBox.getChildren().add(new Label("Analysis failed."));
        });
    Thread thread = new Thread(task, "buoy-anchor-analysis");
    thread.setDaemon(true);
    thread.start();

    exportTxt.setOnAction(
        e -> save(stage, text, "mark-anchoring-report.txt", "Text", "*.txt", text.getText()));
    exportMd.setOnAction(
        e -> {
          if (resultHolder[0] == null) return;
          String md = Analysis.formatMarkdown(params, resultHolder[0]);
          save(stage, text, "mark-anchoring-report.md", "Markdown", "*.md", md);
        });
  }
}
