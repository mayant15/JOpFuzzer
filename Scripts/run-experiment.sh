#!/bin/bash
#
# run-experiment.sh — Replicate the JOpFuzzer paper's coverage experiments
#
# Usage:
#   ./Scripts/run-experiment.sh [OPTIONS]
#
# Options:
#   --jdk-path PATH           Path to coverage-instrumented JDK (required)
#   --jdk-source PATH         Path to JDK source tree (for gcov/lcov, required)
#   --regression-tests PATH   Path to regression test .java files (for learning step)
#   --seed-count N            Number of JavaFuzzer seeds to generate (default: 100)
#   --duration-hours N        How long to fuzz in hours (default: 24)
#   --mutations-per-batch N   Mutation rounds per batch before coverage snapshot (default: 500)
#   --repetitions N           Number of experiment repetitions (default: 3)
#   --output-dir DIR          Where to store results (default: ./experiment-results)
#   --use-heuristics          Skip learning step, use hard-coded heuristics
#   --skip-learning           Alias for --use-heuristics
#   --learning-only           Only run the learning/preprocessing step, then exit
#
# Example (full paper replication with learning):
#   ./Scripts/run-experiment.sh \
#     --jdk-path ~/code/jdk17u-jdk-17.0.19-9/build/linux-x86_64-server-fastdebug/images/jdk \
#     --jdk-source ~/code/jdk17u-jdk-17.0.19-9 \
#     --regression-tests ~/code/jdk17u-jdk-17.0.19-9/test/hotspot/jtreg/compiler \
#     --seed-count 100 --duration-hours 24 --repetitions 3
#
# Example (heuristic mode, quick test):
#   ./Scripts/run-experiment.sh \
#     --jdk-path ~/code/jdk17u-jdk-17.0.19-9/build/linux-x86_64-server-fastdebug/images/jdk \
#     --jdk-source ~/code/jdk17u-jdk-17.0.19-9 \
#     --use-heuristics --seed-count 10 --duration-hours 1 --repetitions 1
#
set -euo pipefail

# ─── Defaults ───────────────────────────────────────────────────────────────────
JDK_PATH=""
JDK_SOURCE=""
REGRESSION_TESTS=""
SEED_COUNT=100
DURATION_HOURS=24
MUTATIONS_PER_BATCH=500
REPETITIONS=3
OUTPUT_DIR="./experiment-results"
USE_HEURISTICS=false
LEARNING_ONLY=false

# ─── Parse arguments ────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jdk-path)           JDK_PATH="$2"; shift 2 ;;
    --jdk-source)         JDK_SOURCE="$2"; shift 2 ;;
    --regression-tests)   REGRESSION_TESTS="$2"; shift 2 ;;
    --seed-count)         SEED_COUNT="$2"; shift 2 ;;
    --duration-hours)     DURATION_HOURS="$2"; shift 2 ;;
    --mutations-per-batch) MUTATIONS_PER_BATCH="$2"; shift 2 ;;
    --repetitions)        REPETITIONS="$2"; shift 2 ;;
    --output-dir)         OUTPUT_DIR="$2"; shift 2 ;;
    --use-heuristics|--skip-learning) USE_HEURISTICS=true; shift ;;
    --learning-only)      LEARNING_ONLY=true; shift ;;
    -h|--help)
      sed -n '2,34p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

# ─── Validate ────────────────────────────────────────────────────────────────────
if [[ -z "$JDK_PATH" ]]; then
  echo "Error: --jdk-path is required" >&2; exit 1
fi
if [[ -z "$JDK_SOURCE" ]]; then
  echo "Error: --jdk-source is required (for lcov coverage collection)" >&2; exit 1
fi
if [[ "$USE_HEURISTICS" == false && -z "$REGRESSION_TESTS" && "$LEARNING_ONLY" == false ]]; then
  echo "Error: --regression-tests is required when not using --use-heuristics" >&2
  echo "  e.g., --regression-tests ~/code/jdk17u-jdk-17.0.19-9/test/hotspot/jtreg/compiler" >&2
  exit 1
fi
if ! command -v lcov &>/dev/null; then
  echo "Error: lcov not found. Install it first." >&2; exit 1
fi
if ! "$JDK_PATH/bin/java" -version &>/dev/null; then
  echo "Error: $JDK_PATH/bin/java is not executable" >&2; exit 1
fi

# ─── Paths ───────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JOPFUZZER_DIR="$PROJECT_ROOT/JOpFuzzer"
BUILD_DIR="$JDK_SOURCE/build"
# Find the hotspot build directory (for gcov data)
HOTSPOT_BUILD_DIR="$(find "$BUILD_DIR" -maxdepth 2 -type d -name hotspot | head -1)"
if [[ -z "$HOTSPOT_BUILD_DIR" ]]; then
  echo "Error: cannot find hotspot build dir under $BUILD_DIR" >&2; exit 1
fi

DURATION_SECONDS=$((DURATION_HOURS * 3600))
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

