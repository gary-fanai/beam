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

package org.apache.beam.runners.fnexecution.control;

import static com.google.common.base.Preconditions.checkState;
import static org.apache.beam.runners.core.construction.SyntheticComponents.uniqueId;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.beam.model.fnexecution.v1.BeamFnApi;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.ProcessBundleDescriptor;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.RemoteGrpcPort;
import org.apache.beam.model.fnexecution.v1.BeamFnApi.Target;
import org.apache.beam.model.pipeline.v1.Endpoints.ApiServiceDescriptor;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.model.pipeline.v1.RunnerApi.Components;
import org.apache.beam.model.pipeline.v1.RunnerApi.PCollection;
import org.apache.beam.model.pipeline.v1.RunnerApi.PTransform;
import org.apache.beam.runners.core.construction.graph.ExecutableStage;
import org.apache.beam.runners.core.construction.graph.PipelineNode.PCollectionNode;
import org.apache.beam.runners.core.construction.graph.PipelineNode.PTransformNode;
import org.apache.beam.runners.core.construction.graph.SideInputReference;
import org.apache.beam.runners.core.construction.graph.UserStateReference;
import org.apache.beam.runners.fnexecution.data.RemoteInputDestination;
import org.apache.beam.runners.fnexecution.wire.LengthPrefixUnknownCoders;
import org.apache.beam.runners.fnexecution.wire.WireCoders;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.fn.data.RemoteGrpcPortRead;
import org.apache.beam.sdk.fn.data.RemoteGrpcPortWrite;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowedValue.FullWindowedValueCoder;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.vendor.protobuf.v3.com.google.protobuf.InvalidProtocolBufferException;
import org.apache.beam.vendor.sdk.v2.sdk.extensions.protobuf.ByteStringCoder;

/** Utility methods for creating {@link ProcessBundleDescriptor} instances. */
// TODO: Rename to ExecutableStages?
public class ProcessBundleDescriptors {

  /**
   * Note that the {@link ProcessBundleDescriptor} is constructed by:
   *
   * <ul>
   *   <li>Adding gRPC read and write nodes wiring them to the specified data endpoint.
   *   <li>Setting the state {@link ApiServiceDescriptor} to the specified state endpoint.
   *   <li>Modifying the coder on PCollections that are accessed as side inputs to be length
   *       prefixed making them binary compatible with the coder chosen when that side input is
   *       materialized.
   * </ul>
   */
  public static ExecutableProcessBundleDescriptor fromExecutableStage(
      String id,
      ExecutableStage stage,
      ApiServiceDescriptor dataEndpoint,
      ApiServiceDescriptor stateEndpoint)
      throws IOException {
    checkState(id != null, "id must be specified.");
    checkState(stage != null, "stage must be specified.");
    checkState(dataEndpoint != null, "dataEndpoint must be specified.");
    checkState(stateEndpoint != null, "stateEndpoint must be specified.");
    return fromExecutableStageInternal(id, stage, dataEndpoint, stateEndpoint);
  }

  public static ExecutableProcessBundleDescriptor fromExecutableStage(
      String id, ExecutableStage stage, ApiServiceDescriptor dataEndpoint) throws IOException {
    checkState(id != null, "id must be specified.");
    checkState(stage != null, "stage must be specified.");
    checkState(dataEndpoint != null, "dateEndpoint must be specified.");
    return fromExecutableStageInternal(id, stage, dataEndpoint, null);
  }

