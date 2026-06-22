package qupath.ext.cellappose;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Persistent preferences for the CellAPpose extension (single PathPrefs namespace).
 *
 * <p>Defaults match the "Smart defaults" table in {@code wip_ui_design.md} section 7.
 * The Run dialog reads these on open and writes them back so values persist across
 * sessions.
 *
 * <p>Pattern source: {@code qupath-extension-confusion-matrix/.../CMPreferences.java}
 * via fiber-analysis's {@code FiberAnalysisPreferences}.
 */
public final class CellAPposePrefs {

    private static final Logger logger = LoggerFactory.getLogger(CellAPposePrefs.class);
    private static final String PREFIX = "cellappose.";

    // Defaults (wip_ui_design.md section 7)
    public static final String DEFAULT_FAMILY = "CP3";
    public static final String DEFAULT_BUILTIN_MODEL = "cyto3";
    public static final double DEFAULT_DIAMETER = 30.0;
    public static final double DEFAULT_CELLPROB = 0.0;
    public static final double DEFAULT_FLOW = 0.4;
    public static final int DEFAULT_MIN_SIZE = 15;
    public static final boolean DEFAULT_NORMALIZE = true;
    public static final int DEFAULT_TILE_SIZE = 1024;
    public static final int DEFAULT_TILE_OVERLAP = 60;
    public static final double DEFAULT_PIXEL_SIZE = 0.5;
    public static final String DEFAULT_OUTPUT_TYPE = "DETECTIONS";
    public static final double DEFAULT_CELL_EXPANSION = 0.0;
    public static final boolean DEFAULT_CONSTRAIN_TO_PARENT = true;
    public static final boolean DEFAULT_USE_GPU = true;

    private static StringProperty family;
    private static StringProperty builtinModel;
    private static DoubleProperty diameter;
    private static DoubleProperty cellprob;
    private static DoubleProperty flow;
    private static IntegerProperty minSize;
    private static BooleanProperty normalize;
    private static IntegerProperty tileSize;
    private static IntegerProperty tileOverlap;
    private static DoubleProperty pixelSize;
    private static StringProperty outputType;
    private static DoubleProperty cellExpansion;
    private static BooleanProperty constrainToParent;
    private static BooleanProperty useGpu;

    // Channel selections, restored only when the image's channel list matches the saved one
    // (channelSignature). Stored by display name; "None" means unset.
    private static StringProperty channelSignature;
    private static StringProperty cp3CellChannel;
    private static StringProperty cp3NucleiChannel;
    private static StringProperty cp4Channel1;
    private static StringProperty cp4Channel2;
    private static StringProperty cp4Channel3;

    private static boolean installed = false;

    private CellAPposePrefs() {}

    /** Installs persistent preferences. Idempotent. */
    public static synchronized void installPreferences() {
        if (installed) {
            return;
        }
        logger.info("Installing CellAPpose preferences");
        family = PathPrefs.createPersistentPreference(PREFIX + "family", DEFAULT_FAMILY);
        builtinModel = PathPrefs.createPersistentPreference(PREFIX + "builtinModel", DEFAULT_BUILTIN_MODEL);
        diameter = PathPrefs.createPersistentPreference(PREFIX + "diameter", DEFAULT_DIAMETER);
        cellprob = PathPrefs.createPersistentPreference(PREFIX + "cellprob", DEFAULT_CELLPROB);
        flow = PathPrefs.createPersistentPreference(PREFIX + "flow", DEFAULT_FLOW);
        minSize = PathPrefs.createPersistentPreference(PREFIX + "minSize", DEFAULT_MIN_SIZE);
        normalize = PathPrefs.createPersistentPreference(PREFIX + "normalize", DEFAULT_NORMALIZE);
        tileSize = PathPrefs.createPersistentPreference(PREFIX + "tileSize", DEFAULT_TILE_SIZE);
        tileOverlap = PathPrefs.createPersistentPreference(PREFIX + "tileOverlap", DEFAULT_TILE_OVERLAP);
        pixelSize = PathPrefs.createPersistentPreference(PREFIX + "pixelSize", DEFAULT_PIXEL_SIZE);
        outputType = PathPrefs.createPersistentPreference(PREFIX + "outputType", DEFAULT_OUTPUT_TYPE);
        cellExpansion = PathPrefs.createPersistentPreference(PREFIX + "cellExpansion", DEFAULT_CELL_EXPANSION);
        constrainToParent =
                PathPrefs.createPersistentPreference(PREFIX + "constrainToParent", DEFAULT_CONSTRAIN_TO_PARENT);
        useGpu = PathPrefs.createPersistentPreference(PREFIX + "useGpu", DEFAULT_USE_GPU);
        channelSignature = PathPrefs.createPersistentPreference(PREFIX + "channelSignature", "");
        cp3CellChannel = PathPrefs.createPersistentPreference(PREFIX + "cp3CellChannel", "");
        cp3NucleiChannel = PathPrefs.createPersistentPreference(PREFIX + "cp3NucleiChannel", "");
        cp4Channel1 = PathPrefs.createPersistentPreference(PREFIX + "cp4Channel1", "");
        cp4Channel2 = PathPrefs.createPersistentPreference(PREFIX + "cp4Channel2", "");
        cp4Channel3 = PathPrefs.createPersistentPreference(PREFIX + "cp4Channel3", "");
        installed = true;
        logger.info("CellAPpose preferences installed");
    }

    public static StringProperty familyProperty() {
        return family;
    }

    public static StringProperty builtinModelProperty() {
        return builtinModel;
    }

    public static DoubleProperty diameterProperty() {
        return diameter;
    }

    public static DoubleProperty cellprobProperty() {
        return cellprob;
    }

    public static DoubleProperty flowProperty() {
        return flow;
    }

    public static IntegerProperty minSizeProperty() {
        return minSize;
    }

    public static BooleanProperty normalizeProperty() {
        return normalize;
    }

    public static IntegerProperty tileSizeProperty() {
        return tileSize;
    }

    public static IntegerProperty tileOverlapProperty() {
        return tileOverlap;
    }

    public static DoubleProperty pixelSizeProperty() {
        return pixelSize;
    }

    public static StringProperty outputTypeProperty() {
        return outputType;
    }

    public static DoubleProperty cellExpansionProperty() {
        return cellExpansion;
    }

    public static BooleanProperty constrainToParentProperty() {
        return constrainToParent;
    }

    public static BooleanProperty useGpuProperty() {
        return useGpu;
    }

    public static StringProperty channelSignatureProperty() {
        return channelSignature;
    }

    public static StringProperty cp3CellChannelProperty() {
        return cp3CellChannel;
    }

    public static StringProperty cp3NucleiChannelProperty() {
        return cp3NucleiChannel;
    }

    public static StringProperty cp4Channel1Property() {
        return cp4Channel1;
    }

    public static StringProperty cp4Channel2Property() {
        return cp4Channel2;
    }

    public static StringProperty cp4Channel3Property() {
        return cp4Channel3;
    }
}