echo "════════════════════════════════════════════════════════════════"
echo " JOpFuzzer Coverage Experiment"
echo "════════════════════════════════════════════════════════════════"
echo " JDK path:          $JDK_PATH"
echo " JDK source:        $JDK_SOURCE"
echo " Hotspot build:     $HOTSPOT_BUILD_DIR"
echo " Regression tests:  ${REGRESSION_TESTS:-N/A (heuristic mode)}"
echo " Seed count:        $SEED_COUNT"
echo " Duration:          ${DURATION_HOURS}h (${DURATION_SECONDS}s)"
echo " Mutations/batch:   $MUTATIONS_PER_BATCH"
echo " Repetitions:       $REPETITIONS"
echo " Use heuristics:    $USE_HEURISTICS"
echo " Output dir:        $OUTPUT_DIR"
echo "════════════════════════════════════════════════════════════════"

# ─── Build JOpFuzzer ─────────────────────────────────────────────────────────────
mkdir -p "$OUTPUT_DIR"
echo ""
echo "[*] Building JOpFuzzer..."
cd "$JOPFUZZER_DIR"
mvn package -q -DskipTests 2>&1
mvn dependency:copy-dependencies -q -DoutputDirectory=target/deps 2>&1

CP="target/JOpFuzzer-demo-1.0-SNAPSHOT.jar"
for f in target/deps/*.jar; do CP="$CP:$f"; done
export CP

echo "[*] JOpFuzzer built successfully."

# ─── Learning step (if not using heuristics) ─────────────────────────────────────
if [[ "$USE_HEURISTICS" == false ]]; then
  echo ""
  echo "[*] Running learning/preprocessing step..."
  echo "    Regression tests: $REGRESSION_TESTS"
  echo "    This may take several hours depending on the test suite size."

  cd "$JOPFUZZER_DIR"

  LEARN_START=$(date +%s)
  java -cp "$CP" MainEntry \
    -jdkPath "$JDK_PATH" \
    -regressionTestPath "$REGRESSION_TESTS" \
    -seedNumber "$SEED_COUNT" \
    -mutationRound 0 2>&1 | tee "$OUTPUT_DIR/learning.log" || true
  LEARN_END=$(date +%s)

  echo "[*] Learning step completed in $(( (LEARN_END - LEARN_START) / 60 )) minutes."

  if [[ -f "$JOPFUZZER_DIR/result.csv" ]]; then
    echo "[*] Correlation matrix saved to result.csv"
  else
    echo "[!] Warning: result.csv not found — learning may have failed."
  fi

  if [[ "$LEARNING_ONLY" == true ]]; then
    echo "[*] --learning-only specified. Exiting."
    exit 0
  fi
fi

# ─── Coverage collection helper ──────────────────────────────────────────────────
collect_coverage() {
  local label="$1"
  local out_dir="$2"

  echo "  [*] Collecting coverage: $label"

  local raw_info="$out_dir/${label}-raw.info"
  local hotspot_info="$out_dir/${label}-hotspot.info"
  local summary_file="$out_dir/${label}-summary.txt"

  lcov --capture \
    --directory "$HOTSPOT_BUILD_DIR" \
    --output-file "$raw_info" \
    --ignore-errors source,negative,inconsistent \
    --quiet 2>/dev/null

  lcov --extract "$raw_info" \
    '*/src/hotspot/*' \
    --output-file "$hotspot_info" \
    --ignore-errors unused \
    --quiet 2>/dev/null

  # Overall summary
  lcov --summary "$hotspot_info" --ignore-errors empty 2>&1 | tee "$summary_file"

  # Per-component breakdown
  echo "" >> "$summary_file"
  echo "=== Per-component breakdown ===" >> "$summary_file"
  for component in c1 opto runtime gc; do
    local comp_info="$out_dir/${label}-${component}.info"
    lcov --extract "$hotspot_info" \
      "*/src/hotspot/share/${component}/*" \
      --output-file "$comp_info" \
      --ignore-errors unused \
      --quiet 2>/dev/null
    echo "--- $component ---" >> "$summary_file"
    lcov --summary "$comp_info" --ignore-errors empty 2>&1 >> "$summary_file"
  done

  echo "  [*] Summary written to $summary_file"
}

# ─── Run experiment repetitions ──────────────────────────────────────────────────
for rep in $(seq 1 "$REPETITIONS"); do
  REP_DIR="$OUTPUT_DIR/rep-$rep"
  mkdir -p "$REP_DIR"

  echo ""
  echo "╔══════════════════════════════════════════════════════════════╗"
  echo "  Repetition $rep / $REPETITIONS"
  echo "╚══════════════════════════════════════════════════════════════╝"

  # Clear gcda files from any prior run
  find "$HOTSPOT_BUILD_DIR" -name "*.gcda" -delete 2>/dev/null || true

  cd "$JOPFUZZER_DIR"

  # Clean previous mutants
  rm -rf JavaFuzzer/tests/
  rm -rf Bug/

  START_TIME=$(date +%s)
  ELAPSED=0
  BATCH=0
  TOTAL_MUTATIONS=0

  echo "[*] Fuzzing for ${DURATION_HOURS}h starting at $(date)"

  # Build the JOpFuzzer command
  FUZZER_BASE_ARGS="-jdkPath $JDK_PATH -seedNumber $SEED_COUNT"
  if [[ "$USE_HEURISTICS" == true ]]; then
    FUZZER_BASE_ARGS="$FUZZER_BASE_ARGS -useHeuristics"
  else
    FUZZER_BASE_ARGS="$FUZZER_BASE_ARGS -regressionTestPath $REGRESSION_TESTS"
  fi

  # Run in batches so we can collect periodic coverage snapshots
  # Always run at least one batch (for smoke tests with --duration-hours 0)
  while [[ $ELAPSED -lt $DURATION_SECONDS || $BATCH -eq 0 ]]; do
    BATCH=$((BATCH + 1))
    REMAINING=$((DURATION_SECONDS - ELAPSED))
    if [[ $REMAINING -le 0 ]]; then REMAINING=3600; fi  # fallback for smoke tests

    # Calculate mutations for this batch (might be fewer if time is running out)
    BATCH_MUTATIONS=$MUTATIONS_PER_BATCH

    echo ""
    echo "  [batch $BATCH] Starting $BATCH_MUTATIONS mutations (elapsed: ${ELAPSED}s / ${DURATION_SECONDS}s)"

    # Run the fuzzer for one batch
    # Note: seeds are only generated on the first batch (JavaFuzzer/tests/ persists)
    BATCH_START=$(date +%s)
    timeout "${REMAINING}s" java -cp "$CP" MainEntry \
      $FUZZER_BASE_ARGS \
      -mutationRound "$BATCH_MUTATIONS" \
      2>&1 | tee "$REP_DIR/batch-${BATCH}.log" || true
    BATCH_END=$(date +%s)

    BATCH_DURATION=$((BATCH_END - BATCH_START))
    TOTAL_MUTATIONS=$((TOTAL_MUTATIONS + BATCH_MUTATIONS))
    ELAPSED=$((BATCH_END - START_TIME))

    echo "  [batch $BATCH] Completed in ${BATCH_DURATION}s (total mutations: $TOTAL_MUTATIONS)"

    # Collect coverage snapshot after each batch
    collect_coverage "batch-${BATCH}" "$REP_DIR"

    # Record timing data
    echo "$BATCH,$ELAPSED,$TOTAL_MUTATIONS" >> "$REP_DIR/timing.csv"
  done

  END_TIME=$(date +%s)
  TOTAL_TIME=$((END_TIME - START_TIME))

  echo ""
  echo "[*] Repetition $rep completed in $((TOTAL_TIME / 3600))h $((TOTAL_TIME % 3600 / 60))m"
  echo "    Total mutations: $TOTAL_MUTATIONS"
  echo "    Total batches:   $BATCH"

  # Collect final coverage for this repetition
  collect_coverage "final" "$REP_DIR"

  # Count bugs found
  BUG_COUNT=0
  if [[ -d Bug ]]; then
    BUG_COUNT=$(ls -1 Bug/ 2>/dev/null | wc -l)
    if [[ $BUG_COUNT -gt 0 ]]; then
      cp -r Bug "$REP_DIR/bugs"
    fi
  fi
  echo "    Bugs found:      $BUG_COUNT"

  # Write repetition summary
  cat > "$REP_DIR/summary.txt" <<EOF
Repetition: $rep
Duration: ${TOTAL_TIME}s (${DURATION_HOURS}h target)
Total mutations: $TOTAL_MUTATIONS
Total batches: $BATCH
Bugs found: $BUG_COUNT
JDK: $($JDK_PATH/bin/java -version 2>&1 | head -1)
Mode: $(if $USE_HEURISTICS; then echo "heuristics"; else echo "learned model"; fi)
Seed count: $SEED_COUNT
EOF
done

# ─── Aggregate results across repetitions ────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════════════════════"
echo " Experiment Complete"
echo "════════════════════════════════════════════════════════════════"
echo ""
echo "Results in: $OUTPUT_DIR"
echo ""

# Print final coverage from each repetition
for rep in $(seq 1 "$REPETITIONS"); do
  REP_DIR="$OUTPUT_DIR/rep-$rep"
  echo "--- Repetition $rep ---"
  if [[ -f "$REP_DIR/final-summary.txt" ]]; then
    cat "$REP_DIR/final-summary.txt"
  fi
  echo ""
done

echo "To generate an HTML coverage report from the last repetition:"
echo "  genhtml $OUTPUT_DIR/rep-$REPETITIONS/final-hotspot.info \\"
echo "    --output-directory $OUTPUT_DIR/html-report \\"
echo "    --ignore-errors source"
