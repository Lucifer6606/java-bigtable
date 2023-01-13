/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigtable.data.v2.stub.metrics;

import com.google.api.core.ApiFuture;

import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.google.api.core.ApiFutures;
import com.google.api.gax.grpc.GrpcCallContext;
import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.ApiCallContext;
import com.google.api.gax.rpc.ClientContext;
import com.google.api.gax.rpc.DeadlineExceededException;
import com.google.api.gax.rpc.UnaryCallable;
import com.google.bigtable.v2.BigtableGrpc;
import com.google.bigtable.v2.MutateRowsRequest;
import com.google.bigtable.v2.MutateRowsResponse;
import com.google.bigtable.v2.MutateRowsResponse.Entry;
import com.google.bigtable.v2.ResponseParams;
import com.google.cloud.bigtable.data.v2.BigtableDataSettings;
import com.google.cloud.bigtable.data.v2.FakeServiceBuilder;
import com.google.cloud.bigtable.data.v2.models.BulkMutation;
import com.google.cloud.bigtable.data.v2.models.Mutation;
import com.google.cloud.bigtable.data.v2.stub.EnhancedBigtableStub;
import com.google.cloud.bigtable.data.v2.stub.EnhancedBigtableStubSettings;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.RateLimiter;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class RateLimitingCallableTest {

  private static final String PROJECT_ID = "fake-project";
  private static final String INSTANCE_ID = "fake-instance";
  private static final String APP_PROFILE_ID = "default";
  private static final String TABLE_ID = "fake-table";
  private static final String ZONE = "us-east1";
  private static final String CLUSTER = "cluster";

  private static final String FAKE_LOW_CPU_VALUES = "40.1,10.1,36.2";
  private static final String FAKE_HIGH_CPU_VALUES = "90.1,80.1,76.2";

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();
  private RateLimitingStats stats;

  private final FakeService FakeService = new FakeService();
  private Server lowCPUServer;
  private Server highCPUServer;

  private EnhancedBigtableStub lowCpuStub;
  private EnhancedBigtableStub highCpuStub;

  private MockMutateInnerCallable innerCallable; // I believe that I can delete this
  private ApiCallContext callContext;

  // Should I do Stats or limiter?
  @Captor
  private ArgumentCaptor<RateLimiter> limiterArgumentCaptor; // I feel like the captor shuold be a string

  @Captor private ArgumentCaptor<Double> rate;



  @Mock RateLimiter mockLimiter;

  @Mock private UnaryCallable<BulkMutation, Void> mockBulkMutateRowsCallable;


  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);

    limiterArgumentCaptor = ArgumentCaptor.forClass(RateLimiter.class);
    rate = ArgumentCaptor.forClass(Double.class);
    innerCallable = new MockMutateInnerCallable();
    callContext = GrpcCallContext.createDefault();

    ServerInterceptor lowCPUInterceptor = cpuReturningIntercepter(FAKE_LOW_CPU_VALUES);
    ServerInterceptor highCPUInterceptor = cpuReturningIntercepter(FAKE_HIGH_CPU_VALUES);

    lowCPUServer = FakeServiceBuilder.create(FakeService).intercept(lowCPUInterceptor).start();
    highCPUServer = FakeServiceBuilder.create(FakeService).intercept(highCPUInterceptor).start();

    BigtableDataSettings lowCPUSettings =
        BigtableDataSettings.newBuilderForEmulator(lowCPUServer.getPort())
            .enableBatchMutationCpuBasedThrottling()
            .setProjectId(PROJECT_ID)
            .setInstanceId(INSTANCE_ID)
            .setAppProfileId(APP_PROFILE_ID)
            .build();

    BigtableDataSettings highCPUSettings =
        BigtableDataSettings.newBuilderForEmulator(highCPUServer.getPort())
            .enableBatchMutationCpuBasedThrottling()
            .setProjectId(PROJECT_ID)
            .setInstanceId(INSTANCE_ID)
            .setAppProfileId(APP_PROFILE_ID)
            .build();

    // Fix naming
    EnhancedBigtableStubSettings lowCPUStubSettings = lowCPUSettings.getStubSettings();
    EnhancedBigtableStubSettings highCPUStubSettings = highCPUSettings.getStubSettings();
    lowCpuStub = new EnhancedBigtableStub(lowCPUStubSettings, ClientContext.create(lowCPUStubSettings)/*, statsArgumentCaptor.capture()*/);
    highCpuStub = new EnhancedBigtableStub(highCPUStubSettings, ClientContext.create(highCPUStubSettings)/*, statsArgumentCaptor.capture()*/);
    stats = new RateLimitingStats();
  }

  @After
  public void tearDown() {
    lowCpuStub.close();
    lowCPUServer.shutdown();
    highCpuStub.close();
    highCPUServer.shutdown();
  }

  @Test
  public void testBulkMutateRowsWithNoChangeInRateLimiting() {
    Mockito.when(mockLimiter.acquire()).thenReturn(1.0); // Correct?
    Mockito.when(mockLimiter.getRate()).thenReturn(100.0);
    //doNothing().when(mockLimiter).setRate(isA(Double.class)); // works?
    //Mockito.when(mockLimiter.)

    BulkMutation mutations = BulkMutation.create(TABLE_ID).add("fake-row", Mutation.create()
        .setCell("cf","qual","value"));

    // Going to be changing the request
    // Need to get request submitted and pass in value through request
    MutateRowsRequest request =
        MutateRowsRequest.newBuilder().addEntries(MutateRowsRequest.Entry.getDefaultInstance()).build();

    ApiFuture<Void> future =
        lowCpuStub.bulkMutateRowsCallableTest(mockLimiter).futureCall(mutations, callContext);

    //future.get();

    Mockito.verify(mockLimiter, Mockito.times(2)).setRate(rate.capture());
  }

  private static class FakeService extends BigtableGrpc.BigtableImplBase {
    Queue<Exception> expectations = Queues.newArrayDeque();

    static List<MutateRowsResponse> createFakeMutateRowsResponse() {
      List<MutateRowsResponse> responses = new ArrayList<>();

      for (int i = 0; i < 1; i++) {
        ArrayList<MutateRowsResponse.Entry> entries = new ArrayList<>();
        entries.add(
            Entry.newBuilder().setIndex(0).setStatus(com.google.rpc.Status.newBuilder().setCode(0).build()).build()); // Definitely a better way to do this

        responses.add(
            MutateRowsResponse.newBuilder().addAllEntries(
                entries
            ).build());
      }

      return responses;
    }

    @Override
    public void mutateRows(
        MutateRowsRequest request, StreamObserver<MutateRowsResponse> responseObserver) {
      responseObserver.onNext(createFakeMutateRowsResponse().get(0));
      responseObserver.onCompleted();
    }
  }

  private ServerInterceptor cpuReturningIntercepter(String cpuValues) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> serverCall,
          Metadata metadata,
          ServerCallHandler<ReqT, RespT> serverCallHandler) {
        return serverCallHandler.startCall(
            new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(serverCall) {
              @Override
              public void sendHeaders(Metadata headers) {
                // Set CPU values
                headers.put(Metadata.Key.of(
                        "bigtable-cpu-values", Metadata.ASCII_STRING_MARSHALLER),
                    cpuValues);

                ResponseParams params =
                    ResponseParams.newBuilder().setZoneId(ZONE).setClusterId(CLUSTER) // What do I need to set?
                        .build();
                byte[] byteArray = params.toByteArray();
                headers.put(Util.LOCATION_METADATA_KEY, byteArray); // Is this needed?

                super.sendHeaders(headers);
              }
            },
            metadata);
      }
    };
  }

  static class MockMutateInnerCallable
      extends UnaryCallable<MutateRowsRequest, List<MutateRowsResponse>> {
    List<MutateRowsResponse> response = Lists.newArrayList();

    MutateRowsRequest lastRequest;
    ApiCallContext lastContext;

    @Override
    public ApiFuture<List<MutateRowsResponse>> futureCall(
        MutateRowsRequest request, ApiCallContext context) {
      lastRequest = request;
      lastContext = context;

      return ApiFutures.immediateFuture(response);
    }
  }
}
