package qupath.ext.cellappose.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cellappose.core.CellposeModelFamily;
import qupath.ext.cellappose.service.ApposeCellposeService;
import qupath.lib.gui.QuPathGUI;

/**
 * Non-modal tool window managing both Cellpose family environments.
 *
 * <p>Renders one row per {@link CellposeModelFamily} (cellappose-cp3 /
 * cellappose-cp4), each with a status tag and Build/Rebuild/Delete buttons, plus a
 * shared indeterminate progress area at the bottom. Builds run on a background
 * daemon thread; status updates marshal back to the FX thread via
 * {@code Platform.runLater}. The detailed pixi/pip output streams to the Python
 * Console. Mirrors PPM's {@code SetupEnvironmentDialog} multi-state pattern,
 * generalized to two family-keyed envs.
 */
public final class ManageEnvironmentsDialog {

    private static final Logger logger = LoggerFactory.getLogger(ManageEnvironmentsDialog.class);

    private static final String COLOR_READY = "#2e7d32";
    private static final String COLOR_BUSY = "#e08a00";
    private static final String COLOR_FAIL = "#c62828";

    private static ManageEnvironmentsDialog instance;

    private final Stage stage;
    private final FamilyRow cp3Row;
    private final FamilyRow cp4Row;
    private final Label progressLabel;
    private final ProgressBar progressBar;

    private volatile boolean building = false;

