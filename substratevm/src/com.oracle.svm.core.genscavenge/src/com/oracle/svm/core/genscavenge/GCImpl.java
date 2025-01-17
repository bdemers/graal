/*
 * Copyright (c) 2013, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.genscavenge;

//Checkstyle: stop

import static com.oracle.svm.core.snippets.KnownIntrinsics.readCallerStackPointer;
import static com.oracle.svm.core.snippets.KnownIntrinsics.readReturnAddress;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;

import javax.management.MBeanNotificationInfo;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;

import org.graalvm.compiler.api.replacements.Fold;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.nativeimage.c.struct.RawField;
import org.graalvm.nativeimage.c.struct.RawStructure;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.hosted.Feature.FeatureAccess;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.MemoryUtil;
import com.oracle.svm.core.MemoryWalker;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.annotate.AlwaysInline;
import com.oracle.svm.core.annotate.NeverInline;
import com.oracle.svm.core.annotate.RestrictHeapAccess;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.c.NonmovableArray;
import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoAccess;
import com.oracle.svm.core.code.CodeInfoQueryResult;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.code.RuntimeCodeInfoAccess;
import com.oracle.svm.core.code.RuntimeCodeInfoMemory;
import com.oracle.svm.core.code.SimpleCodeInfoQueryResult;
import com.oracle.svm.core.deopt.DeoptimizationSupport;
import com.oracle.svm.core.deopt.DeoptimizedFrame;
import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.heap.AllocationFreeList;
import com.oracle.svm.core.heap.AllocationFreeList.PreviouslyRegisteredElementException;
import com.oracle.svm.core.heap.CodeReferenceMapDecoder;
import com.oracle.svm.core.heap.CollectionWatcher;
import com.oracle.svm.core.heap.GC;
import com.oracle.svm.core.heap.GCCause;
import com.oracle.svm.core.heap.NoAllocationVerifier;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.heap.ReferenceHandler;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.jdk.RuntimeSupport;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.os.CommittedMemoryProvider;
import com.oracle.svm.core.snippets.ImplicitExceptions;
import com.oracle.svm.core.stack.JavaStackWalk;
import com.oracle.svm.core.stack.JavaStackWalker;
import com.oracle.svm.core.stack.ThreadStackPrinter;
import com.oracle.svm.core.thread.JavaThreads;
import com.oracle.svm.core.thread.JavaVMOperation;
import com.oracle.svm.core.thread.NativeVMOperation;
import com.oracle.svm.core.thread.NativeVMOperationData;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.core.util.TimeUtils;
import com.oracle.svm.core.util.VMError;
import com.sun.management.GcInfo;

import sun.management.Util;

//Checkstyle: resume

/**
 * Most of the GC state is preallocated at image build time.
 */
public class GCImpl implements GC {
    static final class Options {
        @Option(help = "How much history to maintain about garbage collections.")//
        public static final HostedOptionKey<Integer> GCHistory = new HostedOptionKey<>(1);
    }

    private static final int DECIMALS_IN_TIME_PRINTING = 7;

    private final RememberedSetConstructor rememberedSetConstructor;
    private final GreyToBlackObjRefVisitor greyToBlackObjRefVisitor;
    private final GreyToBlackObjectVisitor greyToBlackObjectVisitor;
    private final CollectionPolicy collectOnlyCompletelyPolicy;

    private final Accounting accounting;
    private final CollectionVMOperation collectOperation;

    private final OutOfMemoryError oldGenerationSizeExceeded;

    private final AllocationFreeList<CollectionWatcher> collectionWatcherList;
    private final NoAllocationVerifier noAllocationVerifier;

    private final GarbageCollectorManagementFactory gcManagementFactory;

    private final ThreadLocalMTWalker threadLocalsWalker;
    private final RuntimeCodeCacheWalker runtimeCodeCacheWalker;
    private final RuntimeCodeCacheCleaner runtimeCodeCacheCleaner;

    private CollectionPolicy policy;
    private boolean completeCollection;
    private UnsignedWord sizeBefore;

    @Platforms(Platform.HOSTED_ONLY.class)
    protected GCImpl(FeatureAccess access) {
        this.rememberedSetConstructor = new RememberedSetConstructor();
        this.accounting = Accounting.factory();
        this.collectOperation = new CollectionVMOperation();

        this.collectionEpoch = WordFactory.zero();
        this.collectionWatcherList = AllocationFreeList.factory();
        this.noAllocationVerifier = NoAllocationVerifier.factory("GCImpl.GCImpl()", false);
        this.completeCollection = false;
        this.sizeBefore = WordFactory.zero();

        this.policy = CollectionPolicy.getInitialPolicy(access);
        this.greyToBlackObjRefVisitor = new GreyToBlackObjRefVisitor();
        this.greyToBlackObjectVisitor = new GreyToBlackObjectVisitor(greyToBlackObjRefVisitor);
        this.collectOnlyCompletelyPolicy = new CollectionPolicy.OnlyCompletely();
        this.collectionInProgress = Latch.factory("Collection in progress");
        this.oldGenerationSizeExceeded = new OutOfMemoryError("Garbage-collected heap size exceeded.");
        this.gcManagementFactory = new GarbageCollectorManagementFactory();

        this.threadLocalsWalker = createThreadLocalsWalker();
        this.runtimeCodeCacheWalker = new RuntimeCodeCacheWalker(greyToBlackObjRefVisitor);
        this.runtimeCodeCacheCleaner = new RuntimeCodeCacheCleaner();

        this.blackenImageHeapRootsTimer = new Timer("blackenImageHeapRootsTimer");
        this.blackenDirtyCardRootsTimer = new Timer("blackenDirtyCardRoots");
        this.blackenStackRootsTimer = new Timer("blackenStackRoots");
        this.cheneyScanFromRootsTimer = new Timer("cheneyScanFromRoots");
        this.cheneyScanFromDirtyRootsTimer = new Timer("cheneyScanFromDirtyRoots");
        this.collectionTimer = new Timer("collection");
        this.referenceObjectsTimer = new Timer("referenceObjects");
        this.releaseSpacesTimer = new Timer("releaseSpaces");
        this.promotePinnedObjectsTimer = new Timer("promotePinnedObjects");
        this.rootScanTimer = new Timer("rootScan");
        this.scanGreyObjectsTimer = new Timer("scanGreyObject");
        this.verifyAfterTimer = new Timer("verifyAfter");
        this.verifyBeforeTimer = new Timer("verifyBefore");
        this.watchersBeforeTimer = new Timer("watchersBefore");
        this.watchersAfterTimer = new Timer("watchersAfter");
        this.mutatorTimer = new Timer("Mutator");
        this.walkThreadLocalsTimer = new Timer("walkThreadLocals");
        this.walkRuntimeCodeCacheTimer = new Timer("walkRuntimeCodeCacheTimer");
        this.cleanRuntimeCodeCacheTimer = new Timer("cleanRuntimeCodeCacheTimer");

        RuntimeSupport.getRuntimeSupport().addShutdownHook(this::printGCSummary);
    }

    private static ThreadLocalMTWalker createThreadLocalsWalker() {
        if (SubstrateOptions.MultiThreaded.getValue()) {
            return new ThreadLocalMTWalker();
        } else {
            return null;
        }
    }

    @Override
    public void collect(GCCause cause) {
        final UnsignedWord requestingEpoch = possibleCollectionPrologue();
        /* Collect without allocating. */
        collectWithoutAllocating(cause);
        /* Do anything necessary now that allocation, etc., is allowed. */
        possibleCollectionEpilogue(requestingEpoch);
    }

    @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate in the implementation of garbage collection.")
    void collectWithoutAllocating(GCCause cause) {
        int size = SizeOf.get(CollectionVMOperationData.class);
        CollectionVMOperationData data = StackValue.get(size);
        MemoryUtil.fillToMemoryAtomic((Pointer) data, WordFactory.unsigned(size), (byte) 0);
        data.setNativeVMOperation(collectOperation);
        data.setCauseId(cause.getId());
        data.setRequestingEpoch(getCollectionEpoch());
        collectOperation.enqueue(data);

        if (data.getOutOfMemory()) {
            throw oldGenerationSizeExceeded;
        }
    }

    /** The body of the VMOperation to do the collection. */
    private boolean collectOperation(GCCause cause, UnsignedWord requestingEpoch) {
        final Log trace = Log.noopLog().string("[GCImpl.collectOperation:").newline()
                        .string("  epoch: ").unsigned(getCollectionEpoch())
                        .string("  cause: ").string(cause.getName())
                        .string("  requestingEpoch: ").unsigned(requestingEpoch)
                        .newline();
        assert VMOperation.isGCInProgress() : "Collection should be a VMOperation.";
        assert getCollectionEpoch().equal(requestingEpoch);

        /* Stop the mutator timer. */
        mutatorTimer.close();

        /* Note that a collection is in progress, or exit if one is already in progress. */
        startCollectionOrExit();
        /* Reset things for this collection. */
        resetTimers();
        incrementCollectionEpoch();

        /* Flush chunks from thread-local lists to global lists. */
        ThreadLocalAllocation.disableThreadLocalAllocation();
        /* Report the heap before the collection. */
        printGCBefore(cause.getName());
        /* Scrub the lists I maintain, before the collection. */
        scrubLists();
        /* Run any collection watchers before the collection. */
        visitWatchersBefore();

        /* Collect. */
        boolean outOfMemory = collectImpl(cause.getName());

        /* Run any collection watchers after the collection. */
        visitWatchersAfter();
        /* Reset for the next collection. */
        HeapPolicy.youngUsedBytes.set(getAccounting().getYoungChunkBytesAfter());
        /* Print the heap after the collection. */
        printGCAfter(cause.getName());
        /* Note that the collection is finished. */
        finishCollection();

        /* Start the mutator timer. */
        mutatorTimer.open();

        trace.string("]").newline();
        return outOfMemory;
    }

