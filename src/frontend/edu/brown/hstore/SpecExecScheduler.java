package edu.brown.hstore;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

import org.apache.log4j.Logger;
import org.voltdb.catalog.Procedure;
import org.voltdb.types.SpecExecSchedulerPolicyType;
import org.voltdb.types.SpeculationType;

import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.estimators.EstimatorState;
import edu.brown.hstore.internal.InternalMessage;
import edu.brown.hstore.specexec.checkers.AbstractConflictChecker;
import edu.brown.hstore.txns.AbstractTransaction;
import edu.brown.hstore.txns.LocalTransaction;
import edu.brown.interfaces.Configurable;
import edu.brown.interfaces.DebugContext;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.profilers.SpecExecProfiler;
import edu.brown.statistics.FastIntHistogram;
import edu.brown.utils.PartitionSet;
import edu.brown.utils.StringUtil;

/**
 * Special scheduler that can figure out what the next best single-partition
 * to speculatively execute at a partition based on the current distributed transaction 
 * @author pavlo
 */
public class SpecExecScheduler implements Configurable {
    private static final Logger LOG = Logger.getLogger(SpecExecScheduler.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }

    // ----------------------------------------------------------------------------
    // CONFIGURATION PARAMETERS
    // ----------------------------------------------------------------------------
    
    /**
     * The scheduling policy type.
     */
    private SpecExecSchedulerPolicyType policyType;
    
    /**
     * The number of txns to examine in the queue per invocation of next()
     */
    private int windowSize = 1;
    
    /**
     * Ignore all LocalTransaction handles
     */
    private boolean ignore_all_local = false;
    
    /**
     * Don't reset the iterator if the queue size changes
     */
    private boolean ignore_queue_size_change = false;
    
    /**
     * Don't reset the iterator if the current SpeculationType changes
     */
    private boolean ignore_speculation_type_change = false;
    
    /**
     * What SpeculationTypes to ignore 
     */
    private Set<SpeculationType> ignore_types = null;
   
    // ----------------------------------------------------------------------------
    // INTERNAL STATE
    // ----------------------------------------------------------------------------

    private final int partitionId;
    private final PartitionLockQueue queue;
    private boolean disabled = false;
    private AbstractConflictChecker checker;
    private AbstractTransaction lastDtxn;
    private SpeculationType lastSpecType;
    private Iterator<AbstractTransaction> lastIterator;
    private int lastSize = 0;
    private boolean interrupted = false;
    private Class<? extends InternalMessage> latchMsg;

    // ----------------------------------------------------------------------------
    // PROFILING STUFF
    // ----------------------------------------------------------------------------
    
    /**
     * Maintain a separate SpecExecProfiler per SpeculationType.
     */
    private final SpecExecProfiler profilerMap[];
    private boolean profiling = false;
    private double profiling_sample;
    private final Random profiling_rand = new Random();
    private AbstractTransaction profilerCurrentTxn;
    private boolean profilerSkipCurrentTxn = true;
    private final FastIntHistogram profilerExecuteCounter = new FastIntHistogram(SpeculationType.values().length);
    
    // ----------------------------------------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------------------------------------
    
    /**
     * Constructor
     * @param catalogContext
     * @param checker TODO
     * @param partitionId
     * @param queue
     */
    public SpecExecScheduler(AbstractConflictChecker checker,
                             int partitionId, PartitionLockQueue queue,
                             SpecExecSchedulerPolicyType schedule_policy, int window_size) {
        assert(schedule_policy != null) : "Unsupported schedule policy parameter passed in";
        
        this.partitionId = partitionId;
        this.queue = queue;
        this.checker = checker;
        this.policyType = schedule_policy;
        this.windowSize = window_size;
        
        this.profiling = HStoreConf.singleton().site.specexec_profiling;
        this.profiling_sample = HStoreConf.singleton().site.specexec_profiling_sample;
        this.profilerExecuteCounter.setKeepZeroEntries(true);
        this.profilerMap = new SpecExecProfiler[SpeculationType.values().length];
        if (this.profiling) {
            for (int i = 0; i < this.profilerMap.length; i++) {
                this.profilerMap[i] = new SpecExecProfiler();
            } // FOR
        }
    }
    