    private ManageEnvironmentsDialog(QuPathGUI qupath) {
        stage = new Stage();
        stage.setTitle("CellAPpose Environments");
        if (qupath != null && qupath.getStage() != null) {
            stage.initOwner(qupath.getStage());
        }

        Label header = new Label("CellAPpose Environments");
        header.setFont(Font.font(null, FontWeight.BOLD, 14));

        Label intro = new Label("Each model family uses its own Python environment. They are built on first use; "
                + "you can also build them ahead of time here. Each download is several GB "
                + "(PyTorch + Cellpose). Not recommended on metered connections.");
        intro.setWrapText(true);

        cp3Row = new FamilyRow(CellposeModelFamily.CP3);
        cp4Row = new FamilyRow(CellposeModelFamily.CP4);

        progressLabel = new Label("");
        progressLabel.setWrapText(true);
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        Button closeButton = new Button("Close");
        closeButton.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttonBox = new HBox(8, spacer, closeButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(
                12,
                header,
                intro,
                cp3Row.container,
                cp4Row.container,
                new Label("Progress:"),
                progressLabel,
                progressBar,
                buttonBox);
        root.setPadding(new Insets(20));
        root.setPrefWidth(560);

        stage.setScene(new Scene(root));
        refreshAll();
    }

    /** Shows the dialog (creating it if needed), bringing it to front. */
    public static synchronized void show(QuPathGUI qupath) {
        if (instance == null || instance.stage.getScene() == null) {
            instance = new ManageEnvironmentsDialog(qupath);
        }
        instance.refreshAll();
        instance.stage.show();
        instance.stage.toFront();
    }

    private void refreshAll() {
        cp3Row.refresh();
        cp4Row.refresh();
    }

    /** A single family's row: status label + Build/Rebuild/Delete buttons. */
    private final class FamilyRow {
        private final CellposeModelFamily family;
        private final VBox container;
        private final Label statusLabel;
        private final Button buildButton;
        private final Button deleteButton;

        FamilyRow(CellposeModelFamily family) {
            this.family = family;

            Label title = new Label(family.displayLabel() + "  (" + family.envName() + ")");
            title.setFont(Font.font(null, FontWeight.BOLD, 12));

            statusLabel = new Label();
            Label location = new Label("Location: " + ApposeCellposeService.getEnvironmentPath(family));
            location.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

            buildButton = new Button("Build");
            buildButton.setTooltip(
                    new Tooltip("Download and build this model family's Python environment (several GB)."));
            buildButton.setOnAction(e -> startBuild(this));

            deleteButton = new Button("Delete environment");
            deleteButton.setTooltip(new Tooltip("Remove this model family's Python environment from disk. "
                    + "You will need to rebuild it before next use."));
            deleteButton.setOnAction(e -> deleteEnv(this));

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox buttons = new HBox(8, spacer, buildButton, deleteButton);

            container = new VBox(4, title, statusLabel, location, buttons);
            container.setPadding(new Insets(8));
            container.setStyle("-fx-border-color: #b0b0b0; -fx-border-radius: 4; -fx-padding: 8;");
        }

        void refresh() {
            boolean built = ApposeCellposeService.isEnvironmentBuilt(family);
            ApposeCellposeService svc = ApposeCellposeService.getInstance();
            String err = svc.getInitError(family);
            if (building) {
                statusLabel.setText("[~] Busy...");
                statusLabel.setStyle("-fx-text-fill: " + COLOR_BUSY + ";");
                buildButton.setDisable(true);
                deleteButton.setDisable(true);
            } else if (err != null && !built) {
                statusLabel.setText("[FAIL] Build failed -- see Python Console");
                statusLabel.setStyle("-fx-text-fill: " + COLOR_FAIL + ";");
                buildButton.setText("Retry");
                buildButton.setDisable(false);
                deleteButton.setDisable(false);
            } else if (built) {
                statusLabel.setText("[OK] Ready");
                statusLabel.setStyle("-fx-text-fill: " + COLOR_READY + ";");
                buildButton.setText("Rebuild");
                buildButton.setDisable(false);
                deleteButton.setDisable(false);
            } else {
                statusLabel.setText("[--] Not built");
                statusLabel.setStyle("-fx-text-fill: " + COLOR_BUSY + ";");
                buildButton.setText("Build");
                buildButton.setDisable(false);
                deleteButton.setDisable(true);
            }
        }
    }

    private void startBuild(FamilyRow row) {
        if (building) {
            return;
        }
        building = true;
        progressBar.setVisible(true);
        progressBar.setProgress(-1); // indeterminate
        progressLabel.setText("Building " + row.family.envName() + "...");
        refreshAll();

        Thread t = new Thread(
                () -> {
                    boolean ok = false;
                    try {
                        ApposeCellposeService.getInstance()
                                .buildEnvironment(
                                        row.family, status -> Platform.runLater(() -> progressLabel.setText(status)));
                        ok = true;
                    } catch (Exception e) {
                        logger.error("Environment build failed for {}", row.family, e);
                        final String msg = e.getMessage();
                        Platform.runLater(
                                () -> progressLabel.setText("Build failed: " + msg + " (see Python Console)"));
                    } finally {
                        final boolean success = ok;
                        Platform.runLater(() -> {
                            building = false;
                            progressBar.setVisible(false);
                            progressBar.setProgress(0);
                            if (success) {
                                progressLabel.setText("Build complete: " + row.family.envName());
                            }
                            refreshAll();
                        });
                    }
                },
                "CellAPpose-EnvBuild-" + row.family.name());
        t.setDaemon(true);
        t.start();
    }

    private void deleteEnv(FamilyRow row) {
        // Destructive: APPLICATION_MODAL confirm (the one deliberate one in this extension).
        boolean confirm = qupath.fx.dialogs.Dialogs.showConfirmDialog(
                "Delete environment",
                "Delete the " + row.family.envName() + " environment? You will need to rebuild it "
                        + "(several GB) before next use of this family.");
        if (!confirm) {
            return;
        }
        try {
            ApposeCellposeService svc = ApposeCellposeService.getInstance();
            svc.deleteEnvironment(row.family);
            progressLabel.setText("Deleted " + row.family.envName());
        } catch (Exception e) {
            logger.error("Failed to delete env {}", row.family, e);
            qupath.fx.dialogs.Dialogs.showErrorMessage(
                    "CellAPpose Environments", "Failed to delete environment: " + e.getMessage());
        }
        refreshAll();
    }
}
