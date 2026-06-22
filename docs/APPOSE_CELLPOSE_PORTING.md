# APPOSE_CELLPOSE_PORTING

Developer porting doc for the Appose Cellpose backend in `qupath-extension-cellAPpose`.
Read this before touching anything under `src/main/resources/qupath/ext/cellappose/scripts/`
or the `cp3.toml` / `cp4.toml` pixi manifests.

## 0. Purpose and audience

This records the exact Appose input/output contract the vendored Cellpose scripts expect,
every change we made to them and why, the two-environment design, the BSD-3 attribution, and
the version-dance rule. Audience: a developer maintaining the Appose backend, not an end user.

## 1. Backend overview

The Python comes from `Image-Analysis-Hub/imglib2-cellpose` (`src/main/resources/`). Five files
are vendored verbatim (BSD-3-Clause headers preserved) into
`src/main/resources/qupath/ext/cellappose/scripts/`:

- `cp3.py`, `cp3_init.py` -- Cellpose 3 per-tile + model-init scripts.
- `cp4.py`, `cp4_init.py` -- Cellpose-SAM (Cellpose 4) per-tile + model-init scripts.
- `cp_utils.py` -- `get_torch_device(use_gpu)` (CUDA > MPS > CPU selection).

Plus `verify_env.py`, which is original to this project (not vendored): it imports cellpose +
torch and reports versions + CUDA/MPS availability as task outputs so the Java service can
confirm the env resolved.

We **vendor** the Python rather than depend on imglib2-cellpose's Java gateway. To be precise
about what that avoids: `net.imglib2:imglib2-cellpose` (the library) pulls in **ImgLib2 only**
(`org.apposed:appose` + `net.imglib2:imglib2-appose`); its `imglib2-ij` dependency is
`<scope>test</scope>` and does NOT propagate, so the library brings **no ImageJ** at compile/runtime.
(ImageJ deps -- `net.imagej:ij`, `net.imagej:imagej`, `imglib2-ij` -- live in the separate
`cellpose-appose` *Fiji plugin*, not the library.) So depending on the gateway would have meant
adopting ImgLib2 and its `RandomAccessibleInterval`/`ShmImg`/`AxisInfo` data model -- not ImageJ.
We vendor instead to (1) follow the monorepo Appose-service convention (own `ApposeCellposeService`
mirroring `ApposePPMService`) and (2) keep the extension natively `BufferedImage`/`NDArray` with no
RAI bridging. Appose pin: `org.apposed:appose:0.11.0` (Java/Maven) with the pixi manifests requesting
`appose >=0.10.1`. The `APPOSE_REFERENCE.md` 0.10.0 playbook holds on 0.11.0 (fiber-analysis / DL
classifier precedent).

## 2. The Appose contract

### 2a. Inputs are bare globals (full list)

The vendored `cp3.py`/`cp4.py` read inputs via `globals()['...']` (Appose injects task inputs
into script scope). The Java side (`CellposeParameters.toApposeInputs`) supplies every key.

Per-tile inputs (`cp3.py` / `cp4.py`), shared keys:

| Key | Type | Value supplied |
|---|---|---|
| `input` | NDArray | the tile (YX uint8 gray, or YXC uint8 RGB) |
| `output_labels` | NDArray | pre-allocated YX uint16 (written in place) |
| `output_flows` | None | flows not computed in the 2D WSI path |
| `compute_flows` | bool | `false` |
| `z_axis` | None | 2D |
| `t_axis` | None | 2D |
| `channel_axis` | int / None | `2` when the tile is YXC, else `None` |
| `anisotropy` | float | `1.0` |
| `diameter` | float | from the dialog (0 = auto) |
| `stitch_threshold` | float | `0.0` (QuPath does seam merging, not Cellpose) |
| `resample` | bool | `true` |
| `normalize` | bool | from the dialog |
| `flow_threshold` | float | from the dialog |
| `cellprob_threshold` | float | from the dialog |
| `min_size` | int | from the dialog |
| `tile_overlap` | float | `0.1` (cellpose internal sub-tiling fraction, NOT our tile overlap) |
| `flow3D_smooth` | int | `0` |
| `niter` | None | default |
| `use_gpu` | bool | from the dialog |
| `use_3D` | bool | `false` |
| `model_name` | str | built-in name (cp3) or `cyto3` placeholder |
| `custom_model` | str / None | custom model path or None |

