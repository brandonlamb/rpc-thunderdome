package io.netifi.thunderdome.grpc.nonblocking.rs;

import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netifi.testing.protobuf.SimpleRequest;
import io.netifi.testing.protobuf.SimpleResponse;
import io.netifi.testing.protobuf.SimpleServiceGrpc;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.HdrHistogram.Histogram;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

public class GrpcRequestReplyClient {
  public static void main(String... args) throws Exception {
    int warmup = 1_000_000;
    int count = 1_000_000;
    String host = System.getProperty("host", "127.0.0.1");
    int port = Integer.getInteger("port", 8001);
    int concurrency = Integer.getInteger("concurrency", 128);

    ThreadFactory tf = new DefaultThreadFactory("client-elg-", true /*daemon */);
    NioEventLoopGroup worker = new NioEventLoopGroup(0, tf);
    ExecutorService executor = worker;

    ManagedChannel managedChannel =
        NettyChannelBuilder.forAddress(host, port)
            .eventLoopGroup(worker)
            .channelType(NioSocketChannel.class)
            .directExecutor()
            .flowControlWindow(NettyChannelBuilder.DEFAULT_FLOW_CONTROL_WINDOW)
            .usePlaintext(true)
            .build();

    SimpleServiceGrpc.SimpleServiceStub simpleService = SimpleServiceGrpc.newStub(managedChannel);

    Histogram histogram = new Histogram(3600000000000L, 3);
    System.out.println("starting warmup...");
    execute(count, simpleService, histogram, concurrency, executor);
    System.out.println("warmup complete");

    System.out.println("starting test - sending " + count);
    histogram = new Histogram(3600000000000L, 3);
    long start = System.nanoTime();
    execute(count, simpleService, histogram, concurrency, executor);

    histogram.outputPercentileDistribution(System.out, 1000.0d);
    double completedMillis = (System.nanoTime() - start) / 1_000_000d;
    double rps = count / ((System.nanoTime() - start) / 1_000_000_000d);
    System.out.println("test complete in " + completedMillis + "ms");
    System.out.println("test rps " + rps);
    System.out.println();
    System.exit(0);
  }

  private static void execute(
      int count,
      SimpleServiceGrpc.SimpleServiceStub simpleService,
      Histogram histogram,
      int concurrency,
      ExecutorService executor) {
    Flux.range(1, count)
        .publishOn(Schedulers.elastic())
        .flatMap(
            integer -> {
              long s = System.nanoTime();
              SimpleRequest request = SimpleRequest.newBuilder().setRequestMessage("hello").build();
              return Flux.create(
                  sink -> {
                    simpleService.requestReply(
                        request,
                        new StreamObserver<SimpleResponse>() {
                          @Override
                          public void onNext(SimpleResponse value) {
                            sink.next(value);
                          }

                          @Override
                          public void onError(Throwable t) {
                            sink.error(t);
                          }

                          @Override
                          public void onCompleted() {
                            histogram.recordValue(System.nanoTime() - s);
                            sink.complete();
                          }
                        });
                  });
            },
            concurrency)
        .blockLast();
  }
}