  private static ExecutableProcessBundleDescriptor fromExecutableStageInternal(
      String id,
      ExecutableStage stage,
      ApiServiceDescriptor dataEndpoint,
      @Nullable ApiServiceDescriptor stateEndpoint)
      throws IOException {
    // Create with all of the processing transforms, and all of the components.
    // TODO: Remove the unreachable subcomponents if the size of the descriptor matters.
    Map<String, PTransform> stageTransforms =
        stage
            .getTransforms()
            .stream()
            .collect(Collectors.toMap(PTransformNode::getId, PTransformNode::getTransform));

    Components.Builder components =
        stage.getComponents().toBuilder().clearTransforms().putAllTransforms(stageTransforms);

    // The order of these 3 does not matter.
    RemoteInputDestination<WindowedValue<?>> inputDestination =
        addStageInput(dataEndpoint, stage.getInputPCollection(), components);

    Map<Target, Coder<WindowedValue<?>>> outputTargetCoders =
        addStageOutputs(dataEndpoint, stage.getOutputPCollections(), components);

    Map<String, Map<String, SideInputSpec>> sideInputSpecs = addSideInputs(stage, components);

    // Copy data from components to ProcessBundleDescriptor.
    ProcessBundleDescriptor.Builder bundleDescriptorBuilder =
        ProcessBundleDescriptor.newBuilder().setId(id);
    if (stateEndpoint != null) {
      bundleDescriptorBuilder.setStateApiServiceDescriptor(stateEndpoint);
    }
    bundleDescriptorBuilder
        .putAllCoders(components.getCodersMap())
        .putAllEnvironments(components.getEnvironmentsMap())
        .putAllPcollections(components.getPcollectionsMap())
        .putAllWindowingStrategies(components.getWindowingStrategiesMap())
        .putAllTransforms(components.getTransformsMap());

    Map<String, Map<String, BagUserStateSpec>> bagUserStateSpecs =
        forBagUserStates(stage, components.build());

    return ExecutableProcessBundleDescriptor.of(
        bundleDescriptorBuilder.build(),
        inputDestination,
        outputTargetCoders,
        sideInputSpecs,
        bagUserStateSpecs);
  }

  private static Map<Target, Coder<WindowedValue<?>>> addStageOutputs(
      ApiServiceDescriptor dataEndpoint,
      Collection<PCollectionNode> outputPCollections,
      Components.Builder components)
      throws IOException {
    Map<Target, Coder<WindowedValue<?>>> outputTargetCoders = new LinkedHashMap<>();
    for (PCollectionNode outputPCollection : outputPCollections) {
      TargetEncoding targetEncoding = addStageOutput(dataEndpoint, components, outputPCollection);
      outputTargetCoders.put(targetEncoding.getTarget(), targetEncoding.getCoder());
    }
    return outputTargetCoders;
  }

  private static RemoteInputDestination<WindowedValue<?>> addStageInput(
      ApiServiceDescriptor dataEndpoint,
      PCollectionNode inputPCollection,
      Components.Builder components)
      throws IOException {
    String inputWireCoderId = WireCoders.addSdkWireCoder(inputPCollection, components);
    @SuppressWarnings("unchecked")
    Coder<WindowedValue<?>> wireCoder =
        (Coder) WireCoders.instantiateRunnerWireCoder(inputPCollection, components.build());

    RemoteGrpcPort inputPort =
        RemoteGrpcPort.newBuilder()
            .setApiServiceDescriptor(dataEndpoint)
            .setCoderId(inputWireCoderId)
            .build();
    String inputId =
        uniqueId(
            String.format("fn/read/%s", inputPCollection.getId()), components::containsTransforms);
    PTransform inputTransform =
        RemoteGrpcPortRead.readFromPort(inputPort, inputPCollection.getId()).toPTransform();
    components.putTransforms(inputId, inputTransform);
    return RemoteInputDestination.of(
        wireCoder,
        Target.newBuilder()
            .setPrimitiveTransformReference(inputId)
            .setName(Iterables.getOnlyElement(inputTransform.getOutputsMap().keySet()))
            .build());
  }

  private static TargetEncoding addStageOutput(
      ApiServiceDescriptor dataEndpoint,
      Components.Builder components,
      PCollectionNode outputPCollection)
      throws IOException {
    String outputWireCoderId = WireCoders.addSdkWireCoder(outputPCollection, components);
    @SuppressWarnings("unchecked")
    Coder<WindowedValue<?>> wireCoder =
        (Coder) WireCoders.instantiateRunnerWireCoder(outputPCollection, components.build());
    RemoteGrpcPort outputPort =
        RemoteGrpcPort.newBuilder()
            .setApiServiceDescriptor(dataEndpoint)
            .setCoderId(outputWireCoderId)
            .build();
    RemoteGrpcPortWrite outputWrite =
        RemoteGrpcPortWrite.writeToPort(outputPCollection.getId(), outputPort);
    String outputId =
        uniqueId(
            String.format("fn/write/%s", outputPCollection.getId()),
            components::containsTransforms);
    PTransform outputTransform = outputWrite.toPTransform();
    components.putTransforms(outputId, outputTransform);
    return new AutoValue_ProcessBundleDescriptors_TargetEncoding(
        Target.newBuilder()
            .setPrimitiveTransformReference(outputId)
            .setName(Iterables.getOnlyElement(outputTransform.getInputsMap().keySet()))
            .build(),
        wireCoder);
  }