CP3-only per-tile keys: `cell_channel` (int / None), `nuclei_channel` (int / None).
CP4-only per-tile keys: `n_channels` (int), `chan0`, `chan1`, `chan2` (int / None).

Init inputs (`cp3_init.py` / `cp4_init.py`):

| Key | Type | Notes |
|---|---|---|
| `use_gpu` | bool | GPU choice |
| `custom_model` | str / None | None => built-in model |
| `model_name` | str | CP3 only (e.g. `cyto3`) |

### 2b. Pre-allocated outputs + model caching

Outputs are written into the **pre-allocated** `output_labels` NDArray via
`output_labels[:] = masks` (NOT `task.outputs[...]`). The Java side allocates a YX uint16
`output_labels` NDArray of the tile size (`NDArrays.allocateLabelNDArray`), puts it in the input
map, runs the task, then reads it back via `NDArrays.readLabelsAsFloat` after `waitFor()`.

DType decision: **uint16**. A single WSI tile will not exceed 65535 distinct labels at the
default 1024 px tile size; uint32 is the documented fallback if a pathological tile could.

Model caching: `cp3_init.py` / `cp4_init.py` load the model once and `task.export(model=model)`;
the per-tile scripts read `globals()['model']` and reuse it. The service re-runs the init script
only when the model-cache key (`CellposeParameters.modelCacheKey()` = family + model + custom
path + gpu) changes (`ApposeCellposeService.ensureModel`). Init-globals starting with `_` are
stripped by the worker -- `model` is the only exported name and is fine.

Task-script caching (throughput): the per-tile task script string is loaded from the JAR **once**
at build time and cached in the family's `EnvHandle.cachedTaskScript`, then reused for every tile
(`ApposeCellposeService.runTile`) instead of re-reading + re-parsing the resource per tile. This
does NOT change the Python contract -- it is the same script string, just loaded once. (Phase 5,
scientist M2.) Per-tile Appose submission stays serialized per the concurrency note. Provenance
stamping (run_id / params_hash / parameter metadata) and the History WorkflowStep are entirely
Java-side (`Cellpose2D.stampProvenance` + `DefaultScriptableWorkflowStep`) and required NO change
to the vendored Python.

## 3. Changes we made to the vendored scripts (TABLE)

Every change preserves the upstream BSD-3 header and is ASCII-only. The sanctioned changes are
(1) a thin default-shim for bare globals the upstream appose-mode branch reads but does not load,
and (2) an optional `task.outputs['n_labels']` symmetry mirror. We did NOT touch the cellpose
logic.

| File | Change | Reason |
|---|---|---|
| `cp3.py` | Added `compute_flows = bool(globals().get('compute_flows', False))` at the top of the `if appose_mode:` input block. | Upstream reads `compute_flows` as a bare global at the write-back (`if compute_flows:`), but only assigns it in the standalone branch -- in appose_mode it would NameError. Defaulting to False matches our 2D path. |
| `cp3.py` | Added `task.outputs['n_labels'] = int(masks.max()) if masks.size else 0` after `output_labels[:] = masks`. | Symmetry mirror -- lets Java sanity-check the round-trip without reading the shared buffer. Masks still go through the pre-allocated NDArray. |
| `cp4.py` | Added `compute_flows = bool(globals().get('compute_flows', False))` at the top of the `if appose_mode:` input block. | Same NameError guard as cp3.py. |
| `cp4.py` | Added `task.outputs['n_labels'] = ...` after `output_labels[:] = masks`. | Same symmetry mirror as cp3.py. |
| `cp3_init.py` | Added `model_name = globals().get('model_name', 'cyto3')` and `custom_model = globals().get('custom_model', None)` at the top of the `if appose_mode:` block. | `selected_model = model_name if custom_model is None else None` reads both as bare globals; defaulting degrades to cyto3 / built-in instead of NameError if a key is omitted. |
| `cp4_init.py` | Added `custom_model = globals().get('custom_model', None)` at the top of the `if appose_mode:` block. | `selected_model = "cpsam" if custom_model is None else custom_model` reads `custom_model` as a bare global; defaulting degrades to the built-in cpsam model. |
| `cp_utils.py` | None (verbatim). | Used as-is by the task scripts for device selection. |
| `verify_env.py` | New file (not vendored). | Asserts cellpose/torch import, reports versions + CUDA/MPS via task outputs. Pre-imports numpy first (Windows deadlock guard). |