    @SuppressWarnings("try")
    private boolean collectImpl(String cause) {
        final Log trace = Log.noopLog().string("[GCImpl.collectImpl:").newline().string("  epoch: ").unsigned(getCollectionEpoch()).string("  cause: ").string(cause).newline();
        boolean outOfMemory;

        precondition();

        /*
         * Disable young generation allocations *inside* the collector, and detect any that slip in.
         */
        trace.string("  Begin collection: ");
        try (NoAllocationVerifier nav = noAllocationVerifier.open()) {

            trace.string("  Verify before: ");
            try (Timer vbt = verifyBeforeTimer.open()) {
                HeapImpl.getHeapImpl().verifyBeforeGC(cause, getCollectionEpoch());
            }

            outOfMemory = doCollectImpl(getPolicy());

            if (outOfMemory) {
                // Avoid running out of memory with a full GC that reclaims softly reachable objects
                ReferenceObjectProcessing.setSoftReferencesAreWeak(true);
                try {
                    outOfMemory = doCollectImpl(collectOnlyCompletelyPolicy);
                } finally {
                    ReferenceObjectProcessing.setSoftReferencesAreWeak(false);
                }
            }
        }

        trace.string("  Verify after: ");
        try (Timer vat = verifyAfterTimer.open()) {
            HeapImpl.getHeapImpl().verifyAfterGC(cause, getCollectionEpoch());
        }

        postcondition();

        trace.string("]").newline();
        return outOfMemory;
    }

    @SuppressWarnings("try")
    private boolean doCollectImpl(CollectionPolicy appliedPolicy) {
        CommittedMemoryProvider.get().beforeGarbageCollection();

        getAccounting().beforeCollection();

        try (Timer ct = collectionTimer.open()) {
            if (appliedPolicy.collectIncrementally()) {
                scavenge(true);
            }
            completeCollection = appliedPolicy.collectCompletely();
            if (completeCollection) {
                scavenge(false);
            }
        }
        CommittedMemoryProvider.get().afterGarbageCollection(completeCollection);

        getAccounting().afterCollection(completeCollection, collectionTimer);
        UnsignedWord maxBytes = HeapPolicy.getMaximumHeapSize();
        UnsignedWord usedBytes = getChunkUsedBytesAfterCollection();
        boolean outOfMemory = usedBytes.aboveThan(maxBytes);

        ReferenceObjectProcessing.afterCollection(usedBytes, maxBytes);

        return outOfMemory;
    }

    /*
     * Implementation methods.
     */

    private void printGCBefore(String cause) {
        final Log verboseGCLog = Log.log();
        final HeapImpl heap = HeapImpl.getHeapImpl();
        sizeBefore = ((SubstrateOptions.PrintGC.getValue() || HeapOptions.PrintHeapShape.getValue()) ? heap.getUsedChunkBytes() : WordFactory.zero());
        if (SubstrateOptions.VerboseGC.getValue() && getCollectionEpoch().equal(1)) {
            /* Print the command line options that shape the heap. */
            verboseGCLog.string("[Heap policy parameters: ").newline();
            verboseGCLog.string("  YoungGenerationSize: ").unsigned(HeapPolicy.getMaximumYoungGenerationSize()).newline();
            verboseGCLog.string("      MaximumHeapSize: ").unsigned(HeapPolicy.getMaximumHeapSize()).newline();
            verboseGCLog.string("      MinimumHeapSize: ").unsigned(HeapPolicy.getMinimumHeapSize()).newline();
            verboseGCLog.string("     AlignedChunkSize: ").unsigned(HeapPolicy.getAlignedHeapChunkSize()).newline();
            verboseGCLog.string("  LargeArrayThreshold: ").unsigned(HeapPolicy.getLargeArrayThreshold()).string("]").newline();
            if (HeapOptions.PrintHeapShape.getValue()) {
                HeapImpl.getHeapImpl().logImageHeapPartitionBoundaries(verboseGCLog).newline();
            }
        }

        if (SubstrateOptions.VerboseGC.getValue()) {
            verboseGCLog.string("[");
            verboseGCLog.string("[");
            final long startTime = System.nanoTime();
            if (HeapOptions.PrintGCTimeStamps.getValue()) {
                verboseGCLog.unsigned(TimeUtils.roundNanosToMillis(Timer.getTimeSinceFirstAllocation(startTime))).string(" msec: ");
            } else {
                verboseGCLog.unsigned(startTime);
            }
            verboseGCLog.string(" GC:").string(" before").string("  epoch: ").unsigned(getCollectionEpoch()).string("  cause: ").string(cause);
            if (HeapOptions.PrintHeapShape.getValue()) {
                heap.report(verboseGCLog);
            }
            verboseGCLog.string("]").newline();
        }
    }

    private void printGCAfter(String cause) {
        final Log verboseGCLog = Log.log();
        final HeapImpl heap = HeapImpl.getHeapImpl();
        if (SubstrateOptions.PrintGC.getValue() || SubstrateOptions.VerboseGC.getValue()) {
            if (SubstrateOptions.PrintGC.getValue()) {
                final Log printGCLog = Log.log();
                final UnsignedWord sizeAfter = heap.getUsedChunkBytes();
                printGCLog.string("[");
                if (HeapOptions.PrintGCTimeStamps.getValue()) {
                    final long finishNanos = collectionTimer.getFinish();
                    printGCLog.unsigned(TimeUtils.roundNanosToMillis(Timer.getTimeSinceFirstAllocation(finishNanos))).string(" msec: ");
                }
                printGCLog.string(completeCollection ? "Full GC" : "Incremental GC");
                printGCLog.string(" (").string(cause).string(") ");
                printGCLog.unsigned(sizeBefore.unsignedDivide(1024));
                printGCLog.string("K->");
                printGCLog.unsigned(sizeAfter.unsignedDivide(1024)).string("K, ");
                printGCLog.rational(collectionTimer.getCollectedNanos(), TimeUtils.nanosPerSecond, DECIMALS_IN_TIME_PRINTING).string(" secs");

                printGCLog.string("]").newline();
            }
            if (SubstrateOptions.VerboseGC.getValue()) {
                verboseGCLog.string(" [");
                final long finishNanos = collectionTimer.getFinish();
                if (HeapOptions.PrintGCTimeStamps.getValue()) {
                    verboseGCLog.unsigned(TimeUtils.roundNanosToMillis(Timer.getTimeSinceFirstAllocation(finishNanos))).string(" msec: ");
                } else {
                    verboseGCLog.unsigned(finishNanos);
                }
                verboseGCLog.string(" GC:").string(" after ").string("  epoch: ").unsigned(getCollectionEpoch()).string("  cause: ").string(cause);
                verboseGCLog.string("  policy: ");
                getPolicy().nameToLog(verboseGCLog);
                verboseGCLog.string("  type: ").string(completeCollection ? "complete" : "incremental");
                if (HeapOptions.PrintHeapShape.getValue()) {
                    heap.report(verboseGCLog);
                }
                if (!HeapOptions.PrintGCTimes.getValue()) {
                    verboseGCLog.newline();
                    verboseGCLog.string("  collection time: ").unsigned(collectionTimer.getCollectedNanos()).string(" nanoSeconds");
                } else {
                    logGCTimers(verboseGCLog);
                }
                verboseGCLog.string("]");
                verboseGCLog.string("]").newline();
            }
        }
    }

    private static void precondition() {
        /* Pre-condition checks things that heap verification can not check. */
        final HeapImpl heap = HeapImpl.getHeapImpl();
        final OldGeneration oldGen = heap.getOldGeneration();
        assert oldGen.getToSpace().isEmpty() : "oldGen.getToSpace() should be empty before a collection.";
    }

    private void postcondition() {
        /* Post-condition checks things that heap verification can not check. */
        final HeapImpl heap = HeapImpl.getHeapImpl();
        final YoungGeneration youngGen = heap.getYoungGeneration();
        final OldGeneration oldGen = heap.getOldGeneration();
        verbosePostCondition();
        assert youngGen.getEden().isEmpty() : "youngGen.getEden() should be empty after a collection.";
        assert oldGen.getToSpace().isEmpty() : "oldGen.getToSpace() should be empty after a collection.";
    }

