/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.io;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.TaskInfo;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.memory.ManagedMemoryUseCase;
import org.apache.flink.metrics.Counter;
import org.apache.flink.runtime.checkpoint.InflightDataRescalingDescriptor;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.runtime.metrics.groups.TaskIOMetricGroup;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.BoundedMultiInput;
import org.apache.flink.streaming.api.operators.InputSelectable;
import org.apache.flink.streaming.api.operators.TwoInputStreamOperator;
import org.apache.flink.streaming.api.operators.sort.MultiInputSortingDataInput;
import org.apache.flink.streaming.api.operators.sort.MultiInputSortingDataInput.SelectableSortingInputs;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.io.checkpointing.CheckpointedInputGate;
import org.apache.flink.streaming.runtime.metrics.WatermarkGauge;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StatusWatermarkValve;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatus;
import org.apache.flink.util.function.ThrowingConsumer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.apache.flink.streaming.api.graph.StreamConfig.requiresSorting;
import static org.apache.flink.util.Preconditions.checkNotNull;

/** A factory to create {@link StreamMultipleInputProcessor} for two input case. */
public class StreamTwoInputProcessorFactory {
    public static <IN1, IN2> StreamMultipleInputProcessor create(
            AbstractInvokable ownerTask,
            CheckpointedInputGate[] checkpointedInputGates,
            IOManager ioManager,
            MemoryManager memoryManager,
            TaskIOMetricGroup taskIOMetricGroup,
            TwoInputStreamOperator<IN1, IN2, ?> streamOperator,
            WatermarkGauge input1WatermarkGauge,
            WatermarkGauge input2WatermarkGauge,
            BoundedMultiInput endOfInputAware,
            StreamConfig streamConfig,
            Configuration taskManagerConfig,
            Configuration jobConfig,
            ExecutionConfig executionConfig,
            ClassLoader userClassloader,
            Counter numRecordsIn,
            InflightDataRescalingDescriptor inflightDataRescalingDescriptor,
            Function<Integer, StreamPartitioner<?>> gatePartitioners,
            TaskInfo taskInfo) {

        checkNotNull(endOfInputAware);

        taskIOMetricGroup.reuseRecordsInputCounter(numRecordsIn);
        TypeSerializer<IN1> typeSerializer1 = streamConfig.getTypeSerializerIn(0, userClassloader);
        StreamTaskInput<IN1> input1 =
                StreamTaskNetworkInputFactory.create(
                        checkpointedInputGates[0],
                        typeSerializer1,
                        ioManager,
                        new StatusWatermarkValve(
                                checkpointedInputGates[0].getNumberOfInputChannels()),
                        0,
                        inflightDataRescalingDescriptor,
                        gatePartitioners,
                        taskInfo);
        TypeSerializer<IN2> typeSerializer2 = streamConfig.getTypeSerializerIn(1, userClassloader);
        StreamTaskInput<IN2> input2 =
                StreamTaskNetworkInputFactory.create(
                        checkpointedInputGates[1],
                        typeSerializer2,
                        ioManager,
                        new StatusWatermarkValve(
                                checkpointedInputGates[1].getNumberOfInputChannels()),
                        1,
                        inflightDataRescalingDescriptor,
                        gatePartitioners,
                        taskInfo);

        InputSelectable inputSelectable =
                streamOperator instanceof InputSelectable ? (InputSelectable) streamOperator : null;

        // this is a bit verbose because we're manually handling input1 and input2
        // TODO: extract method
        StreamConfig.InputConfig[] inputConfigs = streamConfig.getInputs(userClassloader);
        boolean input1IsSorted = requiresSorting(inputConfigs[0]);
        boolean input2IsSorted = requiresSorting(inputConfigs[1]);

        if (input1IsSorted || input2IsSorted) {
            // as soon as one input requires sorting we need to treat all inputs differently, to
            // make sure that pass-through inputs have precedence

            if (inputSelectable != null) {
                throw new IllegalStateException(
                        "The InputSelectable interface is not supported with sorting inputs");
            }

            List<StreamTaskInput<?>> sortedTaskInputs = new ArrayList<>();
            List<KeySelector<?, ?>> keySelectors = new ArrayList<>();
            List<StreamTaskInput<?>> passThroughTaskInputs = new ArrayList<>();
            if (input1IsSorted) {
                sortedTaskInputs.add(input1);
                keySelectors.add(streamConfig.getStatePartitioner(0, userClassloader));
            } else {
                passThroughTaskInputs.add(input1);
            }
            if (input2IsSorted) {
                sortedTaskInputs.add(input2);
                keySelectors.add(streamConfig.getStatePartitioner(1, userClassloader));
            } else {
                passThroughTaskInputs.add(input2);
            }

            @SuppressWarnings("unchecked")
            SelectableSortingInputs selectableSortingInputs =
                    MultiInputSortingDataInput.wrapInputs(
                            ownerTask,
                            sortedTaskInputs.toArray(new StreamTaskInput[0]),
                            keySelectors.toArray(new KeySelector[0]),
                            new TypeSerializer[] {typeSerializer1, typeSerializer2},
                            streamConfig.getStateKeySerializer(userClassloader),
                            passThroughTaskInputs.toArray(new StreamTaskInput[0]),
                            memoryManager,
                            ioManager,
                            executionConfig.isObjectReuseEnabled(),
                            streamConfig.getManagedMemoryFractionOperatorUseCaseOfSlot(
                                    ManagedMemoryUseCase.OPERATOR,
                                    taskManagerConfig,
                                    userClassloader),
                            jobConfig);
            inputSelectable = selectableSortingInputs.getInputSelectable();
            StreamTaskInput<?>[] sortedInputs = selectableSortingInputs.getSortedInputs();
            StreamTaskInput<?>[] passThroughInputs = selectableSortingInputs.getPassThroughInputs();
            if (input1IsSorted) {
                input1 = toTypedInput(sortedInputs[0]);
            } else {
                input1 = toTypedInput(passThroughInputs[0]);
            }
            if (input2IsSorted) {
                input2 = toTypedInput(sortedInputs[sortedInputs.length - 1]);
            } else {
                input2 = toTypedInput(passThroughInputs[passThroughInputs.length - 1]);
            }
        }

        StreamTaskNetworkOutput<IN1> output1 =
                new StreamTaskNetworkOutput<>(
                        streamOperator,
                        record -> processRecord1(record, streamOperator),
                        input1WatermarkGauge,
                        0,
                        numRecordsIn);
        StreamOneInputProcessor<IN1> processor1 =
                new StreamOneInputProcessor<>(input1, output1, endOfInputAware);

        StreamTaskNetworkOutput<IN2> output2 =
                new StreamTaskNetworkOutput<>(
                        streamOperator,
                        record -> processRecord2(record, streamOperator),
                        input2WatermarkGauge,
                        1,
                        numRecordsIn);
        StreamOneInputProcessor<IN2> processor2 =
                new StreamOneInputProcessor<>(input2, output2, endOfInputAware);

        return new StreamMultipleInputProcessor(
                new MultipleInputSelectionHandler(inputSelectable, 2),
                new StreamOneInputProcessor[] {processor1, processor2});
    }