Notes:
- `cp3.py`'s `if compute_flows:` guards now match `cp4.py`'s for the 2D `output_flows=None` path
  (both default `compute_flows` to False), confirming the design's open question.
- The upstream `appose_mode = 'task' in globals()` already gives a clean seam: all our shims live
  inside the `if appose_mode:` branch and never touch the standalone path.

## 4. Two-environment design (cp3 + cp4)

cp3 needs `cellpose>=3,<4`; cp4 needs `cellpose>=4,<5` -- mutually exclusive PyPI constraints, so
one Python env physically cannot host both (upstream proves this via pixi `[environments]`). We
ship two bundled manifests, `cp3.toml` and `cp4.toml`, and two family-keyed Appose env handles in
`ApposeCellposeService` (a `Map<CellposeModelFamily, EnvHandle>`). PPM's single-env helpers are
generalized to take a `CellposeModelFamily`:

- `getEnvironmentPath(family)` -> `~/.local/share/appose/cellappose-cp3` / `-cp4`
- `isEnvironmentBuilt(family)` -> filesystem check on that family's `.pixi/`
- `buildEnvironment(family, callback)` -> sync toml, build, `pixi install`, verify
- `ensureModel(family, key, inputs)` -> run the family init script (caches `model`)
- `runTile(family, inputs)` -> run the family task script
- `deleteEnvironment(family)`

Each env builds **lazily on first use** of its family. Disk cost: a user who runs both families
pays ~2x footprint (PyTorch is the bulk and is duplicated) -- an accepted testbed tradeoff.
Manifests follow the monorepo conda-forge-pytorch pattern (`pytorch` global +
`[target.<plat>.dependencies] pytorch-gpu` on linux-64/win-64), with cellpose from PyPI
(`[pypi-dependencies] cellpose`). osx-64 is omitted (pixi cross-resolve panic; Intel Macs unsupported).

## 5. BSD-3 attribution and THIRD_PARTY notice

The vendored `cp*.py` keep their upstream BSD-3-Clause copyright headers verbatim. A
`THIRD_PARTY.md` ships in the JAR (`src/main/resources/qupath/ext/cellappose/THIRD_PARTY.md`)
listing imglib2-cellpose (BSD-3, vendored) and BIOP's `qupath-extension-cellpose` (Apache-2.0,
architecture inspiration only -- not copied). The README's "Sources & attribution" section is the
user-facing copy. The extension links QuPath (GPL); the extension's own license is settled by the
project license review before release (do not assume Apache-2.0).

## 6. Version-dance rule

We vendor **flat `scripts/*.py`** loaded from JAR resources every run (always current -- no
version dance needed for `scripts/*.py`). **If** a future revision bundles a `cellapposelib/`
package (or pip-installs from a git URL), the `appose-cached-package-version-dance` rule kicks in:
pair a `_version.py` bump with a `REQUIRED_*_VERSION` bump in the service whenever `*lib/*.py`
changes, or the user's Appose env keeps stale code. **Not needed for the flat `scripts/` layout we
ship in v1.**

## 9. Runtime integration findings (WSL smoke, 2026-06-08)