    private void verbosePostCondition() {
        final HeapImpl heap = HeapImpl.getHeapImpl();
        final YoungGeneration youngGen = heap.getYoungGeneration();
        final OldGeneration oldGen = heap.getOldGeneration();
        /*
         * Note to self: I can get output similar to this *all the time* by running with
         * -R:+VerboseGC -R:+PrintHeapShape -R:+TraceHeapChunks
         */
        final boolean forceForTesting = false;
        if (runtimeAssertions() || forceForTesting) {
            final Log witness = Log.log();
            if ((!youngGen.getEden().isEmpty()) || forceForTesting) {
                witness.string("[GCImpl.postcondition: Eden space should be empty after a collection.").newline();
                /* Print raw fields before trying to walk the chunk lists. */
                witness.string("  These should all be 0:").newline();
                witness.string("    Eden space first AlignedChunk:   ").hex(youngGen.getEden().getFirstAlignedHeapChunk()).newline();
                witness.string("    Eden space last  AlignedChunk:   ").hex(youngGen.getEden().getLastAlignedHeapChunk()).newline();
                witness.string("    Eden space first UnalignedChunk: ").hex(youngGen.getEden().getFirstUnalignedHeapChunk()).newline();
                witness.string("    Eden space last  UnalignedChunk: ").hex(youngGen.getEden().getLastUnalignedHeapChunk()).newline();
                youngGen.getEden().report(witness, true).newline();
                witness.string("  verifying the heap:");
                heap.verifyAfterGC("because Eden space is not empty", getCollectionEpoch());
                witness.string("]").newline();
            }
            for (int i = 0; i < HeapPolicy.getMaxSurvivorSpaces(); i++) {
                if ((!youngGen.getSurvivorToSpaceAt(i).isEmpty()) || forceForTesting) {
                    witness.string("[GCImpl.postcondition: Survivor toSpace should be empty after a collection.").newline();
                    /* Print raw fields before trying to walk the chunk lists. */
                    witness.string("  These should all be 0:").newline();
                    witness.string("    Survivor space ").signed(i).string(" first AlignedChunk:   ").hex(youngGen.getSurvivorToSpaceAt(i).getFirstAlignedHeapChunk()).newline();
                    witness.string("    Survivor space ").signed(i).string(" last  AlignedChunk:   ").hex(youngGen.getSurvivorToSpaceAt(i).getLastAlignedHeapChunk()).newline();
                    witness.string("    Survivor space ").signed(i).string(" first UnalignedChunk: ").hex(youngGen.getSurvivorToSpaceAt(i).getFirstUnalignedHeapChunk()).newline();
                    witness.string("    Survivor space ").signed(i).string(" last  UnalignedChunk: ").hex(youngGen.getSurvivorToSpaceAt(i).getLastUnalignedHeapChunk()).newline();
                    youngGen.getSurvivorToSpaceAt(i).report(witness, true).newline();
                    witness.string("  verifying the heap:");
                    heap.verifyAfterGC("because Survivor toSpace is not empty", getCollectionEpoch());
                    witness.string("]").newline();
                }
            }
            if ((!oldGen.getToSpace().isEmpty()) || forceForTesting) {
                witness.string("[GCImpl.postcondition: oldGen toSpace should be empty after a collection.").newline();
                /* Print raw fields before trying to walk the chunk lists. */
                witness.string("  These should all be 0:").newline();
                witness.string("    oldGen toSpace first AlignedChunk:   ").hex(oldGen.getToSpace().getFirstAlignedHeapChunk()).newline();
                witness.string("    oldGen toSpace last  AlignedChunk:   ").hex(oldGen.getToSpace().getLastAlignedHeapChunk()).newline();
                witness.string("    oldGen.toSpace first UnalignedChunk: ").hex(oldGen.getToSpace().getFirstUnalignedHeapChunk()).newline();
                witness.string("    oldGen.toSpace last  UnalignedChunk: ").hex(oldGen.getToSpace().getLastUnalignedHeapChunk()).newline();
                oldGen.getToSpace().report(witness, true).newline();
                oldGen.getFromSpace().report(witness, true).newline();
                witness.string("  verifying the heap:");
                heap.verifyAfterGC("because oldGen toSpace is not empty", getCollectionEpoch());
                witness.string("]").newline();
            }
        }
    }

    private UnsignedWord getChunkUsedBytesAfterCollection() {
        /* The old generation and the survivor spaces have objects */
        UnsignedWord survivorUsedBytes = HeapImpl.getHeapImpl().getYoungGeneration().getSurvivorChunkUsedBytes();
        return getAccounting().getOldGenerationAfterChunkBytes().add(survivorUsedBytes);
    }

    @Fold
    static boolean runtimeAssertions() {
        return SubstrateOptions.getRuntimeAssertionsForClass(GCImpl.class.getName());
    }

    @Fold
    public static GCImpl getGCImpl() {
        final GCImpl gcImpl = HeapImpl.getHeapImpl().getGCImpl();
        assert gcImpl != null;
        return gcImpl;
    }

    @Override
    public void collectCompletely(final GCCause cause) {
        final CollectionPolicy oldPolicy = getPolicy();
        try {
            setPolicy(collectOnlyCompletelyPolicy);
            collect(cause);
        } finally {
            setPolicy(oldPolicy);
        }
    }

    boolean isCompleteCollection() {
        return completeCollection;
    }

    /**
     * Scavenge, either just from dirty roots or from all roots.
     *
     * Process discovered references while scavenging.
     */
    @SuppressWarnings("try")
    private void scavenge(boolean fromDirtyRoots) {
        try (GreyToBlackObjRefVisitor.Counters gtborv = greyToBlackObjRefVisitor.openCounters()) {
            final Log trace = Log.noopLog().string("[GCImpl.scavenge:").string("  fromDirtyRoots: ").bool(fromDirtyRoots).newline();

            try (Timer rst = rootScanTimer.open()) {
                trace.string("  Cheney scan: ");
                if (fromDirtyRoots) {
                    cheneyScanFromDirtyRoots();
                } else {
                    cheneyScanFromRoots();
                }
            }

            trace.string("  Discovered references: ");
            try (Timer drt = referenceObjectsTimer.open()) {
                Reference<?> newlyPendingList = ReferenceObjectProcessing.processRememberedReferences();
                HeapImpl.getHeapImpl().addToReferencePendingList(newlyPendingList);
            }

            trace.string("  Release spaces: ");
            /* Release any memory in the young and from Spaces. */
            try (Timer rst = releaseSpacesTimer.open()) {
                releaseSpaces();
            }

            trace.string("  Swap spaces: ");
            /* Exchange the from and to Spaces. */
            swapSpaces();

            trace.string("]").newline();
        }
    }

    /**
     * Visit all the memory that is reserved for runtime compiled code. References from the runtime
     * compiled code to the Java heap must be consider as either strong or weak references,
     * depending on whether the code is currently on the execution stack.
     */
    @SuppressWarnings("try")
    private void walkRuntimeCodeCache() {
        try (Timer wrm = walkRuntimeCodeCacheTimer.open()) {
            RuntimeCodeInfoMemory.singleton().walkRuntimeMethods(runtimeCodeCacheWalker);
        }
    }

    @SuppressWarnings("try")
    private void cleanRuntimeCodeCache() {
        try (Timer wrm = cleanRuntimeCodeCacheTimer.open()) {
            RuntimeCodeInfoMemory.singleton().walkRuntimeMethods(runtimeCodeCacheCleaner);
        }
    }

    @SuppressWarnings("try")
    private void cheneyScanFromRoots() {
        final Log trace = Log.noopLog().string("[GCImpl.cheneyScanFromRoots:").newline();

        try (Timer csfrt = cheneyScanFromRootsTimer.open()) {
            /* Take a snapshot of the heap so that I can visit all the promoted Objects. */
            /*
             * Debugging tip: I could move the taking of the snapshot and the scanning of grey
             * Objects into each of the blackening methods, or even put them around individual
             * Object reference visits.
             */
            prepareForPromotion(false);

            /*
             * Make sure all chunks with pinned objects are in toSpace, and any formerly pinned
             * objects are in fromSpace.
             */
            promoteIndividualPinnedObjects();

            /*
             * Stack references are grey at the beginning of a collection, so I need to blacken
             * them.
             */
            blackenStackRoots();

            /* Custom memory regions which contain object references. */
            walkThreadLocals();

            /*
             * Native image Objects are grey at the beginning of a collection, so I need to blacken
             * them.
             */
            blackenImageHeapRoots();

            /* Visit all the Objects promoted since the snapshot. */
            scanGreyObjects(false);

            if (DeoptimizationSupport.enabled()) {
                /* Visit the runtime compiled code, now that we know all the reachable objects. */
                walkRuntimeCodeCache();

                /* Visit all objects that became reachable because of the compiled code. */
                scanGreyObjects(false);

                /* Clean the code cache, now that all live objects were visited. */
                cleanRuntimeCodeCache();
            }

            greyToBlackObjectVisitor.reset();
        }

        trace.string("]").newline();
    }

    @SuppressWarnings("try")
    private void cheneyScanFromDirtyRoots() {
        final Log trace = Log.noopLog().string("[GCImpl.cheneyScanFromDirtyRoots:").newline();

        final HeapImpl heap = HeapImpl.getHeapImpl();
        final OldGeneration oldGen = heap.getOldGeneration();

        try (Timer csfdrt = cheneyScanFromDirtyRootsTimer.open()) {
            /*
             * Move all the chunks in fromSpace to toSpace. That does not make those chunks grey, so
             * I have to use the dirty cards marks to blacken them, but that's what card marks are
             * for.
             */
            oldGen.emptyFromSpaceIntoToSpace();

            /* Take a snapshot of the heap so that I can visit all the promoted Objects. */
            /*
             * Debugging tip: I could move the taking of the snapshot and the scanning of grey
             * Objects into each of the blackening methods, or even put them around individual
             * Object reference visits.
             */
            prepareForPromotion(true);

            /*
             * Make sure any released objects are in toSpace (because this is an incremental
             * collection). I do this before blackening any roots to make sure the chunks with
             * pinned objects are moved entirely, as opposed to promoting the objects individually
             * by roots. This makes the objects in those chunks grey.
             */
            promoteIndividualPinnedObjects();

            /*
             * Blacken Objects that are dirty roots. There are dirty cards in ToSpace. Do this early
             * so I don't have to walk the cards of individually promoted objects, which will be
             * visited by the grey object scanner.
             */
            blackenDirtyCardRoots();

            /*
             * Stack references are grey at the beginning of a collection, so I need to blacken
             * them.
             */
            blackenStackRoots();

            /* Custom memory regions which contain object references. */
            walkThreadLocals();

            /*
             * Native image Objects are grey at the beginning of a collection, so I need to blacken
             * them.
             */
            blackenImageHeapRoots();

            /* Visit all the Objects promoted since the snapshot, transitively. */
            scanGreyObjects(true);

            if (DeoptimizationSupport.enabled()) {
                /* Visit the runtime compiled code, now that we know all the reachable objects. */
                walkRuntimeCodeCache();

                /* Visit all objects that became reachable because of the compiled code. */
                scanGreyObjects(true);

                /* Clean the code cache, now that all live objects were visited. */
                cleanRuntimeCodeCache();
            }

            greyToBlackObjectVisitor.reset();
        }

        trace.string("]").newline();
    }