    @SuppressWarnings("unchecked")
    private static <IN1> StreamTaskInput<IN1> toTypedInput(StreamTaskInput<?> multiInput) {
        return (StreamTaskInput<IN1>) multiInput;
    }

    private static <T> void processRecord1(
            StreamRecord<T> record, TwoInputStreamOperator<T, ?, ?> streamOperator)
            throws Exception {

        streamOperator.setKeyContextElement1(record);
        streamOperator.processElement1(record);
    }

    private static <T> void processRecord2(
            StreamRecord<T> record, TwoInputStreamOperator<?, T, ?> streamOperator)
            throws Exception {

        streamOperator.setKeyContextElement2(record);
        streamOperator.processElement2(record);
    }

    /**
     * The network data output implementation used for processing stream elements from {@link
     * StreamTaskNetworkInput} in two input selective processor.
     */
    private static class StreamTaskNetworkOutput<T> implements PushingAsyncDataInput.DataOutput<T> {

        private final TwoInputStreamOperator<?, ?, ?> operator;

        /** The function way is only used for frequent record processing as for JIT optimization. */
        private final ThrowingConsumer<StreamRecord<T>, Exception> recordConsumer;

        private final WatermarkGauge inputWatermarkGauge;

        /** The input index to indicate how to process elements by two input operator. */
        private final int inputIndex;

        private final Counter numRecordsIn;

        private StreamTaskNetworkOutput(
                TwoInputStreamOperator<?, ?, ?> operator,
                ThrowingConsumer<StreamRecord<T>, Exception> recordConsumer,
                WatermarkGauge inputWatermarkGauge,
                int inputIndex,
                Counter numRecordsIn) {
            this.operator = checkNotNull(operator);
            this.recordConsumer = checkNotNull(recordConsumer);
            this.inputWatermarkGauge = checkNotNull(inputWatermarkGauge);
            this.inputIndex = inputIndex;
            this.numRecordsIn = numRecordsIn;
        }

        @Override
        public void emitRecord(StreamRecord<T> record) throws Exception {
            numRecordsIn.inc();
            recordConsumer.accept(record);
        }

        @Override
        public void emitWatermark(Watermark watermark) throws Exception {
            inputWatermarkGauge.setCurrentWatermark(watermark.getTimestamp());
            if (inputIndex == 0) {
                operator.processWatermark1(watermark);
            } else {
                operator.processWatermark2(watermark);
            }
        }

        @Override
        public void emitStreamStatus(StreamStatus streamStatus) throws Exception {
            if (inputIndex == 0) {
                operator.processStreamStatus1(streamStatus);
            } else {
                operator.processStreamStatus2(streamStatus);
            }
        }

        @Override
        public void emitLatencyMarker(LatencyMarker latencyMarker) throws Exception {
            if (inputIndex == 0) {
                operator.processLatencyMarker1(latencyMarker);
            } else {
                operator.processLatencyMarker2(latencyMarker);
            }
        }
    }
}
