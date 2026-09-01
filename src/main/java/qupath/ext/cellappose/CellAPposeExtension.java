package qupath.ext.cellappose;

import javafx.beans.binding.Bindings;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cellappose.ui.CellAPposeCommand;
import qupath.ext.cellappose.ui.ManageEnvironmentsDialog;
import qupath.ext.cellappose.ui.PythonConsoleWindow;
import qupath.lib.common.GeneralTools;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath extension entry point for CellAPpose -- whole-slide Cellpose segmentation
 * via an Appose/Pixi Python backend.
 *
 * <p>Adds a menu under {@code Extensions > CellAPpose}: a Run command (gated on an
 * open image), an environment-management dialog, the Python console, and a
 * documentation link. This is a standalone testbed extension; the Cellpose Python
 * scripts are vendored (BSD-3) and run in two on-demand pixi environments.
 */
public class CellAPposeExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(CellAPposeExtension.class);

    private static final String EXTENSION_NAME = "CellAPpose";
    private static final String EXTENSION_DESCRIPTION =
            "Whole-slide Cellpose 3 and Cellpose-SAM (Cellpose 4) segmentation for QuPath, "
                    + "driven by an Appose/Pixi Python backend with QuPath-side tiling and tile-seam merging.";
    // Compatibility floor: this extension targets QuPath 0.7.0 and uses 0.7.0 APIs.
    // PathObject.getMetadata() (MetadataMap) is >= 0.5.0; DefaultScriptableWorkflowStep,
    // ContourTracing.createGeometries(SimpleImage, RegionRequest, int, int), and
    // RoiTools.computeTiledROIs(...) are all present and stable in 0.7.0.
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");
    private static final GitHubRepo EXTENSION_REPOSITORY =
            GitHubRepo.create(EXTENSION_NAME, "uw-loci", "qupath-extension-cellAPpose");

    private static final String DOCUMENTATION_URL =
            "https://github.com/uw-loci/qupath-extension-cellAPpose/blob/main/documentation/cellappose.md";

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }

    @Override
    public GitHubRepo getRepository() {
        return EXTENSION_REPOSITORY;
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        String extVersion = GeneralTools.getPackageVersion(CellAPposeExtension.class);
        logger.info("Installing CellAPpose extension v{}", extVersion != null ? extVersion : "dev");

        CellAPposePrefs.installPreferences();
        CellAPposePrefs.installPreferencePane(qupath);

        Menu extensionsMenu = qupath.getMenu("Extensions", true);
        Menu cellMenu = new Menu(EXTENSION_NAME);

        // Run -- gated on an open image (detection needs pixels). A tooltip on the
        // item's graphic explains the disabled state ("Open an image first"), since
        // JavaFX MenuItem has no direct tooltip API.
        MenuItem runItem = new MenuItem("Run Cellpose Detection...");
        runItem.setOnAction(e -> CellAPposeCommand.run(qupath));
        runItem.disableProperty().bind(Bindings.isNull(qupath.imageDataProperty()));
        javafx.scene.control.Label runGraphic = new javafx.scene.control.Label();
        javafx.scene.control.Tooltip runTip =
                new javafx.scene.control.Tooltip("Open an image first to run Cellpose detection.");
        javafx.scene.control.Tooltip.install(runGraphic, runTip);
        runItem.setGraphic(runGraphic);

        // Manage Environments -- always enabled.
        MenuItem manageItem = new MenuItem("Manage Environments...");
        manageItem.setOnAction(e -> ManageEnvironmentsDialog.show(qupath));

        // Python Console -- always enabled.
        MenuItem consoleItem = new MenuItem("Python Console");
        consoleItem.setOnAction(e -> PythonConsoleWindow.getInstance().show());

        // Documentation -- always enabled.
        MenuItem docItem = new MenuItem("Documentation");
        docItem.setOnAction(e -> openDocumentation());

        cellMenu.getItems()
                .addAll(runItem, new SeparatorMenuItem(), manageItem, consoleItem, new SeparatorMenuItem(), docItem);

        extensionsMenu.getItems().add(cellMenu);
        logger.info("CellAPpose menu registered under Extensions > CellAPpose");
    }

    private static void openDocumentation() {
        // Run Desktop.open off the FX thread to avoid the UI-freeze gotcha (QuIET).
        Thread t = new Thread(
                () -> {
                    try {
                        if (java.awt.Desktop.isDesktopSupported()) {
                            java.awt.Desktop.getDesktop().browse(java.net.URI.create(DOCUMENTATION_URL));
                            return;
                        }
                        throw new UnsupportedOperationException("Desktop browse not supported");
                    } catch (Exception ex) {
                        javafx.application.Platform.runLater(() -> qupath.fx.dialogs.Dialogs.showErrorMessage(
                                "CellAPpose Documentation",
                                "Could not open the documentation in a browser.\nOpen it manually:\n"
                                        + DOCUMENTATION_URL));
                    }
                },
                "CellAPpose-OpenDocs");
        t.setDaemon(true);
        t.start();
    }
}