    @SuppressWarnings("try")
    private void promoteIndividualPinnedObjects() {
        final Log trace = Log.noopLog().string("[GCImpl.promoteIndividualPinnedObjects:").newline();
        try (Timer ppot = promotePinnedObjectsTimer.open()) {
            /* Capture the PinnedObject list and start a new one. */
            final PinnedObjectImpl oldList = PinnedObjectImpl.claimPinnedObjectList();
            /* Walk the list, dealing with the open PinnedObjects. */
            PinnedObjectImpl rest = oldList;
            while (rest != null) {
                final PinnedObjectImpl first = rest;
                final PinnedObjectImpl next = first.getNext();
                if (first.isOpen()) {
                    /*
                     * Promote the chunk with the object, and put this PinnedObject on the new list.
                     */
                    promotePinnedObject(first);
                    /* Pushing onto the new list reverses the order of the list. */
                    PinnedObjectImpl.pushPinnedObject(first);
                }
                rest = next;
            }
        }
        trace.string("]").newline();
    }

    @NeverInline("Starting a stack walk in the caller frame. " +
                    "Note that we could start the stack frame also further down the stack, because GC stack frames must not access any objects that are processed by the GC. " +
                    "But we don't store stack frame information for the first frame we would need to process.")
    @Uninterruptible(reason = "Required by called JavaStackWalker methods. We are at a safepoint during GC, so it does not change anything for this method.", calleeMustBe = false)
    @SuppressWarnings("try")
    private void blackenStackRoots() {
        final Log trace = Log.noopLog().string("[GCImpl.blackenStackRoots:").newline();
        try (Timer bsr = blackenStackRootsTimer.open()) {
            Pointer sp = readCallerStackPointer();
            trace.string("[blackenStackRoots:").string("  sp: ").hex(sp);
            CodePointer ip = readReturnAddress();
            trace.string("  ip: ").hex(ip).newline();

            JavaStackWalk walk = StackValue.get(JavaStackWalk.class);
            JavaStackWalker.initWalk(walk, sp, ip);
            walkStack(walk);

            if (SubstrateOptions.MultiThreaded.getValue()) {
                /*
                 * Scan the stacks of all the threads. Other threads will be blocked at a safepoint
                 * (or in native code) so they will each have a JavaFrameAnchor in their VMThread.
                 */
                for (IsolateThread vmThread = VMThreads.firstThread(); vmThread.isNonNull(); vmThread = VMThreads.nextThread(vmThread)) {
                    if (vmThread == CurrentIsolate.getCurrentThread()) {
                        /*
                         * The current thread is already scanned by code above, so we do not have to
                         * do anything for it here. It might have a JavaFrameAnchor from earlier
                         * Java-to-C transitions, but certainly not at the top of the stack since it
                         * is running this code, so just this scan would be incomplete.
                         */
                        continue;
                    }
                    if (JavaStackWalker.initWalk(walk, vmThread)) {
                        walkStack(walk);
                    }
                    trace.newline();
                }
            }
            trace.string("]").newline();
        }
        trace.string("]").newline();
    }

    /**
     * This method inlines {@link JavaStackWalker#continueWalk(JavaStackWalk, CodeInfo)} and
     * {@link CodeInfoTable#visitObjectReferences}. This avoids looking up the
     * {@link SimpleCodeInfoQueryResult} twice per frame, and also ensures that there are no virtual
     * calls to a stack frame visitor.
     */
    @Uninterruptible(reason = "Required by called JavaStackWalker methods. We are at a safepoint during GC, so it does not change anything for this method.", calleeMustBe = false)
    private void walkStack(JavaStackWalk walk) {
        assert VMOperation.isGCInProgress() : "This methods accesses a CodeInfo without a tether";

        while (true) {
            SimpleCodeInfoQueryResult queryResult = StackValue.get(SimpleCodeInfoQueryResult.class);
            Pointer sp = walk.getSP();
            CodePointer ip = walk.getPossiblyStaleIP();

            /* We are during a GC, so tethering of the CodeInfo is not necessary. */
            CodeInfo codeInfo = CodeInfoAccess.convert(walk.getIPCodeInfo());
            DeoptimizedFrame deoptFrame = Deoptimizer.checkDeoptimized(sp);
            if (deoptFrame == null) {
                if (codeInfo.isNull()) {
                    throw JavaStackWalker.reportUnknownFrameEncountered(sp, ip, deoptFrame);
                }

                CodeInfoAccess.lookupCodeInfo(codeInfo, CodeInfoAccess.relativeIP(codeInfo, ip), queryResult);
                assert Deoptimizer.checkDeoptimized(sp) == null : "We are at a safepoint, so no deoptimization can have happened even though looking up the code info is not uninterruptible";

                NonmovableArray<Byte> referenceMapEncoding = CodeInfoAccess.getReferenceMapEncoding(codeInfo);
                long referenceMapIndex = queryResult.getReferenceMapIndex();
                if (referenceMapIndex == CodeInfoQueryResult.NO_REFERENCE_MAP) {
                    throw CodeInfoTable.reportNoReferenceMap(sp, ip, codeInfo);
                }
                CodeReferenceMapDecoder.walkOffsetsFromPointer(sp, referenceMapEncoding, referenceMapIndex, greyToBlackObjRefVisitor);
            } else {
                /*
                 * This is a deoptimized frame. The DeoptimizedFrame object is stored in the frame,
                 * but it is pinned so we do not need to visit references of the frame.
                 */
            }

            if (DeoptimizationSupport.enabled() && codeInfo != CodeInfoTable.getImageCodeInfo()) {
                /*
                 * For runtime-compiled code that is currently on the stack, we need to treat all
                 * the references to Java heap objects as strong references. It is important that we
                 * really walk *all* those references here. Otherwise, RuntimeCodeCacheWalker might
                 * decide to invalidate too much code, depending on the order in which the CodeInfo
                 * objects are visited.
                 */
                RuntimeCodeInfoAccess.walkStrongReferences(codeInfo, greyToBlackObjRefVisitor);
                RuntimeCodeInfoAccess.walkWeakReferences(codeInfo, greyToBlackObjRefVisitor);
            }

            if (!JavaStackWalker.continueWalk(walk, queryResult, deoptFrame)) {
                /* No more caller frame found. */
                return;
            }
        }
    }

    @SuppressWarnings("try")
    private void walkThreadLocals() {
        final Log trace = Log.noopLog().string("[walkRegisteredObjectReferences").string(":").newline();
        if (threadLocalsWalker != null) {
            try (Timer wrm = walkThreadLocalsTimer.open()) {
                trace.string("[ThreadLocalsWalker:").newline();
                threadLocalsWalker.walk(greyToBlackObjRefVisitor);
                trace.string("]").newline();
            }
        }
        trace.string("]").newline();
    }

    private void blackenImageHeapRoots() {
        Log trace = Log.noopLog().string("[blackenImageHeapRoots:").newline();
        HeapImpl.getHeapImpl().walkNativeImageHeapRegions(blackenImageHeapRootsVisitor);
        trace.string("]").newline();
    }

    private final BlackenImageHeapRootsVisitor blackenImageHeapRootsVisitor = new BlackenImageHeapRootsVisitor();

    private class BlackenImageHeapRootsVisitor implements MemoryWalker.Visitor {
        @Override
        @SuppressWarnings("try")
        public <T> boolean visitNativeImageHeapRegion(T region, MemoryWalker.NativeImageHeapRegionAccess<T> access) {
            if (access.containsReferences(region) && access.isWritable(region)) {
                try (Timer timer = blackenImageHeapRootsTimer.open()) {
                    ImageHeapInfo imageHeapInfo = HeapImpl.getImageHeapInfo();
                    Pointer cur = Word.objectToUntrackedPointer(imageHeapInfo.firstWritableReferenceObject);
                    final Pointer last = Word.objectToUntrackedPointer(imageHeapInfo.lastWritableReferenceObject);
                    while (cur.belowOrEqual(last)) {
                        Object obj = cur.toObject();
                        if (obj != null) {
                            greyToBlackObjectVisitor.visitObjectInline(obj);
                        }
                        cur = LayoutEncoding.getObjectEnd(obj);
                    }
                }
            }
            return true;
        }

        @Override
        public <T extends PointerBase> boolean visitHeapChunk(T heapChunk, MemoryWalker.HeapChunkAccess<T> access) {
            throw VMError.shouldNotReachHere();
        }

        @Override
        public <T extends CodeInfo> boolean visitCode(T codeInfo, MemoryWalker.CodeAccess<T> access) {
            throw VMError.shouldNotReachHere();
        }
    }