The first live Appose round-trip (build cp4 env + run a real detection headless) surfaced four
integration issues that unit tests and static lints cannot catch. All fixed; round-trip verified
(cellpose 4.1.1, torch 2.11.0, CUDA; 26 detections on a 640x640 / 2x2-tile synthetic image;
provenance + WorkflowStep present; no orphan annotation).

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 1 | `NameError: name 'get_torch_device' is not defined` in `cp4_init.py` | The vendored scripts `from cp_utils import get_torch_device` ONLY under their non-appose (`else`) branch; in appose mode they expect `get_torch_device` already in the worker's global scope. Our init script supplied only `import numpy`. | **The cp_utils helpers must be injected as Appose init globals.** `ApposeCellposeService.buildEnvironment` now runs `pythonService.init("import numpy\n" + <cp_utils.py source>)`. Appose exports non-underscore init globals into every task scope, so `get_torch_device` is then defined for `cp3.py`/`cp4.py`/`*_init.py`. **This is the key contract detail for the vendored scripts** -- do not remove cp_utils from the init. |
| 2 | `IllegalStateException: Toolkit not initialized` building the env headless | `PythonConsoleWindow.appendMessage` called `Platform.runLater` unconditionally; no FX toolkit exists in `QuPath script` batch mode (a legitimate scripting-API entry point). | Guarded the `Platform.runLater` with try/catch(IllegalStateException); the message is already queued, so headless just drops the FX flush and bounds the queue. |
| 3 | `IOException: cellAPpose CP4 service is not available` from a scripted `detectObjects` | The GUI dialog does build-then-run; the scripting API reached `ensureModel` with no initialized service. | `Cellpose2D.detectObjects` now lazily calls `service.buildEnvironment(family, ...)` when `!isAvailable(family)` (idempotent; cheap once the env exists on disk). |
| 4 | Provenance `model_name=cyto3` on a CP4 detection; `verify_env` reported `cellpose=unknown` | `modelName` defaults to the CP3 value `cyto3` and CP4 ignores it (always `cpsam`); cellpose 4 exposes no top-level `__version__`. | Added `CellposeParameters.effectiveModelName()` (CP4 -> `cpsam`, custom path wins) used in provenance; `verify_env.py` falls back to `importlib.metadata.version("cellpose")`. |

None of these touched the cellpose segmentation logic in the vendored scripts. Item 1 is a
loading-mechanism fix (Java service), not a script edit; the only original-script edit is item 4's
`verify_env.py` (our own file) version fallback.

## 10. Multichannel + --frozen fixes (2026-06-09, Windows-surfaced)

Two bugs surfaced when the user ran CP3 on a real multichannel fluorescence image
(`Orion6.ome.tif`, 7-channel) on Windows. Both fixed and verified on Linux with a synthetic
4-channel image (CP3, cell_channel=4 -> 24 detections, no error; cp3 env builds clean).

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 5 | `IndexError: index 5 is out of bounds for axis 2 with size 3` in cellpose `reshape`, when a channel above 3 was selected on a fluorescence image | `Cellpose2D.runTile` classified any image with `numBands >= 3` as RGB and packed only bands 0-2 via `getRGB()`, dropping bands 4+. cellpose maps the 1-based channel value c to band c-1, so picking channel 6 needs >=6 bands present. | Only a TRUE `server.isRGB()` image uses the packed-RGB path. Multichannel (`nBands > 1`, not RGB) now goes through new `NDArrays.bufferedImageToMultiChannelNDArray` -> `(H, W, nBands)` float32 preserving every band in QuPath channel order. The combo's 1-based index already equals cellpose's 1-based channel value, so band c-1 == QuPath channel c-1; no index remapping needed. |
| 6 | `error: unexpected argument '--frozen' found` -> `pixi build failed` (env never builds) | The locked-envs change passed `.flags(List.of("--frozen"))` to `Appose.pixi()`. Appose's PixiBuilder injects builder flags as GLOBAL pixi args (`pixi --frozen ...`), which pixi rejects. | Removed `.flags("--frozen")` from the builder chain. The committed lock is still staged into the env dir by `syncManifest`, and `runPixiInstall` runs `pixi install --frozen --manifest-path ...` (subcommand flag, correct), so the frozen-from-lock intent is preserved. NOTE: the other Appose extensions touched by the same locked-envs change should be checked for this same broken `.flags("--frozen")` builder call. |