  public static Map<String, Map<String, SideInputSpec>> getSideInputs(ExecutableStage stage)
      throws IOException {
    return addSideInputs(stage, stage.getComponents().toBuilder());
  }

  private static Map<String, Map<String, SideInputSpec>> addSideInputs(
      ExecutableStage stage, Components.Builder components) throws IOException {
    ImmutableTable.Builder<String, String, SideInputSpec> idsToSpec = ImmutableTable.builder();
    for (SideInputReference sideInputReference : stage.getSideInputs()) {
      // Update the coder specification for side inputs to be length prefixed so that the
      // SDK and Runner agree on how to encode/decode the key, window, and values for
      // side inputs.
      PCollectionNode pcNode = sideInputReference.collection();
      PCollection pc = pcNode.getPCollection();
      String lengthPrefixedCoderId =
          LengthPrefixUnknownCoders.addLengthPrefixedCoder(pc.getCoderId(), components, false);
      components.putPcollections(
          pcNode.getId(), pc.toBuilder().setCoderId(lengthPrefixedCoderId).build());

      FullWindowedValueCoder<KV<?, ?>> coder =
          (FullWindowedValueCoder)
              WireCoders.instantiateRunnerWireCoder(pcNode, components.build());
      idsToSpec.put(
          sideInputReference.transform().getId(),
          sideInputReference.localName(),
          SideInputSpec.of(
              sideInputReference.transform().getId(),
              sideInputReference.localName(),
              getAccessPattern(sideInputReference),
              coder.getValueCoder(),
              coder.getWindowCoder()));
    }
    return idsToSpec.build().rowMap();
  }