    @Override
    public void updateConf(HStoreConf hstore_conf, String[] changed) {
        // Tell the SpecExecScheduler to ignore certain SpeculationTypes
        if (hstore_conf.site.specexec_ignore_stallpoints != null) {
            if (this.ignore_types != null) this.ignore_types.clear();
            for (String element : StringUtil.splitList(hstore_conf.site.specexec_ignore_stallpoints)) {
                SpeculationType specType = SpeculationType.get(element);
                if (specType != null) this.ignoreSpeculationType(specType);
            } // FOR
        }
        
        this.ignore_queue_size_change = hstore_conf.site.specexec_ignore_queue_size_change;
        this.ignore_all_local = hstore_conf.site.specexec_ignore_all_local;
        
        if (hstore_conf.site.specexec_disable_partitions != null) {
            // Disable on all partitions
            // Use HStoreConf's site.specexec_enable=false instead
            if (hstore_conf.site.specexec_disable_partitions.trim() == "*") {
                this.setDisabled(true);
            }
            else {
                PartitionSet partitions = PartitionSet.parse(hstore_conf.site.specexec_disable_partitions);
                this.setDisabled(partitions.contains(this.partitionId));
            }
        }
        
        if (hstore_conf.site.specexec_unsafe) {
            // this.specExecScheduler.setIgnoreQueueSizeChange(true);
            // this.specExecScheduler.setIgnoreSpeculationTypeChange(true);
        }
    }
    