    @SuppressWarnings("try")
    private void blackenDirtyCardRoots() {
        final Log trace = Log.noopLog().string("[GCImpl.blackenDirtyCardRoots:").newline();
        try (Timer bdcrt = blackenDirtyCardRootsTimer.open()) {
            /*
             * Walk To-Space looking for dirty cards, and within those for old-to-young pointers.
             * Promote any referenced young objects.
             */
            final HeapImpl heap = HeapImpl.getHeapImpl();
            heap.getOldGeneration().walkDirtyObjects(greyToBlackObjectVisitor, true);
        }
        trace.string("]").newline();
    }

    private static void prepareForPromotion(boolean isIncremental) {
        final Log trace = Log.noopLog().string("[GCImpl.prepareForPromotion:").newline();

        final HeapImpl heap = HeapImpl.getHeapImpl();
        final OldGeneration oldGen = heap.getOldGeneration();
        oldGen.prepareForPromotion();
        if (isIncremental) {
            heap.getYoungGeneration().prepareForPromotion();
        }
        trace.string("]").newline();

    }

    @SuppressWarnings("try")
    private void scanGreyObjects(boolean isIncremental) {
        final Log trace = Log.noopLog().string("[GCImpl.scanGreyObjects").newline();
        final HeapImpl heap = HeapImpl.getHeapImpl();
        final OldGeneration oldGen = heap.getOldGeneration();
        try (Timer sgot = scanGreyObjectsTimer.open()) {
            if (isIncremental) {
                scanGreyObjectsLoop();
            } else {
                oldGen.scanGreyObjects();
            }
        }
        trace.string("]").newline();
    }

    private static void scanGreyObjectsLoop() {
        final Log trace = Log.noopLog().string("[GCImpl.scanGreyObjectsLoop").newline();
        final HeapImpl heap = HeapImpl.getHeapImpl();
        final YoungGeneration youngGen = heap.getYoungGeneration();
        final OldGeneration oldGen = heap.getOldGeneration();
        boolean hasGrey = true;
        while (hasGrey) {
            hasGrey = youngGen.scanGreyObjects();
            hasGrey |= oldGen.scanGreyObjects();
        }
        trace.string("]").newline();
    }

    private static void promotePinnedObject(PinnedObjectImpl pinned) {
        final Log trace = Log.noopLog().string("[GCImpl.promotePinnedObject").string("  pinned: ").object(pinned);
        final HeapImpl heap = HeapImpl.getHeapImpl();
        final OldGeneration oldGen = heap.getOldGeneration();
        /* Find the chunk the object is in, and if necessary, move it to To space. */
        final Object referent = pinned.getObject();
        if (referent != null && !heap.isInImageHeap(referent)) {
            trace.string("  referent: ").object(referent);
            /*
             * The referent doesn't move, so I can ignore the result of the promotion because I
             * don't have to update any pointers to it.
             */
            oldGen.promoteObjectChunk(referent);
        }
        trace.string("]").newline();
    }

    private static void swapSpaces() {
        final Log trace = Log.noopLog().string("[GCImpl.swapSpaces:");
        final HeapImpl heap = HeapImpl.getHeapImpl();
        final OldGeneration oldGen = heap.getOldGeneration();
        heap.getYoungGeneration().swapSpaces();
        oldGen.swapSpaces();
        trace.string("]").newline();
    }

    private void releaseSpaces() {
        final Log trace = Log.noopLog().string("[GCImpl.releaseSpaces:");
        final HeapImpl heap = HeapImpl.getHeapImpl();
        heap.getYoungGeneration().releaseSpaces();
        if (completeCollection) {
            heap.getOldGeneration().releaseSpaces();
        }
        trace.string("]").newline();
    }

    /* Collection in progress methods. */

    /** Is a collection in progress? */
    final Latch collectionInProgress;

    private void startCollectionOrExit() {
        CollectionInProgressError.exitIf(collectionInProgress.getState());
        collectionInProgress.open();
    }

    private void finishCollection() {
        collectionInProgress.close();
    }

    /** Record that a collection is possible. */
    UnsignedWord possibleCollectionPrologue() {
        return getCollectionEpoch();
    }

    /**
     * Do whatever is necessary if a collection occurred since the a call to
     * {@link #possibleCollectionPrologue()}.
     *
     * Note that this method may get called by several threads for the same collection. For example,
     * if several threads arrive at {@link #possibleCollectionPrologue()} before any particular
     * collection, they will each present the sampled epoch number to this method, and cause any
     * collection watchers to report. That is mostly a problem for collection watchers to be aware
     * of. For example, watchers could keep track of the collections they have run in and reported
     * on, and only put out one report per collection.
     */
    @SuppressWarnings("try")
    void possibleCollectionEpilogue(UnsignedWord requestingEpoch) {
        if (requestingEpoch.aboveOrEqual(getCollectionEpoch())) {
            /* No GC happened, so do not run any epilogue. */
            return;

        } else if (VMOperation.isInProgress()) {
            /*
             * We are inside a VMOperation where we are not allowed to do certain things, e.g.,
             * perform a synchronization (because it can deadlock when a lock is held outside the
             * VMOperation).
             *
             * Note that the GC operation we are running the epilogue for is no longer in progress,
             * otherwise this check would always return.
             */
            return;

        } else if (!JavaThreads.currentJavaThreadInitialized()) {
            /*
             * Too early in the attach sequence of a thread to do anything useful, e.g., perform a
             * synchronization. Probably the allocation slow path for the first allocation of that
             * thread caused this epilogue.
             */
            return;
        }

        Timer refsTimer = new Timer("Enqueuing pending references and invoking internal cleaners");
        try (Timer timer = refsTimer.open()) {
            ReferenceHandler.maybeProcessCurrentlyPending();
        }
        if (SubstrateOptions.VerboseGC.getValue() && HeapOptions.PrintGCTimes.getValue()) {
            logOneTimer(Log.log(), "[GC epilogue reference processing: ", refsTimer);
            Log.log().string("]");
        }

        visitWatchersReport();
    }

    /* Collection counting. */

    /** A counter for collections. */
    private UnsignedWord collectionEpoch;

    UnsignedWord getCollectionEpoch() {
        return collectionEpoch;
    }

    private void incrementCollectionEpoch() {
        collectionEpoch = collectionEpoch.add(1);
    }

    /*
     * CollectionWatcher methods.
     */
    @Override
    public void registerCollectionWatcher(CollectionWatcher watcher) throws PreviouslyRegisteredElementException {
        /* Give a reasonable error message for trying to reuse a CollectionWatcher. */
        if (watcher.getHasBeenOnList()) {
            throw new PreviouslyRegisteredElementException("Attempting to reuse a previously-registered CollectionWatcher.");
        }
        collectionWatcherList.prepend(watcher);
    }

    @Override
    public void unregisterCollectionWatcher(final CollectionWatcher watcher) {
        watcher.removeElement();
    }

    @SuppressWarnings("try")
    private void visitWatchersBefore() {
        final Log trace = Log.noopLog().string("[GCImpl.visitWatchersBefore:").newline();
        trace.string("  Watchers before: ");
        try (Timer wbt = watchersBeforeTimer.open()) {
            for (CollectionWatcher watcher = collectionWatcherList.getFirst(); watcher != null; watcher = watcher.getNextElement()) {
                try {
                    watcher.beforeCollection();
                } catch (Throwable t) {
                    trace.string("[GCImpl.visitWatchersBefore: Caught: ").string(t.getClass().getName()).string("]").newline();
                }
            }
        }
        trace.string("]").newline();
    }

    @SuppressWarnings("try")
    private void visitWatchersAfter() {
        final Log trace = Log.noopLog().string("[GCImpl.visitWatchersAfter:").newline();
        trace.string("  Watchers after: ");
        try (Timer wat = watchersAfterTimer.open()) {
            /* Run the registered collection watchers. */
            for (CollectionWatcher watcher = collectionWatcherList.getFirst(); watcher != null; watcher = watcher.getNextElement()) {
                try {
                    watcher.afterCollection();
                } catch (Throwable t) {
                    trace.string("[GCImpl.visitWatchersAfter: Caught: ").string(t.getClass().getName()).string("]").newline();
                }
            }
        }
        trace.string("]").newline();
    }

    private void visitWatchersReport() {
        final Log trace = Log.noopLog().string("[GCImpl.visitWatchersReport:").newline();
        /*
         * Run single-threaded (but not at a safepoint) so as not be bothered by concurrent
         * scrubbing of the list due to random garbage collections. There is still window if someone
         * has unregistered a watcher and then there is another collection, because that will scrub
         * the list I am walking, even though I am in a VMOperation. I consider that a small-enough
         * possibility.
         */
        JavaVMOperation.enqueueBlockingNoSafepoint("GCImpl.visitWatchersReport", () -> {
            for (CollectionWatcher watcher = collectionWatcherList.getFirst(); watcher != null; watcher = watcher.getNextElement()) {
                try {
                    watcher.report();
                } catch (Throwable t) {
                    trace.string("[GCImpl.visitWatchersReport: Caught: ").string(t.getClass().getName()).string("]").newline();
                }
            }
        });
        trace.string("]").newline();
    }

    /** Scrub the allocation-free lists I maintain. */
    private void scrubLists() {
        collectionWatcherList.scrub();
    }

    /*
     * Field access methods.
     */

    protected Accounting getAccounting() {
        return accounting;
    }

    private CollectionPolicy getPolicy() {
        return policy;
    }

    private void setPolicy(final CollectionPolicy newPolicy) {
        policy = newPolicy;
    }

    GreyToBlackObjectVisitor getGreyToBlackObjectVisitor() {
        return greyToBlackObjectVisitor;
    }