  private static RunnerApi.FunctionSpec getAccessPattern(SideInputReference sideInputReference) {
    try {
      return RunnerApi.ParDoPayload.parseFrom(
              sideInputReference.transform().getTransform().getSpec().getPayload())
          .getSideInputsMap()
          .get(sideInputReference.localName())
          .getAccessPattern();
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }

  private static Map<String, Map<String, BagUserStateSpec>> forBagUserStates(
      ExecutableStage stage, Components components) throws IOException {
    ImmutableTable.Builder<String, String, BagUserStateSpec> idsToSpec = ImmutableTable.builder();
    for (UserStateReference userStateReference : stage.getUserStates()) {
      FullWindowedValueCoder<KV<?, ?>> coder =
          (FullWindowedValueCoder)
              WireCoders.instantiateRunnerWireCoder(userStateReference.collection(), components);
      idsToSpec.put(
          userStateReference.transform().getId(),
          userStateReference.localName(),
          BagUserStateSpec.of(
              userStateReference.transform().getId(),
              userStateReference.localName(),
              // We use the ByteString coder to save on encoding and decoding the actual key.
              ByteStringCoder.of(),
              // Usage of the ByteStringCoder provides a significant simplification for handling
              // a logical stream of values by not needing to know where the element boundaries
              // actually are. See StateRequestHandlers.java for further details.
              ByteStringCoder.of(),
              coder.getWindowCoder()));
    }
    return idsToSpec.build().rowMap();
  }

  @AutoValue
  abstract static class TargetEncoding {
    abstract BeamFnApi.Target getTarget();

    abstract Coder<WindowedValue<?>> getCoder();
  }

  /**
   * A container type storing references to the key, value, and window {@link Coder} used when
   * handling side input state requests.
   */
  @AutoValue
  public abstract static class SideInputSpec<K, T, W extends BoundedWindow> {
    public static <T, W extends BoundedWindow> SideInputSpec of(
        String transformId,
        String sideInputId,
        RunnerApi.FunctionSpec accessPattern,
        Coder<T> elementCoder,
        Coder<W> windowCoder) {
      return new AutoValue_ProcessBundleDescriptors_SideInputSpec(
          transformId, sideInputId, accessPattern, elementCoder, windowCoder);
    }

    public abstract String transformId();

    public abstract String sideInputId();

    public abstract RunnerApi.FunctionSpec accessPattern();

    public abstract Coder<T> elementCoder();

    public abstract Coder<W> windowCoder();
  }

  /**
   * A container type storing references to the key, value, and window {@link Coder} used when
   * handling bag user state requests.
   */
  @AutoValue
  public abstract static class BagUserStateSpec<K, V, W extends BoundedWindow> {
    static <K, V, W extends BoundedWindow> BagUserStateSpec<K, V, W> of(
        String transformId,
        String userStateId,
        Coder<K> keyCoder,
        Coder<V> valueCoder,
        Coder<W> windowCoder) {
      return new AutoValue_ProcessBundleDescriptors_BagUserStateSpec(
          transformId, userStateId, keyCoder, valueCoder, windowCoder);
    }

    public abstract String transformId();

    public abstract String userStateId();

    public abstract Coder<K> keyCoder();

    public abstract Coder<V> valueCoder();

    public abstract Coder<W> windowCoder();
  }

  /** */
  @AutoValue
  public abstract static class ExecutableProcessBundleDescriptor {
    public static ExecutableProcessBundleDescriptor of(
        ProcessBundleDescriptor descriptor,
        RemoteInputDestination<WindowedValue<?>> inputDestination,
        Map<BeamFnApi.Target, Coder<WindowedValue<?>>> outputTargetCoders,
        Map<String, Map<String, SideInputSpec>> sideInputSpecs,
        Map<String, Map<String, BagUserStateSpec>> bagUserStateSpecs) {
      ImmutableTable.Builder copyOfSideInputSpecs = ImmutableTable.builder();
      for (Map.Entry<String, Map<String, SideInputSpec>> outer : sideInputSpecs.entrySet()) {
        for (Map.Entry<String, SideInputSpec> inner : outer.getValue().entrySet()) {
          copyOfSideInputSpecs.put(outer.getKey(), inner.getKey(), inner.getValue());
        }
      }
      ImmutableTable.Builder copyOfBagUserStateSpecs = ImmutableTable.builder();
      for (Map.Entry<String, Map<String, BagUserStateSpec>> outer : bagUserStateSpecs.entrySet()) {
        for (Map.Entry<String, BagUserStateSpec> inner : outer.getValue().entrySet()) {
          copyOfBagUserStateSpecs.put(outer.getKey(), inner.getKey(), inner.getValue());
        }
      }
      return new AutoValue_ProcessBundleDescriptors_ExecutableProcessBundleDescriptor(
          descriptor,
          inputDestination,
          Collections.unmodifiableMap(outputTargetCoders),
          copyOfSideInputSpecs.build().rowMap(),
          copyOfBagUserStateSpecs.build().rowMap());
    }

    public abstract ProcessBundleDescriptor getProcessBundleDescriptor();

    /**
     * Get the {@link RemoteInputDestination} that input data are sent to the {@link
     * ProcessBundleDescriptor} over.
     */
    public abstract RemoteInputDestination<WindowedValue<?>> getRemoteInputDestination();

    /**
     * Get all of the targets materialized by this {@link ExecutableProcessBundleDescriptor} and the
     * java {@link Coder} for the wire format of that {@link BeamFnApi.Target}.
     */
    public abstract Map<BeamFnApi.Target, Coder<WindowedValue<?>>> getOutputTargetCoders();

    /**
     * Get a mapping from PTransform id to side input id to {@link SideInputSpec side inputs} that
     * are used during execution.
     */
    public abstract Map<String, Map<String, SideInputSpec>> getSideInputSpecs();

    /**
     * Get a mapping from PTransform id to user state input id to {@link BagUserStateSpec bag user
     * states} that are used during execution.
     */
    public abstract Map<String, Map<String, BagUserStateSpec>> getBagUserStateSpecs();
  }
}
