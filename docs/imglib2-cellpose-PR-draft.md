# Draft PR to `Image-Analysis-Hub/imglib2-cellpose`

**Title:** Make the Appose scripts robust for non-Fiji consumers (`compute_flows` default + `get_torch_device` availability)

**Status:** draft for the maintainers. Both changes are **backward-compatible** with the Fiji
plugin (`cellpose-appose`), which already sends `compute_flows` and injects `cp_utils` — see
"Fiji compatibility" below.

## Motivation

While building a QuPath extension that drives these scripts via Appose (vendoring `cp3.py`,
`cp4.py`, `cp3_init.py`, `cp4_init.py`, `cp_utils.py`), two latent issues surfaced that affect
**any** Appose consumer other than the Fiji plugin:

1. **`compute_flows` is undefined in appose mode.** `cp3.py` / `cp4.py` reference `compute_flows`
   at write-back (`if compute_flows:`), but only assign it in the standalone (`else`) branch. The
   Fiji caller passes it as a task input, so it happens to resolve as an injected global; a
   consumer that does not pass it gets `NameError: name 'compute_flows' is not defined`.

2. **`get_torch_device` is assumed pre-injected in appose mode.** All four scripts call
   `use_gpu, device = get_torch_device(use_gpu)` but only `from cp_utils import get_torch_device`
   in the non-appose branch. In appose mode they rely on the host having injected `cp_utils`'s
   helpers as init globals. A consumer that does not know to do this gets
   `NameError: name 'get_torch_device' is not defined` (this cost us a debugging session).

## Proposed changes (all backward-compatible)

### 1. Default `compute_flows` in appose mode -- `cp3.py` and `cp4.py`

In the `if appose_mode:` input-loading block, add a defaulted read near the top:

```diff
 if appose_mode:
+    # compute_flows is referenced at write-back but only assigned in the standalone branch
+    # below; default it here so an Appose caller that does not pass it does not NameError.
+    compute_flows = bool(globals().get('compute_flows', False))
     fiji_image = globals()['input']
     fiji_output_labels = globals()['output_labels']
     ...
```

`globals().get('compute_flows', False)` returns the caller-supplied value when present (Fiji), and
`False` otherwise -- so existing callers are unaffected.

### 2. Make `get_torch_device` available in appose mode -- `cp3.py`, `cp4.py`, `cp3_init.py`, `cp4_init.py`

In the `if appose_mode:` branch where `task` is obtained, fall back to importing `cp_utils` only
when the host did not already inject it:

```diff
 if appose_mode:
     if TYPE_CHECKING:
         from appose.python_worker import Task
         task: Task

     from appose.python_worker import Task
     task = globals()['task']
+    # Some Appose hosts inject cp_utils' helpers as init globals; others rely on import.
+    # Prefer the injected global, else import, so get_torch_device is always defined.
+    if 'get_torch_device' not in globals():
+        from cp_utils import get_torch_device
 else:
     from cp_utils import get_torch_device
     ...
```

This is a no-op when the host injected `get_torch_device` (the `if` is False), and otherwise
imports it (requires `cp_utils` to be importable, which it already is in the Fiji bundle).

### Optional (lower priority): default `model_name` / `custom_model` in the init scripts

`cp3_init.py` reads `model_name` and `custom_model` as bare globals; `cp4_init.py` reads
`custom_model`. The same `globals().get(..., default)` treatment would make them robust to a caller
that omits them. The Fiji caller always sends them, so this is optional polish, not a bug fix.

## Fiji compatibility

Neither change alters behavior for the `cellpose-appose` Fiji plugin:
- The Fiji caller already supplies `compute_flows` as a task input, so `globals().get(...)` returns
  the same value it does today.
- The Fiji side already makes `cp_utils` helpers available in appose mode, so
  `'get_torch_device' in globals()` is True and the new import is skipped.

Both changes are purely additive guards: they only take effect when a key/global is *missing*,
which never happens under the Fiji plugin's current calling convention.

## How this was found

`qupath-extension-cellAPpose` (a QuPath/Appose consumer) vendored these scripts and hit both
`NameError`s on first run; we worked around them on our side (defaulting the globals and injecting
`cp_utils` via our Appose init script). Upstreaming the two guards would let the next non-Fiji
Appose consumer use the scripts unmodified. We are not requesting the `n_labels` task-output we
added -- that is specific to our round-trip and not generally useful.