    /*
     * Timers.
     */
    private final Timer blackenImageHeapRootsTimer;
    private final Timer blackenDirtyCardRootsTimer;
    private final Timer blackenStackRootsTimer;
    private final Timer cheneyScanFromRootsTimer;
    private final Timer cheneyScanFromDirtyRootsTimer;
    private final Timer collectionTimer;
    private final Timer referenceObjectsTimer;
    private final Timer promotePinnedObjectsTimer;
    private final Timer rootScanTimer;
    private final Timer scanGreyObjectsTimer;
    private final Timer releaseSpacesTimer;
    private final Timer verifyAfterTimer;
    private final Timer verifyBeforeTimer;
    private final Timer walkThreadLocalsTimer;
    private final Timer walkRuntimeCodeCacheTimer;
    private final Timer cleanRuntimeCodeCacheTimer;
    private final Timer watchersBeforeTimer;
    private final Timer watchersAfterTimer;
    private final Timer mutatorTimer;

    private void resetTimers() {
        final Log trace = Log.noopLog();
        trace.string("[GCImpl.resetTimers:");
        watchersBeforeTimer.reset();
        verifyBeforeTimer.reset();
        collectionTimer.reset();
        rootScanTimer.reset();
        cheneyScanFromRootsTimer.reset();
        cheneyScanFromDirtyRootsTimer.reset();
        promotePinnedObjectsTimer.reset();
        blackenStackRootsTimer.reset();
        walkThreadLocalsTimer.reset();
        walkRuntimeCodeCacheTimer.reset();
        cleanRuntimeCodeCacheTimer.reset();
        blackenImageHeapRootsTimer.reset();
        blackenDirtyCardRootsTimer.reset();
        scanGreyObjectsTimer.reset();
        referenceObjectsTimer.reset();
        releaseSpacesTimer.reset();
        verifyAfterTimer.reset();
        watchersAfterTimer.reset();
        /* The mutator timer is *not* reset here. */
        trace.string("]").newline();
    }

    private void logGCTimers(final Log log) {
        if (log.isEnabled()) {
            log.newline();
            log.string("  [GC nanoseconds:");
            logOneTimer(log, "    ", watchersBeforeTimer);
            logOneTimer(log, "    ", verifyBeforeTimer);
            logOneTimer(log, "    ", collectionTimer);
            logOneTimer(log, "      ", rootScanTimer);
            logOneTimer(log, "        ", cheneyScanFromRootsTimer);
            logOneTimer(log, "        ", cheneyScanFromDirtyRootsTimer);
            logOneTimer(log, "          ", promotePinnedObjectsTimer);
            logOneTimer(log, "          ", blackenStackRootsTimer);
            logOneTimer(log, "          ", walkThreadLocalsTimer);
            logOneTimer(log, "          ", walkRuntimeCodeCacheTimer);
            logOneTimer(log, "          ", cleanRuntimeCodeCacheTimer);
            logOneTimer(log, "          ", blackenImageHeapRootsTimer);
            logOneTimer(log, "          ", blackenDirtyCardRootsTimer);
            logOneTimer(log, "          ", scanGreyObjectsTimer);
            logOneTimer(log, "      ", referenceObjectsTimer);
            logOneTimer(log, "      ", releaseSpacesTimer);
            logOneTimer(log, "    ", verifyAfterTimer);
            logOneTimer(log, "    ", watchersAfterTimer);
            logGCLoad(log, "    ", "GCLoad", collectionTimer, mutatorTimer);
            log.string("]");
        }
    }

    private static void logOneTimer(final Log log, final String prefix, final Timer timer) {
        /* If the timer has recorded some time, then print it. */
        if (timer.getCollectedNanos() > 0) {
            log.newline().string(prefix).string(timer.getName()).string(": ").signed(timer.getCollectedNanos());
        }
    }

    /**
     * Log the "GC load" for this collection as the collection time divided by the sum of the
     * previous mutator interval plus the collection time. This method uses wall-time, and so does
     * not take in to account that the collector is single-threaded, while the mutator might be
     * multi-threaded.
     */
    private static void logGCLoad(Log log, String prefix, String label, Timer cTimer, Timer mTimer) {
        final long collectionNanos = cTimer.getLastIntervalNanos();
        final long mutatorNanos = mTimer.getLastIntervalNanos();
        /* Compute a rounded percentage, since I can only log integers. */
        final long intervalNanos = mutatorNanos + collectionNanos;
        final long intervalGCPercent = (((100 * collectionNanos) + (intervalNanos / 2)) / intervalNanos);
        log.newline().string(prefix).string(label).string(": ").signed(intervalGCPercent).string("%");
    }

    /**
     * Accounting for this collector. Times are in nanoseconds. ChunkBytes refer to bytes reserved
     * (but maybe not occupied). ObjectBytes refer to bytes occupied by objects.
     */
    public static class Accounting {

        /* State that is available to collection policies, etc. */
        private long incrementalCollectionCount;
        private long incrementalCollectionTotalNanos;
        private long completeCollectionCount;
        private long completeCollectionTotalNanos;
        private UnsignedWord collectedTotalChunkBytes;
        private UnsignedWord normalChunkBytes;
        private UnsignedWord promotedTotalChunkBytes;
        private UnsignedWord copiedTotalChunkBytes;
        /* Before and after measures. */
        private UnsignedWord youngChunkBytesBefore;
        private UnsignedWord youngChunkBytesAfter;
        private UnsignedWord oldChunkBytesBefore;
        private UnsignedWord oldChunkBytesAfter;
        /* History of promotions and copies. */
        private int history;
        private UnsignedWord[] promotedUnpinnedChunkBytes;
        private UnsignedWord[] copiedUnpinnedChunkBytes;
        /*
         * Bytes allocated in Objects, as opposed to bytes of chunks. These are only maintained if
         * -R:+PrintGCSummary because they are expensive.
         */
        private UnsignedWord collectedTotalObjectBytes;
        private UnsignedWord youngObjectBytesBefore;
        private UnsignedWord youngObjectBytesAfter;
        private UnsignedWord oldObjectBytesBefore;
        private UnsignedWord oldObjectBytesAfter;
        private UnsignedWord normalObjectBytes;

        @Platforms(Platform.HOSTED_ONLY.class)
        Accounting() {
            this.incrementalCollectionCount = 0L;
            this.incrementalCollectionTotalNanos = 0L;
            this.completeCollectionCount = 0L;
            this.completeCollectionTotalNanos = 0L;
            this.normalChunkBytes = WordFactory.zero();
            this.promotedTotalChunkBytes = WordFactory.zero();
            this.collectedTotalChunkBytes = WordFactory.zero();
            this.copiedTotalChunkBytes = WordFactory.zero();
            this.history = 0;
            this.youngChunkBytesBefore = WordFactory.zero();
            this.youngChunkBytesAfter = WordFactory.zero();
            this.oldChunkBytesBefore = WordFactory.zero();
            this.oldChunkBytesAfter = WordFactory.zero();
            /* Initialize histories. */
            this.promotedUnpinnedChunkBytes = historyFactory(WordFactory.zero());
            this.copiedUnpinnedChunkBytes = historyFactory(WordFactory.zero());
            /* Object bytes, if requested. */
            this.collectedTotalObjectBytes = WordFactory.zero();
            this.youngObjectBytesBefore = WordFactory.zero();
            this.youngObjectBytesAfter = WordFactory.zero();
            this.oldObjectBytesBefore = WordFactory.zero();
            this.oldObjectBytesAfter = WordFactory.zero();
            this.normalObjectBytes = WordFactory.zero();
        }

        @Platforms(Platform.HOSTED_ONLY.class)
        public static Accounting factory() {
            return new Accounting();
        }

        /*
         * Access methods.
         */

        long getIncrementalCollectionCount() {
            return incrementalCollectionCount;
        }

        long getIncrementalCollectionTotalNanos() {
            return incrementalCollectionTotalNanos;
        }

        UnsignedWord getNormalChunkBytes() {
            return normalChunkBytes;
        }

        UnsignedWord getPromotedTotalChunkBytes() {
            return promotedTotalChunkBytes;
        }

        long getCompleteCollectionCount() {
            return completeCollectionCount;
        }

        long getCompleteCollectionTotalNanos() {
            return completeCollectionTotalNanos;
        }

        UnsignedWord getCopiedTotalChunkBytes() {
            return copiedTotalChunkBytes;
        }

        UnsignedWord getCollectedTotalChunkBytes() {
            return collectedTotalChunkBytes;
        }

        UnsignedWord getCollectedTotalObjectBytes() {
            return collectedTotalObjectBytes;
        }

        UnsignedWord getNormalObjectBytes() {
            return normalObjectBytes;
        }

        /** Bytes held in the old generation. */
        UnsignedWord getOldGenerationAfterChunkBytes() {
            return oldChunkBytesAfter;
        }

        /** Bytes held in the young generation. */
        UnsignedWord getYoungChunkBytesAfter() {
            return youngChunkBytesAfter;
        }

        /** Average promoted unpinned chunk bytes. */
        UnsignedWord averagePromotedUnpinnedChunkBytes() {
            return averageOfHistory(promotedUnpinnedChunkBytes);
        }

        /* History methods. */

        /** Increment the amount of history I have seen. */
        void incrementHistory() {
            history += 1;
        }

        /** Convert the history counter into an index into a bounded history array. */
        int historyAsIndex() {
            return historyAsIndex(0);
        }

        /** Convert an offset into an index into a bounded history array. */
        int historyAsIndex(int offset) {
            return ((history + offset) % Options.GCHistory.getValue().intValue());
        }

