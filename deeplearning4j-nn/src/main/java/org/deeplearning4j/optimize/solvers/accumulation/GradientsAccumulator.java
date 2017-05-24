package org.deeplearning4j.optimize.solvers.accumulation;

import lombok.NonNull;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.Serializable;
import java.util.Queue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class provides accumulation for gradients for both input (i.e. updates coming from network) and output (comint from one ore more models training at the same time)
 *
 * @author raver119@gmail.com
 */
public class GradientsAccumulator implements Serializable {

    protected MessageHandler handler;

    // here we'll store messages coming from "somewhere else"
    protected transient Queue<INDArray> gradients;

    // this field stores current accumulated
    protected transient INDArray storage;

    // this counter tracks number of messages generated by this accumulation
    protected transient AtomicLong ownCounter = new AtomicLong(0);

    // this counter tracks number of messages received from somewhere
    protected transient AtomicLong extCounter = new AtomicLong(0);

    /**
     * Creates new GradientsAccumulator with starting threshold of 1e-3
     */
    public GradientsAccumulator() {
        this(new LocalHandler());
    }

    /**
     * Creates new GradientsAccumulator with custom starting threshold
     *
     * @param handler MessageHandler instance that'll be used for communication purposes
     */
    public GradientsAccumulator(@NonNull MessageHandler handler) {
        this.gradients = new LinkedTransferQueue<>();
        this.handler = handler;

        this.handler.initialize(this);
    }

    public INDArray getUpdate() {
        // FIXME: this is wrong
        return gradients.poll();
    }

    /**
     * This method accepts updates suitable for StepFunction, and accumulates/propagates it across all workers
     *
     * @param array
     */
    // TODO: this method should be synchronized probably, if we want to call this method from different threads
    public synchronized void storeUpdate(INDArray array) {
        /*
            Here we want to do 4 things:
            1) update accumulated values
            2) invoke extractor, that'll (optionally) pull all updates above threshold
            3) ???
            4) PROFIT!
         */

        // if accum is null, let's just create it here
        if (storage == null) {
            // we don't want state array to be attached to any workspace
            try (MemoryWorkspace workspace = Nd4j.getMemoryManager().scopeOutOfWorkspaces()) {
                storage = Nd4j.create(array.shape(), array.ordering());
            }
        }

        // accumulate our values
        storage.addi(array);

        // if there's something to send - send it. Skip otherwise!!!
        if (handler.broadcastUpdates(storage)) {
            ownCounter.getAndIncrement();
        }
    }


    /**
     * This method accepts updates suitable for StepFunction and puts them to the queue, which is used in backpropagation loop
     *
     * @param array
     */
    public void receiveUpdate(INDArray array) {
        extCounter.getAndIncrement();

        // TODO: we need to replicate array here, wrt number of consumers. separate queues maybe? MQ-style?
        gradients.add(array);
    }


    /**
     * This method resets all accumulated updates (if any)
     */
    public void reset() {
        if (storage != null)
            storage.assign(0.0f);
    }
}