## 11. Multichannel channel-EXTRACTION (2026-06-09, supersedes the sec-10 #5 approach)

Fix #5 (preserve ALL bands) was necessary but not sufficient. On the real 18-channel Orion6 image,
passing cellpose a (H,W,18) tile made it mis-read the 18-channel axis as a **Z-stack** even with
`channel_axis=2` set ("z_axis not specified, assuming it is dim 2 ... masks are made per plane
only"), producing masks of shape (18,H,W) -> `ValueError: could not broadcast (18,248,293) into
(248,293)`. Same failure for a 1-channel or a 2-channel selection, because the tile still carried
all 18 bands.

**Fix:** for CP3 on a non-RGB multichannel image, EXTRACT only the user's selected channel(s) into
a compact tile and re-index the cellpose channels:
- `NDArrays.bufferedImageToSelectedChannelsNDArray(img, bands0)` builds `(H,W)` (one band) or
  `(H,W,2)` (cyto+nucleus) float32.
- `CellposeParameters.cp3SourceBands()` returns the 0-based source bands `[cell-1, nuclei-1]`.
- `Cellpose2D.runTile` overrides the Appose inputs to `cell_channel=0,nuclei_channel=0` (single ->
  cellpose grayscale `[0,0]`, channel_axis=None) or `cell_channel=1,nuclei_channel=2` (pair ->
  `[1,2]`, channel_axis=2). cellpose never sees more than 2 channels, so no Z mis-read.

This is the standard cellpose multichannel idiom (and matches BIOP). Verified on a synthetic
18-channel image: CP3 1-channel -> 22 detections, CP3 2-channel -> 36 detections, no broadcast
error. NOTE: CP4 (cpsam) multichannel still passes all bands and is UNVERIFIED on >3-channel images
-- a likely follow-up if CP4 is run on Orion-class data.

## 12. v0.2.0 -- CP4 channel selection, channel persistence, non-closing dialog (2026-06-09)

Three dialog features, applied across CP3 and CP4. None required a vendored-script change.

**CP4 channel selection (backend).** Cellpose-SAM takes up to 3 channels; cp4.py selects them via
`input_image[..., channels, :, :]`, which indexes the channel axis at **-3 (channels-first)**. So for
CP4 multichannel we extract the user's chosen channels into a CHANNELS-FIRST `(k,H,W)` tile
(`NDArrays.bufferedImageToSelectedChannelsCHWNDArray` + `CellposeParameters.cp4SourceBands`), set
`channel_axis=0`, and pass compact chan indices `0..k-1`. cpsam never sees >3 channels (no Z
mis-read). Note the CP3 vs CP4 LAYOUT difference: CP3 uses channels-LAST `(H,W,k)` with cellpose
`channels=[0,0]`/`[1,2]`; CP4 uses channels-FIRST `(k,H,W)` with `channel_axis=0`. Verified on a
synthetic 18-channel image: CP4 1-channel -> 25 detections, CP4 2-channel -> 43, no broadcast error.

**CP4 channel picker (UI).** The CP4 panel now has 3 channel combos (Channel 1/2/3), each an image
channel or None. `comboChannelIndex0Based` maps a selection to a 0-based source index for
`channelsCP4(...)`. (CP3 keeps its 1-based cell/nuclei combos.)

**Channel persistence.** `CellAPposePrefs` gained a `channelSignature` (nCh + joined channel names)
plus the CP3 cell/nuclei and CP4 channel-1/2/3 selections (by display name). On dialog open, the
channel choices are restored only when the current image's signature matches the saved one;
otherwise defaults apply. All other settings already persisted via prefs.

**Non-closing dialog.** The Run dialog is now `Modality.NONE`; Run no longer closes it (re-enables
Run + refreshes the count), so the user can draw/adjust annotations and run repeatedly. "Cancel" is
now "Close".