        UnsignedWord[] historyFactory(UnsignedWord initial) {
            assert initial.equal(WordFactory.zero()) : "Can not initialize history to any value except WordFactory.zero().";
            final UnsignedWord[] result = new UnsignedWord[Options.GCHistory.getValue().intValue()];
            /* Initialization to null/WordFactory.zero() is implicit. */
            return result;
        }

        /** Get the current element of a history array. */
        UnsignedWord getHistoryOf(UnsignedWord[] array) {
            return getHistoryOf(array, 0);
        }

        /** Get an offset element of a history array. */
        UnsignedWord getHistoryOf(UnsignedWord[] array, int offset) {
            return array[historyAsIndex(offset)];
        }

        /** Set the current element of a history array. */
        void setHistoryOf(UnsignedWord[] array, UnsignedWord value) {
            setHistoryOf(array, 0, value);
        }

        /** Set an offset element of a history array. */
        void setHistoryOf(UnsignedWord[] array, int offset, UnsignedWord value) {
            array[historyAsIndex(offset)] = value;
        }

        /** Average the non-zero elements of a history array. */
        UnsignedWord averageOfHistory(UnsignedWord[] array) {
            int count = 0;
            UnsignedWord sum = WordFactory.zero();
            UnsignedWord result = WordFactory.zero();
            for (int offset = 0; offset < array.length; offset += 1) {
                final UnsignedWord element = getHistoryOf(array, offset);
                if (element.aboveThan(WordFactory.zero())) {
                    sum = sum.add(element);
                    count += 1;
                }
            }
            if (count > 0) {
                result = sum.unsignedDivide(count);
            }
            return result;
        }

        /*
         * Methods for collectors.
         */

        void beforeCollection() {
            final Log trace = Log.noopLog().string("[GCImpl.Accounting.beforeCollection:").newline();
            /* Gather some space statistics. */
            incrementHistory();
            final HeapImpl heap = HeapImpl.getHeapImpl();
            final YoungGeneration youngGen = heap.getYoungGeneration();
            youngChunkBytesBefore = youngGen.getChunkUsedBytes();
            /* This is called before the collection, so OldSpace is FromSpace. */
            final Space oldSpace = heap.getOldGeneration().getFromSpace();
            oldChunkBytesBefore = oldSpace.getChunkBytes();
            /* Objects are allocated in the young generation. */
            normalChunkBytes = normalChunkBytes.add(youngChunkBytesBefore);
            /* Keep some aggregate metrics. */
            if (HeapOptions.PrintGCSummary.getValue()) {
                youngObjectBytesBefore = youngGen.getObjectBytes();
                oldObjectBytesBefore = oldSpace.getObjectBytes();
                normalObjectBytes = normalObjectBytes.add(youngObjectBytesBefore);
            }
            trace.string("  youngChunkBytesBefore: ").unsigned(youngChunkBytesBefore)
                            .string("  oldChunkBytesBefore: ").unsigned(oldChunkBytesBefore);
            trace.string("]").newline();
        }

        void afterCollection(boolean completeCollection, Timer collectionTimer) {
            if (completeCollection) {
                afterCompleteCollection(collectionTimer);
            } else {
                afterIncrementalCollection(collectionTimer);
            }
        }

        private void afterIncrementalCollection(Timer collectionTimer) {
            final Log trace = Log.noopLog().string("[GCImpl.Accounting.afterIncrementalCollection:");
            /*
             * Aggregating collection information is needed because any given collection policy may
             * not be called for all collections, but may want to make decisions based on the
             * aggregate values.
             */
            incrementalCollectionCount += 1;
            afterCollectionCommon();
            /* Incremental collections only promote. */
            setHistoryOf(promotedUnpinnedChunkBytes, oldChunkBytesAfter.subtract(oldChunkBytesBefore));
            promotedTotalChunkBytes = promotedTotalChunkBytes.add(getHistoryOf(promotedUnpinnedChunkBytes));
            incrementalCollectionTotalNanos += collectionTimer.getCollectedNanos();
            trace.string("  incrementalCollectionCount: ").signed(incrementalCollectionCount)
                            .string("  oldChunkBytesAfter: ").unsigned(oldChunkBytesAfter)
                            .string("  oldChunkBytesBefore: ").unsigned(oldChunkBytesBefore)
                            .string("  promotedUnpinnedChunkBytes: ").unsigned(getHistoryOf(promotedUnpinnedChunkBytes));
            trace.string("]").newline();
        }

        private void afterCompleteCollection(Timer collectionTimer) {
            final Log trace = Log.noopLog().string("[GCImpl.Accounting.afterCompleteCollection:");
            completeCollectionCount += 1;
            afterCollectionCommon();
            /* Complete collections only copy, and they copy everything. */
            setHistoryOf(copiedUnpinnedChunkBytes, oldChunkBytesAfter);
            copiedTotalChunkBytes = copiedTotalChunkBytes.add(oldChunkBytesAfter);
            completeCollectionTotalNanos += collectionTimer.getCollectedNanos();
            trace.string("  completeCollectionCount: ").signed(completeCollectionCount)
                            .string("  oldChunkBytesAfter: ").unsigned(oldChunkBytesAfter);
            trace.string("]").newline();
        }

        /** Shared after collection processing. */
        void afterCollectionCommon() {
            final HeapImpl heap = HeapImpl.getHeapImpl();
            /*
             * This is called after the collection, after the space flip, so OldSpace is FromSpace.
             */
            final YoungGeneration youngGen = heap.getYoungGeneration();
            youngChunkBytesAfter = youngGen.getChunkUsedBytes();
            final Space oldSpace = heap.getOldGeneration().getFromSpace();
            oldChunkBytesAfter = oldSpace.getChunkBytes();
            final UnsignedWord beforeChunkBytes = youngChunkBytesBefore.add(oldChunkBytesBefore);
            final UnsignedWord afterChunkBytes = oldChunkBytesAfter.add(youngChunkBytesAfter);
            final UnsignedWord collectedChunkBytes = beforeChunkBytes.subtract(afterChunkBytes);
            collectedTotalChunkBytes = collectedTotalChunkBytes.add(collectedChunkBytes);
            if (HeapOptions.PrintGCSummary.getValue()) {
                youngObjectBytesAfter = youngGen.getObjectBytes();
                oldObjectBytesAfter = oldSpace.getObjectBytes();
                final UnsignedWord beforeObjectBytes = youngObjectBytesBefore.add(oldObjectBytesBefore);
                final UnsignedWord collectedObjectBytes = beforeObjectBytes.subtract(oldObjectBytesAfter).subtract(youngObjectBytesAfter);
                collectedTotalObjectBytes = collectedTotalObjectBytes.add(collectedObjectBytes);
            }
        }
    }

    /** A class for the timers kept by the collector. */
    public static class Timer implements AutoCloseable {

        public Timer open() {
            openNanos = System.nanoTime();
            closeNanos = 0L;
            return this;
        }

        @Override
        public void close() {
            /* If a timer was not opened, pretend it was opened at the start of the VM. */
            if (openNanos == 0L) {
                openNanos = HeapChunkProvider.getFirstAllocationTime();
            }
            closeNanos = System.nanoTime();
            collectedNanos += closeNanos - openNanos;
        }

        public void reset() {
            openNanos = 0L;
            closeNanos = 0L;
            collectedNanos = 0L;
        }

        public String getName() {
            return name;
        }

        public long getStart() {
            return openNanos;
        }

        public long getFinish() {
            assert closeNanos > 0L : "Should have closed timer";
            return closeNanos;
        }

        /** Get all the nanoseconds collected between open/close pairs since the last reset. */
        long getCollectedNanos() {
            return collectedNanos;
        }

        /** Get the nanoseconds collected by the most recent open/close pair. */
        long getLastIntervalNanos() {
            assert openNanos > 0L : "Should have opened timer";
            assert closeNanos > 0L : "Should have closed timer";
            return closeNanos - openNanos;
        }

        static long getTimeSinceFirstAllocation(final long nanos) {
            return nanos - HeapChunkProvider.getFirstAllocationTime();
        }

        public Timer(final String name) {
            this.name = name;
        }

        /* State. */
        final String name;
        long openNanos;
        long closeNanos;
        long collectedNanos;
    }

    RememberedSetConstructor getRememberedSetConstructor() {
        return rememberedSetConstructor;
    }

    /** A ObjectVisitor to build the remembered set for a chunk. */
    protected static class RememberedSetConstructor implements ObjectVisitor {

        /* Lazy-initialized state. */
        AlignedHeapChunk.AlignedHeader chunk;

        /** Constructor. */
        @Platforms(Platform.HOSTED_ONLY.class)
        RememberedSetConstructor() {
            /* Nothing to do. */
        }

        /** Lazy initializer. */
        public void initialize(AlignedHeapChunk.AlignedHeader aChunk) {
            this.chunk = aChunk;
        }

        /** Visit the interior Pointers of an Object. */
        @Override
        public boolean visitObject(final Object o) {
            return visitObjectInline(o);
        }

        @Override
        @AlwaysInline("GC performance")
        public boolean visitObjectInline(final Object o) {
            AlignedHeapChunk.setUpRememberedSetForObjectOfAlignedHeapChunk(chunk, o);
            return true;
        }

        public void reset() {
            chunk = WordFactory.nullPointer();
        }
    }

    /**
     * Throw one of these to signal that a collection is already in progress.
     */
    static final class CollectionInProgressError extends Error {

