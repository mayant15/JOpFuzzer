# Changes Made to JOpFuzzer

## Source Layout Fix
- Moved sources from `src/` to `src/main/java/` to follow standard Maven layout

## Heuristic Option Selection (`Options/HeuristicOptionSelector.java`)
- **New file** that replaces the learned correlation matrix (`result.csv`) with hard-coded heuristics mapping code features → JIT options
- Maps each `SourceCodeFeature` to weighted `Option` sets based on well-known compiler optimization relationships (e.g., loop features → loop unrolling options, field access → inlining + escape analysis)
- Includes baseline options that are always somewhat relevant regardless of code features
- Uses weighted random selection (same interface as the original `analyzeChangedStructure()`)

## CLI Flag: `-useHeuristics` (`MainEntry.java`)
- Added `-useHeuristics` boolean flag to skip the expensive preprocessing step entirely
- When set, `Prepare.preprocess()` is skipped and `HeuristicOptionSelector` is used instead of the learned matrix
- `-regressionTestPath` is only required when not using heuristics

## DifferentialTest Integration (`Mutation/DT/DifferentialTest.java`)
- Added constructor parameter `useHeuristics` to control which option selection path is used
- When `useHeuristics` is true: skips `readArray()` (no `result.csv` needed), uses `HeuristicOptionSelector.selectOptions()` instead of `analyzeChangedStructure()`
- When false: original behavior with CSV-based learned model

## Bug Fix: `threshold` Calculation (`DifferentialTest.readArray()`)
- **Before**: `threshold` was divided by `index * nextLine.length` inside the while loop on every iteration, making the final value the average of only the last row's running division
- **After**: accumulates sum and count across all non-NaN values, computes a single global average after the loop

## OpenJDK 17 Build Fix for Native Coverage (`jdk17u-jdk-17.0.19-9`)

Building OpenJDK 17 with `--enable-native-coverage` fails because the build shell
uses `bash -o pipefail -e`. The coverage-instrumented `.o` files contain ~1M gcov
symbols, so `nm --defined-only *.o` produces massive output. When `awk` finishes
filtering before `nm` finishes writing, `nm` receives SIGPIPE and exits with code 13.
With `pipefail`, this kills the entire recipe.

**File**: `make/hotspot/lib/JvmMapfile.gmk` (in the JDK source tree at `~/code/jdk17u-jdk-17.0.19-9/`)

```diff
--- a/make/hotspot/lib/JvmMapfile.gmk
+++ b/make/hotspot/lib/JvmMapfile.gmk
@@ -131,7 +131,7 @@
 $(JVM_OUTPUTDIR)/symbols-objects: $(BUILD_LIBJVM_ALL_OBJS)
 	$(call LogInfo, Generating symbol list from object files)
 	$(CD) $(JVM_OUTPUTDIR)/objs && \
-	  $(DUMP_SYMBOLS_CMD) | $(AWK) $(FILTER_SYMBOLS_AWK_SCRIPT) | $(SORT) -u > $@
+	  ( $(DUMP_SYMBOLS_CMD) || true ) | $(AWK) $(FILTER_SYMBOLS_AWK_SCRIPT) | $(SORT) -u > $@
```

**Build command used**:
```bash
cd ~/code/jdk17u-jdk-17.0.19-9
bash configure --enable-native-coverage --with-debug-level=fastdebug \
  --with-boot-jdk=$HOME/.local/share/mise/installs/java/17 \
  --disable-warnings-as-errors
make images JOBS=15
```

## MutationEntry (`Mutation/MutationEntry.java`)
- Added `useHeuristics` field and constructor overload to pass the flag through to `DifferentialTest`

## Experiment Replication Script (`Scripts/run-experiment.sh`)
- **New file** that automates the full paper replication workflow:
  - Builds JOpFuzzer jar + dependencies
  - Optionally runs the learning/preprocessing step (builds `result.csv` from regression tests)
  - Runs the fuzzer in time-bounded batches with periodic lcov coverage snapshots
  - Collects per-component coverage breakdowns (c1, c2/opto, runtime, gc)
  - Supports multiple repetitions (paper uses 3×24h runs)
  - Supports both learned model and heuristic mode via `--use-heuristics`
