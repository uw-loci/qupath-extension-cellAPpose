package qupath.ext.cellappose.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cellappose.CellAPposePrefs;
import qupath.ext.cellappose.core.Cellpose2D;
import qupath.ext.cellappose.core.CellposeBuilder;
import qupath.ext.cellappose.core.CellposeModelFamily;
import qupath.ext.cellappose.core.CellposeParameters;
import qupath.ext.cellappose.service.ApposeCellposeService;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * The "Run Cellpose Detection..." dialog.
 *
 * <p>A scrollable {@link VBox} of {@link TitledPane} sections (progressive
 * disclosure). A live env banner re-queries
 * {@link ApposeCellposeService#isEnvironmentBuilt} on family switch; parameters
 * stay editable when the env is not built. Run builds the env first if needed,
 * then segments. CP3/CP4 panels swap via {@code setVisible}+{@code setManaged}.
 * Tooltips are implemented verbatim from {@code wip_ui_design.md} section 5.
 *
 * <p>Uses {@code qupath.fx.dialogs.Dialogs} only.
 */
public final class CellAPposeCommand {

    private static final Logger logger = LoggerFactory.getLogger(CellAPposeCommand.class);

    private static final String COLOR_READY = "#1565c0"; // blue = ready
    private static final String COLOR_WILL_BUILD = "#e08a00"; // amber = not built

    private final QuPathGUI qupath;
    // Not final: a non-modal dialog follows the active image, so this is re-targeted when the user
    // switches images without closing the dialog (otherwise Run would land objects on the old image).
    private ImageData<BufferedImage> imageData;
    private final Stage stage;
    private javafx.beans.value.ChangeListener<ImageData<BufferedImage>> activeImageListener;

    // Controls
    private final ToggleGroup familyGroup = new ToggleGroup();
    private RadioButton cp3Radio;
    private RadioButton cp4Radio;
    private Label envBanner;
    private Button buildNowButton;

    private GridPane cp3Panel;
    private GridPane cp4Panel;
    private ComboBox<String> builtinModelCombo;
    private ComboBox<String> cellChannelCombo;
    private ComboBox<String> nucleiChannelCombo;
    // CP4 (Cellpose-SAM) channel picker: up to 3 source channels mapped to cpsam inputs.
    private ComboBox<String> cp4Channel1Combo;
    private ComboBox<String> cp4Channel2Combo;
    private ComboBox<String> cp4Channel3Combo;

    private Spinner<Double> diameterSpinner;
    private Spinner<Double> cellprobSpinner;
    private Spinner<Double> flowSpinner;
    private Spinner<Integer> minSizeSpinner;
    private CheckBox normalizeCheck;

    private Spinner<Integer> tileSizeSpinner;
    private Spinner<Integer> tileOverlapSpinner;
    private Spinner<Double> pixelSizeSpinner;

    private ToggleGroup outputGroup = new ToggleGroup();
    private RadioButton detectionsRadio;
    private RadioButton cellsRadio;
    private RadioButton annotationsRadio;
    private Spinner<Double> cellExpansionSpinner;
    private CheckBox constrainCheck;
    private Label outputNote;

    private CheckBox gpuCheck;

    private ToggleGroup runOnGroup = new ToggleGroup();
    private RadioButton selectedRadio;
    private RadioButton wholeImageRadio;

    // Set when whole-image is chosen; added to the hierarchy just before detection and
    // removed on failure so a failed run never leaves an orphan annotation (clinical M3).
    private PathObject tempWholeImageParent;
    private Label countLabel;

    private Button runButton;

    private CellAPposeCommand(QuPathGUI qupath, ImageData<BufferedImage> imageData) {
        this.qupath = qupath;
        this.imageData = imageData;
        this.stage = new Stage();
        // Non-modal: Run no longer closes the dialog, so the user can draw/adjust annotations and
        // re-run repeatedly without reopening it. Each Run re-resolves the current selection.
        stage.initModality(Modality.NONE);
        if (qupath.getStage() != null) {
            stage.initOwner(qupath.getStage());
        }
        stage.setScene(new Scene(buildContent()));
        stage.setTitle(dialogTitle());

        // Follow the active image: if the user switches images while this non-modal dialog is open,
        // re-target the new image (refresh channels + count + title) so Run operates on what is shown.
        activeImageListener = (obs, oldData, newData) -> {
            if (newData != null && newData != this.imageData) {
                this.imageData = newData;
                populateChannelCombos();
                updateCount();
                stage.setTitle(dialogTitle());
            }
        };
        qupath.imageDataProperty().addListener(activeImageListener);
        stage.setOnHidden(e -> qupath.imageDataProperty().removeListener(activeImageListener));
    }

    /** Window title that names the image the dialog currently targets. */
    private String dialogTitle() {
        String name = null;
        try {
            name = ServerTools.getDisplayableImageName(imageData.getServer());
        } catch (RuntimeException ignored) {
            // fall through to the generic title
        }
        return name == null || name.isBlank() ? "Run Cellpose Detection" : "Run Cellpose Detection - " + name;
    }

    /** Opens the Run dialog for the current image. */
    public static void run(QuPathGUI qupath) {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage("CellAPpose", "Open an image to run Cellpose.");
            return;
        }
        CellAPposePrefs.installPreferences();
        new CellAPposeCommand(qupath, imageData).stage.show();
    }

    private Region buildContent() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(16));
        content.setMinWidth(520);

        content.getChildren().add(buildFamilySelector());

        envBanner = new Label();
        envBanner.setWrapText(true);
        envBanner.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(envBanner, Priority.ALWAYS);
        buildNowButton = new Button("Build now...");
        buildNowButton.setTooltip(
                new Tooltip("Open the Environments window and build this model family's Python environment now."));
        buildNowButton.setOnAction(e -> ManageEnvironmentsDialog.show(qupath));
        HBox bannerBox = new HBox(8, envBanner, buildNowButton);
        bannerBox.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(bannerBox);

        content.getChildren().add(buildModelSection());
        content.getChildren().add(buildDetectionSection());
        content.getChildren().add(buildTilingSection());
        content.getChildren().add(buildOutputSection());
        content.getChildren().add(buildComputeSection());
        content.getChildren().add(buildRunOnSection());

        // Buttons
        runButton = new Button("Run");
        runButton.setDefaultButton(true);
        runButton.setTooltip(
                new Tooltip("Build the environment if needed, then segment and add the objects to the image."));
        runButton.setOnAction(e -> onRun());

        Button closeButton = new Button("Close");
        closeButton.setCancelButton(true);
        closeButton.setTooltip(new Tooltip("Close this dialog. Run does not close it, so you can run repeatedly."));
        closeButton.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttonBox = new HBox(8, spacer, runButton, closeButton);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        content.getChildren().add(buttonBox);

        // Initial state
        loadDefaults();
        updateFamilyUI();
        updateCount();

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(720);
        return scroll;
    }

    private Region buildFamilySelector() {
        cp3Radio = new RadioButton("CP3  Cellpose 3");
        cp3Radio.setToggleGroup(familyGroup);
        cp3Radio.setTooltip(new Tooltip(
                "Use Cellpose 3. Lets you set separate cell and nuclei channels and pick a built-in or custom model."));
        cp4Radio = new RadioButton("CP4  Cellpose-SAM");
        cp4Radio.setToggleGroup(familyGroup);
        cp4Radio.setTooltip(
                new Tooltip("Use Cellpose-SAM (Cellpose 4). Works across channels without a cell/nuclei split."));

        familyGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            updateFamilyUI();
            updateEnvBanner();
        });

        Label label = new Label("Model family:");
        label.setFont(Font.font(null, FontWeight.BOLD, 12));
        HBox box = new HBox(12, label, cp3Radio, cp4Radio);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private TitledPane buildModelSection() {
        // CP3 sub-panel
        cp3Panel = new GridPane();
        cp3Panel.setHgap(8);
        cp3Panel.setVgap(6);
        builtinModelCombo = new ComboBox<>();
        builtinModelCombo.getItems().addAll("cyto3", "cyto2", "nuclei");
        builtinModelCombo.setTooltip(new Tooltip(
                "Choose a built-in Cellpose model: cyto3 and cyto2 segment whole cells; nuclei segments nuclei only."));
        cellChannelCombo = new ComboBox<>();
        cellChannelCombo.setTooltip(new Tooltip("Image channel that shows the cell body or cytoplasm."));
        nucleiChannelCombo = new ComboBox<>();
        nucleiChannelCombo.setTooltip(
                new Tooltip("Image channel that shows nuclei. Set to None if you are segmenting cells only."));

        cp3Panel.addRow(0, new Label("Built-in model:"), builtinModelCombo);
        cp3Panel.addRow(1, new Label("Cell channel:"), cellChannelCombo);
        cp3Panel.addRow(2, new Label("Nuclei channel:"), nucleiChannelCombo);

        // CP4 sub-panel: Cellpose-SAM channel picker (up to 3 source channels).
        cp4Panel = new GridPane();
        cp4Panel.setHgap(8);
        cp4Panel.setVgap(6);
        Label cp4Info = new Label("Cellpose-SAM takes up to 3 channels. Pick which image channels to send "
                + "(set the extras to None for a single-channel run).");
        cp4Info.setWrapText(true);
        cp4Panel.add(cp4Info, 0, 0, 2, 1);
        cp4Channel1Combo = new ComboBox<>();
        cp4Channel1Combo.setTooltip(new Tooltip("First channel sent to Cellpose-SAM."));
        cp4Channel2Combo = new ComboBox<>();
        cp4Channel2Combo.setTooltip(new Tooltip("Second channel sent to Cellpose-SAM, or None."));
        cp4Channel3Combo = new ComboBox<>();
        cp4Channel3Combo.setTooltip(new Tooltip("Third channel sent to Cellpose-SAM, or None."));
        cp4Panel.addRow(1, new Label("Channel 1:"), cp4Channel1Combo);
        cp4Panel.addRow(2, new Label("Channel 2:"), cp4Channel2Combo);
        cp4Panel.addRow(3, new Label("Channel 3:"), cp4Channel3Combo);

        // Populate ALL channel combos (CP3 + CP4) now that every combo exists.
        populateChannelCombos();

        VBox body = new VBox(8, cp3Panel, cp4Panel);
        TitledPane pane = new TitledPane("Model and channels", body);
        pane.setExpanded(true);
        pane.setAnimated(false);
        return pane;
    }

    private TitledPane buildDetectionSection() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        diameterSpinner = doubleSpinner(0, 1000, CellAPposePrefs.DEFAULT_DIAMETER, 1);
        diameterSpinner.setTooltip(
                new Tooltip("Expected object diameter in pixels. Set to 0 to let Cellpose estimate it automatically."));
        cellprobSpinner = doubleSpinner(-6.0, 6.0, CellAPposePrefs.DEFAULT_CELLPROB, 0.1);
        cellprobSpinner.setTooltip(
                new Tooltip("Detection sensitivity from -6.0 to 6.0. Lower values find more (and smaller) objects; "
                        + "higher values are stricter."));
        flowSpinner = doubleSpinner(0.0, 1.0, CellAPposePrefs.DEFAULT_FLOW, 0.05);
        flowSpinner.setTooltip(new Tooltip(
                "Maximum allowed flow error from 0.0 to 1.0. Lower values reject more poorly-shaped objects."));
        minSizeSpinner = intSpinner(0, 100000, CellAPposePrefs.DEFAULT_MIN_SIZE, 1);
        minSizeSpinner.setTooltip(new Tooltip("Discard detected objects smaller than this many pixels."));
        normalizeCheck = new CheckBox("Normalize image intensities (per-tile 1-99 percentile)");
        normalizeCheck.setTooltip(new Tooltip(
                "Rescale each tile's intensities (1-99 percentile) before segmentation. Recommended for most images."));

        grid.addRow(0, new Label("Diameter (px):"), diameterSpinner);
        grid.addRow(1, new Label("Cell probability:"), cellprobSpinner);
        grid.addRow(2, new Label("Flow threshold:"), flowSpinner);
        grid.addRow(3, new Label("Minimum object size:"), minSizeSpinner);
        grid.add(normalizeCheck, 0, 4, 2, 1);

        TitledPane pane = new TitledPane("Detection parameters", grid);
        pane.setExpanded(true);
        pane.setAnimated(false);
        return pane;
    }

    private TitledPane buildTilingSection() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        tileSizeSpinner = intSpinner(64, 8192, CellAPposePrefs.DEFAULT_TILE_SIZE, 64);
        tileSizeSpinner.setTooltip(
                new Tooltip("Size in pixels of each square tile sent to Cellpose. Larger tiles use more memory."));
        tileOverlapSpinner = intSpinner(0, 4096, CellAPposePrefs.DEFAULT_TILE_OVERLAP, 2);
        tileOverlapSpinner.setTooltip(
                new Tooltip("Pixels of overlap between neighboring tiles so objects on tile edges are not split. "
                        + "About 2x the diameter works well."));
        pixelSizeSpinner = doubleSpinner(0.0, 100.0, CellAPposePrefs.DEFAULT_PIXEL_SIZE, 0.05);
        pixelSizeSpinner.setTooltip(
                new Tooltip("Physical resolution in um per pixel used for segmentation. Coarser values run faster; "
                        + "finer values resolve smaller objects."));

        // Derived-overlap live recompute when diameter changes. For an explicit diameter
        // we use 2 x diameter; for the auto/zero-diameter case (CP4-SAM) we cannot read the
        // user's diameter, so we apply the seam-double-count FLOOR rather than leaving the
        // overlap unguarded (scientist M1). The same floor is enforced server-side in
        // CellposeParameters.effectiveTileOverlap so scripted runs get it too.
        diameterSpinner.valueProperty().addListener((obs, o, n) -> {
            if (n == null) {
                return;
            }
            int derived;
            if (n > 0) {
                derived = (int) Math.round(2 * n);
            } else {
                derived =
                        Math.max(2 * CellposeParameters.CELLPOSE_DEFAULT_DIAMETER, CellposeParameters.MIN_AUTO_OVERLAP);
            }
            derived = Math.max(2, Math.min(derived, tileSizeSpinner.getValue() / 2));
            tileOverlapSpinner.getValueFactory().setValue(derived);
        });

        grid.addRow(0, new Label("Tile size (px):"), tileSizeSpinner);
        grid.addRow(1, new Label("Tile overlap (px):"), tileOverlapSpinner);
        grid.addRow(2, new Label("Processing resolution (um/px):"), pixelSizeSpinner);

        TitledPane pane = new TitledPane("Tiling and resolution", grid);
        pane.setExpanded(false);
        pane.setAnimated(false);
        return pane;
    }

    private TitledPane buildOutputSection() {
        detectionsRadio = new RadioButton("Detections");
        detectionsRadio.setToggleGroup(outputGroup);
        detectionsRadio.setTooltip(new Tooltip("Create one detection object per segmented object."));
        cellsRadio = new RadioButton("Cells");
        cellsRadio.setToggleGroup(outputGroup);
        cellsRadio.setTooltip(new Tooltip("Create cell objects (nucleus plus an expanded cell boundary)."));
        annotationsRadio = new RadioButton("Annotations");
        annotationsRadio.setToggleGroup(outputGroup);
        annotationsRadio.setTooltip(new Tooltip("Create annotation objects you can edit by hand afterward."));

        cellExpansionSpinner = doubleSpinner(0.0, 1000.0, CellAPposePrefs.DEFAULT_CELL_EXPANSION, 0.5);
        cellExpansionSpinner.setTooltip(
                new Tooltip("Grow each object outward by this many um to approximate the cell boundary. "
                        + "Set to 0 for no expansion."));
        constrainCheck = new CheckBox("Constrain expanded cells to parent object");
        constrainCheck.setTooltip(
                new Tooltip("Keep expanded cells inside their parent annotation; trim any part that spills outside."));

        outputNote = new Label();
        outputNote.setWrapText(true);

        outputGroup.selectedToggleProperty().addListener((obs, o, n) -> updateOutputEnable());
        cellExpansionSpinner.valueProperty().addListener((obs, o, n) -> updateOutputEnable());

        HBox radios = new HBox(12, detectionsRadio, cellsRadio, annotationsRadio);
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.add(new Label("Output as:"), 0, 0);
        grid.add(radios, 1, 0);
        grid.addRow(1, new Label("Cell expansion (um):"), cellExpansionSpinner);
        grid.add(constrainCheck, 0, 2, 2, 1);
        grid.add(outputNote, 0, 3, 2, 1);

        // Output is expanded by default so a first-timer following Quick Start sees the
        // output-type and cell-expansion controls without hunting for them (grad M1/M2).
        TitledPane pane = new TitledPane("Output", grid);
        pane.setExpanded(true);
        pane.setAnimated(false);
        return pane;
    }

    private TitledPane buildComputeSection() {
        gpuCheck = new CheckBox("Use GPU if available (falls back to CPU)");
        gpuCheck.setTooltip(new Tooltip("Run on the GPU when one is available, falling back to the CPU otherwise."));
        VBox box = new VBox(6, gpuCheck);
        TitledPane pane = new TitledPane("Compute", box);
        pane.setExpanded(false);
        pane.setAnimated(false);
        return pane;
    }

    private Region buildRunOnSection() {
        selectedRadio = new RadioButton("Selected annotations");
        selectedRadio.setToggleGroup(runOnGroup);
        selectedRadio.setTooltip(new Tooltip("Run only inside the annotation objects you have selected."));
        wholeImageRadio = new RadioButton("Whole image");
        wholeImageRadio.setToggleGroup(runOnGroup);
        wholeImageRadio.setTooltip(new Tooltip("Run across the entire image."));

        runOnGroup.selectedToggleProperty().addListener((obs, o, n) -> updateCount());

        countLabel = new Label();
        countLabel.setWrapText(true);

        Label label = new Label("Run on:");
        label.setFont(Font.font(null, FontWeight.BOLD, 12));
        HBox radios = new HBox(12, label, selectedRadio, wholeImageRadio);
        radios.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, radios, countLabel);
    }

    // ==================== State updates ====================

    private void loadDefaults() {
        if (CellposeModelFamily.CP4
                .name()
                .equals(CellAPposePrefs.familyProperty().get())) {
            cp4Radio.setSelected(true);
        } else {
            cp3Radio.setSelected(true);
        }
        builtinModelCombo.setValue(CellAPposePrefs.builtinModelProperty().get());
        diameterSpinner
                .getValueFactory()
                .setValue(CellAPposePrefs.diameterProperty().get());
        cellprobSpinner
                .getValueFactory()
                .setValue(CellAPposePrefs.cellprobProperty().get());
        flowSpinner.getValueFactory().setValue(CellAPposePrefs.flowProperty().get());
        minSizeSpinner
                .getValueFactory()
                .setValue(CellAPposePrefs.minSizeProperty().get());
        normalizeCheck.setSelected(CellAPposePrefs.normalizeProperty().get());
        tileSizeSpinner
                .getValueFactory()
                .setValue(CellAPposePrefs.tileSizeProperty().get());
        tileOverlapSpinner
                .getValueFactory()
                .setValue(CellAPposePrefs.tileOverlapProperty().get());
        pixelSizeSpinner
                .getValueFactory()
                .setValue(CellAPposePrefs.pixelSizeProperty().get());
        String out = CellAPposePrefs.outputTypeProperty().get();
        if ("CELLS".equals(out)) {
            cellsRadio.setSelected(true);
        } else if ("ANNOTATIONS".equals(out)) {
            annotationsRadio.setSelected(true);
        } else {
            detectionsRadio.setSelected(true);
        }
        cellExpansionSpinner
                .getValueFactory()
                .setValue(CellAPposePrefs.cellExpansionProperty().get());
        constrainCheck.setSelected(CellAPposePrefs.constrainToParentProperty().get());
        gpuCheck.setSelected(CellAPposePrefs.useGpuProperty().get());

        // Run-on default: selected if any selected, else whole image.
        boolean hasSelected = !selectedAnnotations().isEmpty();
        if (hasSelected) {
            selectedRadio.setSelected(true);
        } else {
            wholeImageRadio.setSelected(true);
        }
        updateOutputEnable();
    }

    private void updateFamilyUI() {
        boolean cp3 = cp3Radio.isSelected();
        cp3Panel.setVisible(cp3);
        cp3Panel.setManaged(cp3);
        cp4Panel.setVisible(!cp3);
        cp4Panel.setManaged(!cp3);
        updateEnvBanner();
    }

    private void updateEnvBanner() {
        CellposeModelFamily family = currentFamily();
        boolean built = ApposeCellposeService.isEnvironmentBuilt(family);
        if (built) {
            envBanner.setText("[i] " + family.envName() + " environment is READY.");
            envBanner.setStyle("-fx-text-fill: " + COLOR_READY + ";");
            if (buildNowButton != null) {
                buildNowButton.setVisible(false);
                buildNowButton.setManaged(false);
            }
        } else {
            envBanner.setText("[!] Environment '" + family.envName() + "' is not built. "
                    + "It will be built on first Run (several GB download).");
            envBanner.setStyle("-fx-text-fill: " + COLOR_WILL_BUILD + ";");
            if (buildNowButton != null) {
                buildNowButton.setVisible(true);
                buildNowButton.setManaged(true);
            }
        }
    }

    /** Default cell expansion (um) auto-applied when Cells is chosen with no expansion set. */
    private static final double DEFAULT_CELLS_EXPANSION_UM = 5.0;

    private void updateOutputEnable() {
        boolean cells = cellsRadio.isSelected();
        cellExpansionSpinner.setDisable(!cells);
        constrainCheck.setDisable(!cells);

        if (cells && cellExpansionSpinner.getValue() != null && cellExpansionSpinner.getValue() <= 0) {
            // Cells with no expansion would produce no visible cell boundary. Auto-set a
            // sensible default and show an inline note rather than failing at submit (grad M2).
            cellExpansionSpinner.getValueFactory().setValue(DEFAULT_CELLS_EXPANSION_UM);
            outputNote.setText("Cell output needs a boundary -- expansion set to " + DEFAULT_CELLS_EXPANSION_UM
                    + " um. Adjust above if needed.");
            outputNote.setStyle("-fx-text-fill: " + COLOR_WILL_BUILD + ";");
        } else if (cells) {
            outputNote.setText("Cells = nucleus plus an expanded boundary (" + cellExpansionSpinner.getValue()
                    + " um expansion).");
            outputNote.setStyle("");
        } else {
            outputNote.setText("");
            outputNote.setStyle("");
        }
    }

    private void updateCount() {
        if (wholeImageRadio.isSelected()) {
            countLabel.setText("This will run on 1 region (whole image).");
            countLabel.setStyle("");
            if (runButton != null) {
                runButton.setDisable(false);
            }
            return;
        }
        int n = selectedAnnotations().size();
        if (n == 0) {
            countLabel.setText("No annotations selected -- choose 'Whole image' or select annotations first.");
            countLabel.setStyle("-fx-text-fill: " + COLOR_WILL_BUILD + ";");
            if (runButton != null) {
                runButton.setDisable(true);
            }
        } else {
            countLabel.setText("This will run on " + n + " parent object" + (n == 1 ? "" : "s") + ".");
            countLabel.setStyle("");
            if (runButton != null) {
                runButton.setDisable(false);
            }
        }
    }

    // ==================== Run ====================

    private void onRun() {
        savePrefs();
        CellposeModelFamily family = currentFamily();
        List<PathObject> parents = resolveParents();
        if (parents.isEmpty()) {
            Dialogs.showErrorMessage("CellAPpose", "Nothing to run on. Select annotations or choose 'Whole image'.");
            return;
        }
        Cellpose2D detector = buildDetector(family);

        final PathObject tempParent = tempWholeImageParent;

        runButton.setDisable(true);
        Thread t = new Thread(
                () -> {
                    try {
                        if (!ApposeCellposeService.isEnvironmentBuilt(family)) {
                            logger.info("Building {} env before detection...", family);
                            ApposeCellposeService.getInstance()
                                    .buildEnvironment(family, msg -> logger.info("[build] {}", msg));
                        } else if (!ApposeCellposeService.getInstance().isAvailable(family)) {
                            // Env exists on disk but service not started yet -- build() reattaches.
                            ApposeCellposeService.getInstance()
                                    .buildEnvironment(family, msg -> logger.info("[build] {}", msg));
                        }
                        // Add the temporary whole-image parent only now, just before
                        // detection, so an env-build failure above never orphans it.
                        if (tempParent != null) {
                            imageData.getHierarchy().addObject(tempParent);
                        }
                        final int nObjects = detector.detectObjects(imageData, parents);
                        Platform.runLater(() -> {
                            if (nObjects == 0) {
                                // A run that found nothing is not a success -- say what to change.
                                Dialogs.showWarningNotification(
                                        "CellAPpose",
                                        "Cellpose finished but found no objects. Check the channel selection, "
                                                + "the object diameter and the requested pixel size, then run again.");
                            } else {
                                Dialogs.showInfoNotification(
                                        "CellAPpose",
                                        "Cellpose detection complete: " + nObjects
                                                + (nObjects == 1 ? " object" : " objects") + " created.");
                            }
                            // Keep the dialog open for repeated runs; just re-enable Run and refresh
                            // the "runs on N objects" count for the next selection.
                            runButton.setDisable(false);
                            updateCount();
                        });
                    } catch (IOException | RuntimeException e) {
                        logger.error("Cellpose detection failed", e);
                        // Remove the temporary whole-image parent on failure so a failed/
                        // cancelled run leaves no orphan annotation in the project.
                        if (tempParent != null) {
                            try {
                                imageData.getHierarchy().removeObject(tempParent, false);
                                imageData.getHierarchy().fireHierarchyChangedEvent(this);
                            } catch (RuntimeException cleanupError) {
                                logger.warn(
                                        "Could not remove temporary whole-image annotation: {}",
                                        cleanupError.getMessage());
                            }
                        }
                        final String msg = e.getMessage();
                        Platform.runLater(() -> {
                            Dialogs.showErrorMessage(
                                    "Cellpose failed",
                                    (msg == null ? "Unknown error" : msg) + ". See the Python Console for details.");
                            runButton.setDisable(false);
                        });
                    }
                },
                "CellAPpose-Run");
        t.setDaemon(true);
        t.start();
    }

    private Cellpose2D buildDetector(CellposeModelFamily family) {
        CellposeBuilder b = Cellpose2D.builder(family)
                .diameter(diameterSpinner.getValue())
                .cellprobThreshold(cellprobSpinner.getValue())
                .flowThreshold(flowSpinner.getValue())
                .minSize(minSizeSpinner.getValue())
                .normalize(normalizeCheck.isSelected())
                .tileSize(tileSizeSpinner.getValue())
                .tileOverlap(tileOverlapSpinner.getValue())
                .pixelSize(pixelSizeSpinner.getValue())
                .cellExpansion(cellExpansionSpinner.getValue())
                // The checkbox is greyed out unless Cells is the output type, so do not let a
                // stale ticked state leak into a Detections/Annotations run.
                .constrainToParent(!constrainCheck.isDisable() && constrainCheck.isSelected())
                .useGpu(gpuCheck.isSelected());

        if (family == CellposeModelFamily.CP3) {
            b.modelName(builtinModelCombo.getValue());
            b.channels(comboChannelIndex(cellChannelCombo), comboChannelIndex(nucleiChannelCombo));
        } else {
            // CP4 (Cellpose-SAM): the up-to-3 channels chosen in the picker (0-based source indices).
            int nCh = imageData.getServer().nChannels();
            b.channelsCP4(
                    nCh,
                    comboChannelIndex0Based(cp4Channel1Combo),
                    comboChannelIndex0Based(cp4Channel2Combo),
                    comboChannelIndex0Based(cp4Channel3Combo));
        }

        if (cellsRadio.isSelected()) {
            b.createCells();
        } else if (annotationsRadio.isSelected()) {
            b.createAnnotations();
        } else {
            b.createDetections();
        }
        return b.build();
    }

    /**
     * Resolves the parent objects to run on. For whole-image, creates a full-image
     * annotation but DOES NOT add it to the hierarchy here -- it is added just before
     * detection and removed on failure (see {@link #onRun}) so a failed/cancelled run
     * never leaves an orphan annotation in the project (clinical M3).
     *
     * @return the parents; {@code tempWholeImageParent} is set iff whole-image was chosen
     */
    private List<PathObject> resolveParents() {
        if (wholeImageRadio.isSelected()) {
            var server = imageData.getServer();
            ROI roi =
                    ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(), ImagePlane.getDefaultPlane());
            PathObject whole = PathObjects.createAnnotationObject(roi);
            tempWholeImageParent = whole;
            return List.of(whole);
        }
        tempWholeImageParent = null;
        return new ArrayList<>(selectedAnnotations());
    }

    // ==================== Helpers ====================

    private CellposeModelFamily currentFamily() {
        return cp4Radio.isSelected() ? CellposeModelFamily.CP4 : CellposeModelFamily.CP3;
    }

    private List<PathObject> selectedAnnotations() {
        List<PathObject> result = new ArrayList<>();
        if (imageData == null) {
            return result;
        }
        for (PathObject obj : imageData.getHierarchy().getSelectionModel().getSelectedObjects()) {
            if (obj != null && obj.isAnnotation() && obj.hasROI()) {
                result.add(obj);
            }
        }
        return result;
    }

    private void populateChannelCombos() {
        List<String> names = new ArrayList<>();
        names.add("None");
        var server = imageData.getServer();
        int nCh = server.nChannels();
        List<String> channelNames = new ArrayList<>();
        for (int i = 0; i < nCh; i++) {
            ImageChannel ch = server.getMetadata().getChannel(i);
            String name = ch != null && ch.getName() != null ? ch.getName() : "Channel " + (i + 1);
            names.add(name);
            channelNames.add(name);
        }
        cellChannelCombo.getItems().setAll(names);
        nucleiChannelCombo.getItems().setAll(names);
        cp4Channel1Combo.getItems().setAll(names);
        cp4Channel2Combo.getItems().setAll(names);
        cp4Channel3Combo.getItems().setAll(names);

        String firstChannel = names.size() > 1 ? names.get(1) : "None";

        // Restore the previous channel choices only if this image's channel list matches the one
        // they were saved against; otherwise fall back to sensible defaults.
        if (channelSignature(nCh, channelNames)
                .equals(CellAPposePrefs.channelSignatureProperty().get())) {
            setComboOrDefault(
                    cellChannelCombo, CellAPposePrefs.cp3CellChannelProperty().get(), firstChannel);
            setComboOrDefault(
                    nucleiChannelCombo,
                    CellAPposePrefs.cp3NucleiChannelProperty().get(),
                    "None");
            setComboOrDefault(
                    cp4Channel1Combo, CellAPposePrefs.cp4Channel1Property().get(), firstChannel);
            setComboOrDefault(
                    cp4Channel2Combo, CellAPposePrefs.cp4Channel2Property().get(), "None");
            setComboOrDefault(
                    cp4Channel3Combo, CellAPposePrefs.cp4Channel3Property().get(), "None");
        } else {
            cellChannelCombo.setValue(firstChannel); // CP3 cell = first channel
            nucleiChannelCombo.setValue("None");
            // Cellpose-SAM is handed exactly the channels picked here, so on an RGB image a
            // one-channel default would segment a single colour plane while the CP3 path on
            // the same image sees all three. Default RGB to the full 3-channel stack.
            boolean rgbDefault = server.isRGB() && names.size() > 3;
            cp4Channel1Combo.setValue(firstChannel); // CP4 channel 1 = first channel
            cp4Channel2Combo.setValue(rgbDefault ? names.get(2) : "None");
            cp4Channel3Combo.setValue(rgbDefault ? names.get(3) : "None");
        }
    }

    /** A stable fingerprint of the image's channel list, used to gate channel-selection restore. */
    private static String channelSignature(int nChannels, List<String> channelNames) {
        return nChannels + ":" + String.join(" | ", channelNames);
    }

    /** Sets the combo to {@code value} if it is an available item, otherwise to {@code fallback}. */
    private static void setComboOrDefault(ComboBox<String> combo, String value, String fallback) {
        if (value != null && combo.getItems().contains(value)) {
            combo.setValue(value);
        } else {
            combo.setValue(fallback);
        }
    }

    /**
     * Maps a channel combo selection to a 1-based channel index, or null for None.
     * Index 0 in the combo is "None"; index i (>=1) is channel i.
     */
    private Integer comboChannelIndex(ComboBox<String> combo) {
        int idx = combo.getSelectionModel().getSelectedIndex();
        if (idx <= 0) {
            return null; // None
        }
        return idx; // 1-based channel index
    }

    /**
     * Maps a channel combo selection to a 0-based source channel index, or null for None.
     * Used for CP4 (Cellpose-SAM), whose {@code chan0/1/2} are 0-based source indices.
     * Index 0 in the combo is "None"; index i (>=1) is source channel i-1.
     */
    private Integer comboChannelIndex0Based(ComboBox<String> combo) {
        int idx = combo.getSelectionModel().getSelectedIndex();
        if (idx <= 0) {
            return null; // None
        }
        return idx - 1; // 0-based source channel index
    }

    private void savePrefs() {
        CellAPposePrefs.familyProperty().set(currentFamily().name());
        CellAPposePrefs.builtinModelProperty().set(builtinModelCombo.getValue());
        CellAPposePrefs.diameterProperty().set(diameterSpinner.getValue());
        CellAPposePrefs.cellprobProperty().set(cellprobSpinner.getValue());
        CellAPposePrefs.flowProperty().set(flowSpinner.getValue());
        CellAPposePrefs.minSizeProperty().set(minSizeSpinner.getValue());
        CellAPposePrefs.normalizeProperty().set(normalizeCheck.isSelected());
        CellAPposePrefs.tileSizeProperty().set(tileSizeSpinner.getValue());
        CellAPposePrefs.tileOverlapProperty().set(tileOverlapSpinner.getValue());
        CellAPposePrefs.pixelSizeProperty().set(pixelSizeSpinner.getValue());
        String out = cellsRadio.isSelected() ? "CELLS" : (annotationsRadio.isSelected() ? "ANNOTATIONS" : "DETECTIONS");
        CellAPposePrefs.outputTypeProperty().set(out);
        CellAPposePrefs.cellExpansionProperty().set(cellExpansionSpinner.getValue());
        CellAPposePrefs.constrainToParentProperty().set(constrainCheck.isSelected());
        CellAPposePrefs.useGpuProperty().set(gpuCheck.isSelected());

        // Channel selections + the signature of the image they were chosen against, so they can be
        // restored next time IF the next image has the same channel list.
        var server = imageData.getServer();
        int nCh = server.nChannels();
        List<String> channelNames = new ArrayList<>();
        for (int i = 0; i < nCh; i++) {
            ImageChannel ch = server.getMetadata().getChannel(i);
            channelNames.add(ch != null && ch.getName() != null ? ch.getName() : "Channel " + (i + 1));
        }
        CellAPposePrefs.channelSignatureProperty().set(channelSignature(nCh, channelNames));
        CellAPposePrefs.cp3CellChannelProperty().set(cellChannelCombo.getValue());
        CellAPposePrefs.cp3NucleiChannelProperty().set(nucleiChannelCombo.getValue());
        CellAPposePrefs.cp4Channel1Property().set(cp4Channel1Combo.getValue());
        CellAPposePrefs.cp4Channel2Property().set(cp4Channel2Combo.getValue());
        CellAPposePrefs.cp4Channel3Property().set(cp4Channel3Combo.getValue());
    }

    private static Spinner<Double> doubleSpinner(double min, double max, double init, double step) {
        Spinner<Double> spinner = new Spinner<>();
        SpinnerValueFactory.DoubleSpinnerValueFactory factory =
                new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, init, step);
        spinner.setValueFactory(factory);
        spinner.setEditable(true);
        return spinner;
    }

    private static Spinner<Integer> intSpinner(int min, int max, int init, int step) {
        Spinner<Integer> spinner = new Spinner<>();
        SpinnerValueFactory.IntegerSpinnerValueFactory factory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, init, step);
        spinner.setValueFactory(factory);
        spinner.setEditable(true);
        return spinner;
    }
}