        static void exitIf(final boolean state) {
            if (state) {
                /* Throw an error to capture the stack backtrace. */
                final Log failure = Log.log();
                failure.string("[CollectionInProgressError:");
                failure.newline();
                ThreadStackPrinter.printBacktrace();
                failure.string("]").newline();
                throw CollectionInProgressError.SINGLETON;
            }
        }

        private CollectionInProgressError() {
            super();
        }

        /** A singleton instance, to be thrown without allocation. */
        private static final CollectionInProgressError SINGLETON = new CollectionInProgressError();

        /** Generated serialVersionUID. */
        private static final long serialVersionUID = -4473303241014559591L;
    }

    private static class CollectionVMOperation extends NativeVMOperation {
        protected CollectionVMOperation() {
            super("Garbage collection", SystemEffect.SAFEPOINT);
        }

        @Override
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        protected boolean isGC() {
            return true;
        }

        @Override
        @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate while collecting")
        protected void operate(NativeVMOperationData data) {
            /*
             * Exceptions during collections are fatal. The heap is likely in an inconsistent state.
             * The GC must also be allocation free, i.e., we cannot allocate exception stack traces
             * while in the GC. This is bad for diagnosing errors in the GC. To improve the
             * situation a bit, we switch on the flag to make implicit exceptions such as
             * NullPointerExceptions fatal errors. This ensures that we fail early at the place
             * where the fatal error reporting can still dump the full stack trace.
             */
            ImplicitExceptions.activateImplicitExceptionsAreFatal();
            try {
                CollectionVMOperationData d = (CollectionVMOperationData) data;
                boolean outOfMemory = HeapImpl.getHeapImpl().getGCImpl().collectOperation(GCCause.fromId(d.getCauseId()), d.getRequestingEpoch());
                d.setOutOfMemory(outOfMemory);
            } catch (Throwable t) {
                throw VMError.shouldNotReachHere(t);
            } finally {
                ImplicitExceptions.deactivateImplicitExceptionsAreFatal();
            }
        }

        @Override
        protected boolean hasWork(NativeVMOperationData data) {
            CollectionVMOperationData d = (CollectionVMOperationData) data;
            return HeapImpl.getHeapImpl().getGCImpl().getCollectionEpoch().equal(d.getRequestingEpoch());
        }
    }

    @RawStructure
    private interface CollectionVMOperationData extends NativeVMOperationData {
        @RawField
        int getCauseId();

        @RawField
        void setCauseId(int value);

        @RawField
        UnsignedWord getRequestingEpoch();

        @RawField
        void setRequestingEpoch(UnsignedWord value);

        @RawField
        boolean getOutOfMemory();

        @RawField
        void setOutOfMemory(boolean value);
    }

    /* Invoked by a shutdown hook registered in the GCImpl constructor. */
    private void printGCSummary() {
        if (!HeapOptions.PrintGCSummary.getValue()) {
            return;
        }

        final Log log = Log.log();
        final String prefix = "PrintGCSummary: ";

        /* Print GC configuration. */
        log.string(prefix).string("YoungGenerationSize: ").unsigned(HeapPolicy.getMaximumYoungGenerationSize()).newline();
        log.string(prefix).string("MinimumHeapSize: ").unsigned(HeapPolicy.getMinimumHeapSize()).newline();
        log.string(prefix).string("MaximumHeapSize: ").unsigned(HeapPolicy.getMaximumHeapSize()).newline();
        log.string(prefix).string("AlignedChunkSize: ").unsigned(HeapPolicy.getAlignedHeapChunkSize()).newline();

        /* Add in any young objects allocated since the last collection. */
        JavaVMOperation.enqueueBlockingSafepoint("PrintGCSummaryShutdownHook", ThreadLocalAllocation::disableThreadLocalAllocation);
        final HeapImpl heap = HeapImpl.getHeapImpl();
        final Space edenSpace = heap.getYoungGeneration().getEden();
        final UnsignedWord youngChunkBytes = edenSpace.getChunkBytes();
        final UnsignedWord youngObjectBytes = edenSpace.getObjectBytes();

        /* Compute updated values. */
        final UnsignedWord allocatedNormalChunkBytes = accounting.getNormalChunkBytes().add(youngChunkBytes);
        final UnsignedWord allocatedNormalObjectBytes = accounting.getNormalObjectBytes().add(youngObjectBytes);

        /* Print the total bytes allocated and collected by chunks. */
        log.string(prefix).string("CollectedTotalChunkBytes: ").signed(accounting.getCollectedTotalChunkBytes()).newline();
        log.string(prefix).string("CollectedTotalObjectBytes: ").signed(accounting.getCollectedTotalObjectBytes()).newline();
        log.string(prefix).string("AllocatedNormalChunkBytes: ").signed(allocatedNormalChunkBytes).newline();
        log.string(prefix).string("AllocatedNormalObjectBytes: ").signed(allocatedNormalObjectBytes).newline();

        /* Print the collection counts and times. */
        final long incrementalNanos = accounting.getIncrementalCollectionTotalNanos();
        log.string(prefix).string("IncrementalGCCount: ").signed(accounting.getIncrementalCollectionCount()).newline();
        log.string(prefix).string("IncrementalGCNanos: ").signed(incrementalNanos).newline();
        final long completeNanos = accounting.getCompleteCollectionTotalNanos();
        log.string(prefix).string("CompleteGCCount: ").signed(accounting.getCompleteCollectionCount()).newline();
        log.string(prefix).string("CompleteGCNanos: ").signed(completeNanos).newline();
        /* Compute a GC load percent. */
        final long gcNanos = incrementalNanos + completeNanos;
        final long mutatorNanos = mutatorTimer.getCollectedNanos();
        final long totalNanos = gcNanos + mutatorNanos;
        final long roundedGCLoad = (0 < totalNanos ? TimeUtils.roundedDivide(100 * gcNanos, totalNanos) : 0);
        log.string(prefix).string("GCNanos: ").signed(gcNanos).newline();
        log.string(prefix).string("TotalNanos: ").signed(totalNanos).newline();
        log.string(prefix).string("GCLoadPercent: ").signed(roundedGCLoad).newline();
    }

    @Override
    public List<GarbageCollectorMXBean> getGarbageCollectorMXBeanList() {
        return gcManagementFactory.getGCBeanList();
    }
}

final class GarbageCollectorManagementFactory {

    private List<GarbageCollectorMXBean> gcBeanList;

    GarbageCollectorManagementFactory() {
        final List<GarbageCollectorMXBean> newList = new ArrayList<>();
        /* Changing the order of this list will break assumptions we take in the object replacer. */
        newList.add(new IncrementalGarbageCollectorMXBean());
        newList.add(new CompleteGarbageCollectorMXBean());
        gcBeanList = newList;
    }

    List<GarbageCollectorMXBean> getGCBeanList() {
        return gcBeanList;
    }

    /** A GarbageCollectorMXBean for the incremental collector. */
    private static final class IncrementalGarbageCollectorMXBean implements com.sun.management.GarbageCollectorMXBean, NotificationEmitter {

        private IncrementalGarbageCollectorMXBean() {
            /* Nothing to do. */
        }

        @Override
        public long getCollectionCount() {
            return HeapImpl.getHeapImpl().getGCImpl().getAccounting().getIncrementalCollectionCount();
        }

        @Override
        public long getCollectionTime() {
            final long nanos = HeapImpl.getHeapImpl().getGCImpl().getAccounting().getIncrementalCollectionTotalNanos();
            return TimeUtils.roundNanosToMillis(nanos);
        }

        @Override
        public String[] getMemoryPoolNames() {
            /* Return a new array each time because arrays are not immutable. */
            return new String[]{"young generation space"};
        }

        @Override
        public String getName() {
            /* Changing this name will break assumptions we take in the object replacer. */
            return "young generation scavenger";
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public ObjectName getObjectName() {
            return Util.newObjectName(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE, getName());
        }

        @Override
        public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) {
        }

        @Override
        public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) {
        }

        @Override
        public void removeNotificationListener(NotificationListener listener) {
        }

        @Override
        public MBeanNotificationInfo[] getNotificationInfo() {
            return new MBeanNotificationInfo[0];
        }

        @Override
        public GcInfo getLastGcInfo() {
            return null;
        }
    }

    /** A GarbageCollectorMXBean for the complete collector. */
    private static final class CompleteGarbageCollectorMXBean implements com.sun.management.GarbageCollectorMXBean, NotificationEmitter {

        private CompleteGarbageCollectorMXBean() {
            /* Nothing to do. */
        }

        @Override
        public long getCollectionCount() {
            return HeapImpl.getHeapImpl().getGCImpl().getAccounting().getCompleteCollectionCount();
        }

        @Override
        public long getCollectionTime() {
            final long nanos = HeapImpl.getHeapImpl().getGCImpl().getAccounting().getCompleteCollectionTotalNanos();
            return TimeUtils.roundNanosToMillis(nanos);
        }

        @Override
        public String[] getMemoryPoolNames() {
            /* Return a new array each time because arrays are not immutable. */
            return new String[]{"young generation space", "old generation space"};
        }

        @Override
        public String getName() {
            /* Changing this name will break assumptions we take in the object replacer. */
            return "complete scavenger";
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public ObjectName getObjectName() {
            return Util.newObjectName(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE, getName());
        }

        @Override
        public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) {
        }

        @Override
        public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) {
        }

        @Override
        public void removeNotificationListener(NotificationListener listener) {
        }

        @Override
        public MBeanNotificationInfo[] getNotificationInfo() {
            return new MBeanNotificationInfo[0];
        }

        @Override
        public GcInfo getLastGcInfo() {
            return null;
        }
    }
}