    // ----------------------------------------------------------------------------
    // ACCESS METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Replace the ConflictChecker. This should only be used for testing
     * @param checker
     */
    protected void setConflictChecker(AbstractConflictChecker checker) {
        LOG.warn(String.format("Replacing original checker %s with %s",
                 this.checker.getClass().getSimpleName(),
                 checker.getClass().getCanonicalName()));
        this.checker = checker;
    }
    protected void setIgnoreAllLocal(boolean ignore_all_local) {
        this.ignore_all_local = ignore_all_local;
    }
    protected void setIgnoreQueueSizeChange(boolean ignore_queue_changes) {
        this.ignore_queue_size_change = ignore_queue_changes;
    }
    protected void setIgnoreSpeculationTypeChange(boolean ignore_speculation_type_change) {
        this.ignore_speculation_type_change = ignore_speculation_type_change;
    }
    protected void setWindowSize(int window) {
        this.windowSize = window;
    }
    protected void setPolicyType(SpecExecSchedulerPolicyType policy) {
        this.policyType = policy;
    }
    protected void reset() {
        this.lastIterator = null;
    }
    
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
        if (this.disabled == true)
            LOG.info("Disabled speculative execution scheduling at partition " + this.partitionId);
    }

    public boolean shouldIgnoreProcedure(Procedure catalog_proc) {
        return (this.checker.shouldIgnoreProcedure(catalog_proc));
    }
    
    public void ignoreSpeculationType(SpeculationType specType) {
        if (this.ignore_types == null) {
            this.ignore_types = new HashSet<SpeculationType>();
        }
        this.ignore_types.add(specType);
        if (debug.val)
            LOG.debug(String.format("Setting %s to ignore speculation at stall point %s",
                      this.getClass().getSimpleName(), specType));
    }
    
    public void interruptSearch(InternalMessage msg) {
        if (this.interrupted == false) {
            this.interrupted = true;
            this.latchMsg = msg.getClass();
        }
    }
    
    /**
     * Find the next non-conflicting txn that we can speculatively execute.
     * Note that if we find one, it will be immediately removed from the queue
     * and returned. If you do this and then find out for some reason that you
     * can't execute the StartTxnMessage that is returned, you must be sure
     * to requeue it back.
     * @param dtxn The current distributed txn at this partition.
     * @return
     */
    public LocalTransaction next(AbstractTransaction dtxn, SpeculationType specType) {
        if (this.disabled) return (null);
        
        this.interrupted = false;
        
        if (debug.val) {
            LOG.debug(String.format("%s - Checking queue for transaction to speculatively execute " +
                      "[specType=%s, windowSize=%d, queueSize=%d, policy=%s]",
                      dtxn, specType, this.windowSize, this.queue.size(), this.policyType));
            if (trace.val)
                LOG.trace(String.format("%s - Last Invocation [lastDtxn=%s, lastSpecType=%s, lastIterator=%s]",
                          dtxn, this.lastDtxn, this.lastSpecType, this.lastIterator));
        }
        
        SpecExecProfiler profiler = null;
        if (this.profiling) {
            // This is the first time that we've seen this dtxn, so
            // we need to dump out its stats. This is not entirely accurate,
            // since we won't have the last txn's info, but it's good enough.
            if (this.profilerCurrentTxn != dtxn) {
                if (this.profilerCurrentTxn != null && this.profilerSkipCurrentTxn == false) {
                    for (int i = 0; i < this.profilerMap.length; i++) {
                        int cnt = (int)this.profilerExecuteCounter.get(i, 0);
                        this.profilerMap[i].num_executed.put(i, cnt);
                    } // FOR
                    this.profilerExecuteCounter.clearValues();
                    this.profilerCurrentTxn = null;
                }
                this.profilerCurrentTxn = dtxn;
                
                // Check whether we should enable it for this new txn
                if (this.profiling_rand.nextDouble() < this.profiling_sample) {
                    this.profilerSkipCurrentTxn = false;
                }
                else {
                    this.profilerSkipCurrentTxn = true;
                }
            }
            if (this.profilerSkipCurrentTxn == false) {
                profiler = this.profilerMap[specType.ordinal()];
                profiler.total_time.start();
            }
        }
        
        // Check whether we need to ignore this speculation stall point
        if (this.ignore_types != null && this.ignore_types.contains(specType)) {
            if (debug.val)
                LOG.debug(String.format("%s - Ignoring txn because we are set to ignore %s",
                          dtxn, specType));
            if (profiler != null) profiler.total_time.stop();
            return (null);
        }
        
        // If we have a distributed txn, then check make sure it's legit
        if (dtxn != null) {
            assert(this.checker.shouldIgnoreProcedure(dtxn.getProcedure()) == false) :
                String.format("Trying to check for speculative txns for %s but the txn " +
                		      "should have been ignored", dtxn);
            
            // If this is a LocalTransaction and all of the remote partitions that it needs are
            // on the same site, then we won't bother with trying to pick something out
            // because there is going to be very small wait times.
            if (this.ignore_all_local && dtxn instanceof LocalTransaction && ((LocalTransaction)dtxn).isPredictAllLocal()) {
                if (debug.val)
                    LOG.debug(String.format("%s - Ignoring current distributed txn because all of the partitions that " +
                              "it is using are on the same HStoreSite [%s]", dtxn, dtxn.getProcedure()));
                if (profiler != null) profiler.total_time.stop();
                return (null);
            }
        }
        
        // Now peek in the queue looking for single-partition txns that do not
        // conflict with the current dtxn
        LocalTransaction next = null;
        int txn_ctr = 0;
        int examined_ctr = 0;
        int matched_ctr = 0;
        boolean was_interrupted = false;
        long bestTime = (this.policyType == SpecExecSchedulerPolicyType.LONGEST ? Long.MIN_VALUE : Long.MAX_VALUE);

        // Check whether we can use our same iterator from the last call
        if (this.policyType != SpecExecSchedulerPolicyType.FIRST ||
                this.lastDtxn != dtxn ||
                this.lastIterator == null ||
                (this.ignore_speculation_type_change == false && this.lastSpecType != specType) ||
                (this.ignore_queue_size_change == false && this.lastSize != this.queue.size())) {
            this.lastIterator = this.queue.iterator();    
        }
        boolean resetIterator = true;
        if (profiler != null) profiler.queue_size.put(this.queue.size());
        boolean lastHasNext;
        if (trace.val) LOG.trace(StringUtil.header("BEGIN QUEUE CHECK :: " + dtxn));
        while ((lastHasNext = this.lastIterator.hasNext()) == true) {
            if (this.interrupted) {
                if (debug.val)
                    LOG.warn(String.format("Search interrupted after %d examinations [%s]",
                             examined_ctr, this.latchMsg.getSimpleName()));
                if (profiler != null) profiler.interrupts++;
                was_interrupted = true;
                break;
            }
            
            AbstractTransaction txn = this.lastIterator.next();
            assert(txn != null) : "Null transaction handle " + txn;
            boolean singlePartition = txn.isPredictSinglePartition();
            txn_ctr++;

            // Skip any distributed or non-local transactions
            if ((txn instanceof LocalTransaction) == false || singlePartition == false) {
                if (trace.val)
                    LOG.trace(String.format("Skipping non-speculative candidate %s", txn));
                continue;
            }
            LocalTransaction localTxn = (LocalTransaction)txn;
            
            // Skip anything already executed
            if (localTxn.isMarkExecuted()) {
                if (trace.val)
                    LOG.trace(String.format("Skipping %s because it was already executed", txn));
                continue;
            }

            // Let's check it out!
            if (profiler != null) profiler.compute_time.start();
            if (singlePartition == false) {
                if (trace.val)
                    LOG.trace(String.format("Skipping %s because it is not single-partitioned", localTxn));
                continue;
            }
            if (debug.val)
                LOG.debug(String.format("Examining whether %s conflicts with current dtxn", localTxn));
            examined_ctr++;
            try {
                switch (specType) {
                    // We can execute anything when we are in SP3 (i.e., 2PC) or IDLE
                    // For SP2, we can execute anything if the txn has not
                    // executed a query at this partition.
                    case IDLE:
                    case SP2_REMOTE_BEFORE:
                    case SP3_LOCAL:
                    case SP3_REMOTE: {
                        break;
                    }
                    // Otherwise we have to use the ConflictChecker to determine whether
                    // it is safe to execute this txn given what the distributed txn
                    // is expected to execute in the future.
                    case SP1_LOCAL:
                    case SP2_REMOTE_AFTER: {
                        if (this.checker.canExecute(dtxn, localTxn, this.partitionId) == false) {
                            if (debug.val)
                                LOG.debug(String.format("Skipping %s because it conflicts with current transaction", localTxn));
                            continue;
                        }
                        break;
                    }
                    // BUSTED!
                    default:
                        String msg = String.format("Unexpected %s.%s", specType.getClass().getSimpleName(), specType);
                        throw new RuntimeException(msg);
                } // SWITCH
                // If we get get to this point through the above switch statement, we know
                // that this txn is safe to execute now.
                matched_ctr++;
                
                // Scheduling Policy: FIRST MATCH
                if (this.policyType == SpecExecSchedulerPolicyType.FIRST) {
                    next = localTxn;
                    resetIterator = false;
                    break;
                }
                // Scheduling Policy: LAST MATCH
                else if (this.policyType == SpecExecSchedulerPolicyType.LAST) {
                    next = localTxn;
                }
                // Scheduling Policy: SHORTEST/LONGEST TIME
                else {
                    // Estimate the time that remains.
                    EstimatorState es = localTxn.getEstimatorState();
                    if (es != null) {
                        long remainingTime = es.getLastEstimate().getRemainingExecutionTime();
                        if ((this.policyType == SpecExecSchedulerPolicyType.SHORTEST && remainingTime < bestTime) ||
                            (this.policyType == SpecExecSchedulerPolicyType.LONGEST && remainingTime > bestTime)) {
                            bestTime = remainingTime;
                            next = localTxn;
                            if (debug.val)
                                LOG.debug(String.format("[%s %d/%d] New Match -> %s / remainingTime=%d",
                                          this.policyType, examined_ctr, this.windowSize, next, remainingTime));
                         }
                    }
                }
                // Stop if we've reached our window size
                if (examined_ctr == this.windowSize) break;
                
            } finally {
                if (profiler != null) profiler.compute_time.stop();
            }
        } // WHILE
        if (trace.val) LOG.trace(StringUtil.header("END QUEUE CHECK"));
        if (profiler != null) {
            profiler.num_comparisons.put(txn_ctr);
            profiler.num_matches.put(matched_ctr);
        }
        
        // We found somebody to execute right now!
        // Make sure that we set the speculative flag to true!
        if (next != null) {
            next.markReleased(this.partitionId);
            if (profiler != null) {
                this.profilerExecuteCounter.put(specType.ordinal());
                profiler.success++;
            }
            if (this.policyType == SpecExecSchedulerPolicyType.FIRST) {
                this.lastIterator.remove();
            } else {
                this.queue.remove(next);
            }
            if (debug.val)
                LOG.debug(dtxn + " - Found next non-conflicting speculative txn " + next);
        }
        else if (debug.val && this.queue.isEmpty() == false) {
            LOG.debug(String.format("Failed to find non-conflicting speculative txn " +
            		  "[dtxn=%s, txnCtr=%d, examinedCtr=%d, interrupted=%s]",
                      dtxn, txn_ctr, examined_ctr, was_interrupted));
        }
        
        this.lastDtxn = dtxn;
        this.lastSpecType = specType;
        if (resetIterator || lastHasNext == false) this.lastIterator = null;
        else if (this.ignore_queue_size_change == false) this.lastSize = this.queue.size();
        if (profiler != null) profiler.total_time.stop();
        return (next);
    }
    
    // ----------------------------------------------------------------------------
    // DEBUG METHODS
    // ----------------------------------------------------------------------------
    
    public class Debug implements DebugContext {
        public AbstractTransaction getLastDtxn() {
            return (lastDtxn);
        }
        public int getLastSize() {
            return (lastSize);
        }
        public Iterator<AbstractTransaction> getLastIterator() {
            return (lastIterator);
        }
        public SpeculationType getLastSpecType() {
            return (lastSpecType);
        }
        public SpecExecProfiler[] getProfilers() {
            return (profilerMap);
        }
        public SpecExecProfiler getProfiler(SpeculationType stype) {
            return (profilerMap[stype.ordinal()]);
        }
    } // CLASS
    
    private SpecExecScheduler.Debug cachedDebugContext;
    public SpecExecScheduler.Debug getDebugContext() {
        if (this.cachedDebugContext == null) {
            // We don't care if we're thread-safe here...
            this.cachedDebugContext = new SpecExecScheduler.Debug();
        }
        return this.cachedDebugContext;
    }
}
