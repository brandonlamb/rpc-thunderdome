package io.netifi.thunderdome.grpc.nonblocking.stock;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.netifi.testing.protobuf.SimpleRequest;
import io.netifi.testing.protobuf.SimpleServiceGrpc;
import org.HdrHistogram.Histogram;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;

public class GrpcRequestReplyClient {
  public static void main(String... args) throws Exception {
    int warmup = 10_000;
    int count = 1_000_000;

    String host = System.getProperty("host", "127.0.0.1");
    int port = Integer.getInteger("port", 8001);

    ManagedChannel managedChannel =
        ManagedChannelBuilder.forAddress(host, port).usePlaintext(true).build();
    SimpleServiceGrpc.SimpleServiceFutureStub simpleService =
        SimpleServiceGrpc.newFutureStub(managedChannel);

    CountDownLatch warmupLatch = new CountDownLatch(warmup);

    ForkJoinPool executorService = ForkJoinPool.commonPool();
    System.out.println("starting warmup...");

    for (int i = 0; i < warmup; i++) {
      SimpleRequest hello = SimpleRequest.newBuilder().setRequestMessage("hello").build();
      simpleService.requestReply(hello).addListener(warmupLatch::countDown, executorService);
    }

    warmupLatch.await();
    System.out.println("warmup complete");

    System.out.println("starting test - sending " + count);
    CountDownLatch latch = new CountDownLatch(count);
    Histogram histogram = new Histogram(3600000000000L, 3);
    long start = System.nanoTime();
    for (int i = 0; i < count; i++) {
      long s = System.nanoTime();
      SimpleRequest hello = SimpleRequest.newBuilder().setRequestMessage("hello").build();
      simpleService
          .requestReply(hello)
          .addListener(
              () -> {
                histogram.recordValue(System.nanoTime() - s);
                latch.countDown();
              },
              executorService);
    }

    double completedMillis = (System.nanoTime() - start) / 1_000_000d;
    double rps = count / ((System.nanoTime() - start) / 1_000_000_000d);
    System.out.println("test complete in " + completedMillis + "ms");
    System.out.println("test rps " + rps);
    System.out.println();
    latch.await();
    managedChannel.shutdownNow();
  }
}
