package Options;

import Preprocess.SourceCodeFeature;

import java.util.*;

/**
 * Replaces the learned correlation matrix (result.csv) with hard-coded heuristics
 * that map code features to relevant JIT optimization options.
 *
 * The paper's learned model runs O(seeds × methods × 78 options × 2) JVM invocations
 * to discover that e.g. loop unrolling options correlate with loop code features.
 * These relationships are well-known from compiler engineering, so we encode them directly.
 */
public class HeuristicOptionSelector {

    private static final int SELECT_OPTION_NUMBER = 3;

    // Feature → (Option, weight) mappings. Higher weight = more likely to be selected.
    private static final Map<SourceCodeFeature, Map<Option, Double>> FEATURE_OPTION_MAP = new EnumMap<>(SourceCodeFeature.class);

    // Baseline options that are always somewhat relevant regardless of code features
    private static final Map<Option, Double> BASELINE_OPTIONS = new LinkedHashMap<>();

    static {
        // --- Loop optimizations ---
        Map<Option, Double> loopOpts = new LinkedHashMap<>();
        loopOpts.put(Option.LoopMaxUnroll, 3.0);
        loopOpts.put(Option.LoopUnrollLimit, 3.0);
        loopOpts.put(Option.LoopUnrollMin, 2.0);
        loopOpts.put(Option.LoopOptsCount, 2.0);
        loopOpts.put(Option.LoopStripMiningIter, 2.0);
        loopOpts.put(Option.LoopStripMiningIterShortLoop, 1.0);
        loopOpts.put(Option.LoopPercentProfileLimit, 1.0);
        loopOpts.put(Option.MaxLoopPad, 1.0);
        loopOpts.put(Option.NumberOfLoopInstrToAlign, 1.0);
        loopOpts.put(Option.UseLoopPredicate, 2.0);
        loopOpts.put(Option.LoopUnswitching, 2.0);
        loopOpts.put(Option.PartialPeelLoop, 2.0);
        loopOpts.put(Option.PartialPeelNewPhiDelta, 1.0);
        loopOpts.put(Option.BlockLayoutRotateLoops, 1.5);
        loopOpts.put(Option.OptimizeFill, 2.0);
        loopOpts.put(Option.RangeCheckElimination, 2.0);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.loopDepthOne, loopOpts);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.loopDepthTwo, loopOpts);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.loopDepthThreeOrMore, loopOpts);

        // --- Vectorization (loops + arrays) ---
        Map<Option, Double> vectorOpts = new LinkedHashMap<>();
        vectorOpts.put(Option.UseSuperWord, 3.0);
        vectorOpts.put(Option.SuperWordLoopUnrollAnalysis, 3.0);
        vectorOpts.put(Option.AlignVector, 2.0);
        // These will be combined when both loop and array features are present

        // --- Array operations ---
        Map<Option, Double> arrayOpts = new LinkedHashMap<>();
        arrayOpts.put(Option.InlineArrayCopy, 2.0);
        arrayOpts.put(Option.InlineObjectCopy, 2.0);
        arrayOpts.put(Option.ArrayCopyLoadStoreMaxElem, 2.0);
        arrayOpts.put(Option.ArrayOperationPartialInlineSize, 1.5);
        arrayOpts.put(Option.MultiArrayExpandLimit, 1.5);
        arrayOpts.put(Option.RangeCheckElimination, 2.5);
        // Add vectorization options since arrays in loops are prime vectorization targets
        arrayOpts.putAll(vectorOpts);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.arrayDimensionOne, arrayOpts);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.arrayDimensionTwo, arrayOpts);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.arrayDimensionThreeOrMore, arrayOpts);

        // --- Field access → inlining + escape analysis ---
        Map<Option, Double> fieldOpts = new LinkedHashMap<>();
        fieldOpts.put(Option.Inline, 2.0);
        fieldOpts.put(Option.InlineAccessors, 3.0);
        fieldOpts.put(Option.InlineSmallCode, 2.0);
        fieldOpts.put(Option.ClipInlining, 1.5);
        fieldOpts.put(Option.DoEscapeAnalysis, 2.5);
        fieldOpts.put(Option.EliminateAllocations, 2.0);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.fieldAccess, fieldOpts);

        // --- Synchronized access → lock elimination ---
        Map<Option, Double> syncOpts = new LinkedHashMap<>();
        syncOpts.put(Option.EliminateLocks, 3.0);
        syncOpts.put(Option.EliminateNestedLocks, 3.0);
        syncOpts.put(Option.DoEscapeAnalysis, 2.5);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.synchronizedAccess, syncOpts);

        // --- Arithmetic operators → peephole + canonicalization ---
        Map<Option, Double> arithOpts = new LinkedHashMap<>();
        arithOpts.put(Option.OptoPeephole, 2.5);
        arithOpts.put(Option.CanonicalizeNodes, 2.0);
        arithOpts.put(Option.UseGlobalValueNumbering, 2.0);
        arithOpts.put(Option.UseLocalValueNumbering, 2.0);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.arithmeticOperator, arithOpts);

        // --- Shift operators ---
        Map<Option, Double> shiftOpts = new LinkedHashMap<>();
        shiftOpts.put(Option.OptoPeephole, 2.5);
        shiftOpts.put(Option.CanonicalizeNodes, 2.0);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.shiftOperator, shiftOpts);

        // --- Compare operators → comparison optimizations ---
        Map<Option, Double> cmpOpts = new LinkedHashMap<>();
        cmpOpts.put(Option.OptimizePtrCompare, 2.5);
        cmpOpts.put(Option.DoCEE, 2.0);
        cmpOpts.put(Option.EliminateNullChecks, 2.0);
        cmpOpts.put(Option.EliminateBlocks, 1.5);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.compareOperator, cmpOpts);

        // --- Method invocations → inlining ---
        Map<Option, Double> invokeOpts = new LinkedHashMap<>();
        invokeOpts.put(Option.Inline, 3.0);
        invokeOpts.put(Option.InlineSmallCode, 2.0);
        invokeOpts.put(Option.C1MaxInlineSize, 2.0);
        invokeOpts.put(Option.C1MaxInlineLevel, 2.0);
        invokeOpts.put(Option.C1MaxRecursiveInlineLevel, 1.5);
        invokeOpts.put(Option.C1MaxTrivialSize, 1.5);
        invokeOpts.put(Option.C1InlineStackLimit, 1.0);
        invokeOpts.put(Option.ClipInlining, 1.5);
        invokeOpts.put(Option.IncrementalInline, 2.0);
        invokeOpts.put(Option.AlwaysIncrementalInline, 1.5);
        invokeOpts.put(Option.NestedInliningSizeRatio, 1.5);
        invokeOpts.put(Option.NodeCountInliningCutoff, 1.5);
        invokeOpts.put(Option.NodeCountInliningStep, 1.0);
        invokeOpts.put(Option.LiveNodeCountInliningCutoff, 1.0);
        invokeOpts.put(Option.InlineWarmCalls, 1.5);
        invokeOpts.put(Option.TieredCompilation, 2.0);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.invocationStmt, invokeOpts);

        // --- Constructors → allocation elimination ---
        Map<Option, Double> ctorOpts = new LinkedHashMap<>();
        ctorOpts.put(Option.EliminateAllocations, 3.0);
        ctorOpts.put(Option.EliminateAllocationArraySizeLimit, 2.0);
        ctorOpts.put(Option.EliminateAllocationFieldsLimit, 2.0);
        ctorOpts.put(Option.DoEscapeAnalysis, 3.0);
        ctorOpts.put(Option.AggressiveUnboxing, 2.0);
        ctorOpts.put(Option.EliminateAutoBox, 2.0);
        ctorOpts.put(Option.AutoBoxCacheMax, 1.5);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.constructorCallStmt, ctorOpts);

        // --- Exception handling ---
        Map<Option, Double> exnOpts = new LinkedHashMap<>();
        exnOpts.put(Option.InlineMethodsWithExceptionHandlers, 3.0);
        exnOpts.put(Option.EliminateBlocks, 2.0);
        exnOpts.put(Option.SplitIfBlocks, 1.5);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.tryStmt, exnOpts);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.throwStmt, exnOpts);

        // --- If statements → conditional elimination ---
        Map<Option, Double> ifOpts = new LinkedHashMap<>();
        ifOpts.put(Option.DoCEE, 2.5);
        ifOpts.put(Option.EliminateBlocks, 2.0);
        ifOpts.put(Option.SplitIfBlocks, 2.5);
        ifOpts.put(Option.EliminateNullChecks, 2.0);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.ifStmt, ifOpts);

        // --- Switch statements ---
        Map<Option, Double> switchOpts = new LinkedHashMap<>();
        switchOpts.put(Option.EliminateBlocks, 2.0);
        switchOpts.put(Option.SplitIfBlocks, 1.5);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.switchStmt, switchOpts);

        // --- Lambda → method handle inlining ---
        Map<Option, Double> lambdaOpts = new LinkedHashMap<>();
        lambdaOpts.put(Option.IncrementalInline, 3.0);
        lambdaOpts.put(Option.IncrementalInlineMH, 3.0);
        lambdaOpts.put(Option.IncrementalInlineVirtual, 2.5);
        lambdaOpts.put(Option.IncrementalInlineForceCleanup, 1.5);
        lambdaOpts.put(Option.Inline, 2.0);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.lambda, lambdaOpts);

        // --- Assignments → value numbering ---
        Map<Option, Double> assignOpts = new LinkedHashMap<>();
        assignOpts.put(Option.UseGlobalValueNumbering, 2.0);
        assignOpts.put(Option.UseLocalValueNumbering, 2.0);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.assignmentStmt, assignOpts);

        // --- Return statements ---
        Map<Option, Double> retOpts = new LinkedHashMap<>();
        retOpts.put(Option.OptoPeephole, 1.5);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.returnStmt, retOpts);

        // --- Autoboxing types ---
        Map<Option, Double> boxOpts = new LinkedHashMap<>();
        boxOpts.put(Option.EliminateAutoBox, 3.0);
        boxOpts.put(Option.AggressiveUnboxing, 2.5);
        boxOpts.put(Option.AutoBoxCacheMax, 2.0);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.booleanType, boxOpts);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.integerType, boxOpts);

        // --- Instanceof ---
        Map<Option, Double> instanceOpts = new LinkedHashMap<>();
        instanceOpts.put(Option.OptimizePtrCompare, 2.0);
        instanceOpts.put(Option.DoCEE, 1.5);
        FEATURE_OPTION_MAP.put(SourceCodeFeature.instance, instanceOpts);

        // --- String concat optimization (invocations involving strings) ---
        Map<Option, Double> strOpts = new LinkedHashMap<>();
        strOpts.put(Option.OptimizeStringConcat, 3.0);
        // Attached to invocation since string concat desugars to StringBuilder calls
        // (already covered via invocationStmt)

        // --- Unsafe operations ---
        Map<Option, Double> unsafeOpts = new LinkedHashMap<>();
        unsafeOpts.put(Option.OptimizeUnsafes, 3.0);
        unsafeOpts.put(Option.InlineUnsafeOps, 2.5);

        // --- Baseline: always-relevant options ---
        BASELINE_OPTIONS.put(Option.TieredCompilation, 1.0);
        BASELINE_OPTIONS.put(Option.SplitIfBlocks, 0.5);
        BASELINE_OPTIONS.put(Option.UseGlobalValueNumbering, 0.5);
        BASELINE_OPTIONS.put(Option.UseLocalValueNumbering, 0.5);
        BASELINE_OPTIONS.put(Option.DominatorSearchLimit, 0.5);
        BASELINE_OPTIONS.put(Option.OptimizeUnsafes, 0.3);
        BASELINE_OPTIONS.put(Option.OptimizeStringConcat, 0.3);
        BASELINE_OPTIONS.put(Option.InlineNatives, 0.5);
        BASELINE_OPTIONS.put(Option.InlineClassNatives, 0.3);
        BASELINE_OPTIONS.put(Option.InlineMathNatives, 0.3);
        BASELINE_OPTIONS.put(Option.InlineThreadNatives, 0.3);
        BASELINE_OPTIONS.put(Option.InlineNIOCheckIndex, 0.3);
        BASELINE_OPTIONS.put(Option.InlineObjectHash, 0.3);
        BASELINE_OPTIONS.put(Option.InlineReflectionGetCallerClass, 0.3);
        BASELINE_OPTIONS.put(Option.InlineIntrinsics, 0.5);
        BASELINE_OPTIONS.put(Option.InlineSynchronizedMethods, 0.3);
        BASELINE_OPTIONS.put(Option.DebugInlinedCalls, 0.3);
        BASELINE_OPTIONS.put(Option.C1ProfileInlinedCalls, 0.3);
    }

    /**
     * Select options based on code features at the mutation point.
     * Replaces DifferentialTest.analyzeChangedStructure() + readArray().
     *
     * @param changedStructure list of SourceCodeFeature names present at the mutation point
     * @return list of selected Option names, or null if no features given
     */
    public static List<String> selectOptions(List<String> changedStructure) {
        if (changedStructure == null || changedStructure.isEmpty()) {
            return null;
        }

        // Accumulate weights for each option based on all changed features
        Map<Option, Double> optionWeights = new EnumMap<>(Option.class);

        // Add baseline weights for all options
        for (Map.Entry<Option, Double> entry : BASELINE_OPTIONS.entrySet()) {
            optionWeights.merge(entry.getKey(), entry.getValue(), Double::sum);
        }

        // Add feature-specific weights
        for (String featureName : changedStructure) {
            try {
                SourceCodeFeature feature = SourceCodeFeature.valueOf(featureName);
                Map<Option, Double> featureOpts = FEATURE_OPTION_MAP.get(feature);
                if (featureOpts != null) {
                    for (Map.Entry<Option, Double> entry : featureOpts.entrySet()) {
                        optionWeights.merge(entry.getKey(), entry.getValue(), Double::sum);
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Unknown feature name, skip
            }
        }

        // Weighted random selection of SELECT_OPTION_NUMBER options
        List<String> selected = new ArrayList<>();
        Random random = new Random();
        List<Map.Entry<Option, Double>> entries = new ArrayList<>(optionWeights.entrySet());

        for (int i = 0; i < SELECT_OPTION_NUMBER && !entries.isEmpty(); i++) {
            double sum = entries.stream().mapToDouble(Map.Entry::getValue).sum();
            double r = random.nextDouble() * sum;
            int chosenIdx = 0;
            for (int j = 0; j < entries.size(); j++) {
                r -= entries.get(j).getValue();
                if (r <= 0) {
                    chosenIdx = j;
                    break;
                }
            }
            selected.add(entries.get(chosenIdx).getKey().toString());
            entries.remove(chosenIdx); // don't pick the same option twice
        }

        return selected;
    }
}
