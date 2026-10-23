package com.lqbz.ops.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 测试夹具在 Java 8 上的流读取和线程池资源管理。 */
public final class Java8TestSupport {
    private Java8TestSupport() { }

    public static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    public static byte[] readBytes(InputStream input, int length) throws IOException {
        if (length < 0) throw new IllegalArgumentException("读取长度不能为负数");
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count == -1) return java.util.Arrays.copyOf(bytes, offset);
            offset += count;
        }
        return bytes;
    }

    public static ClosingExecutor executor() { return new ClosingExecutor(); }

    public static final class ClosingExecutor extends AbstractExecutorService implements AutoCloseable {
        private final ExecutorService delegate = Executors.newCachedThreadPool(
                com.lqbz.ops.compat.Java8.daemonFactory("test-fixture"));

        public void shutdown() { delegate.shutdown(); }
        public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        public boolean isShutdown() { return delegate.isShutdown(); }
        public boolean isTerminated() { return delegate.isTerminated(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
        public void execute(Runnable task) { delegate.execute(task); }
        public void close() {
            delegate.shutdown();
            boolean interrupted = false;
            try {
                while (!delegate.isTerminated()) {
                    try {
                        delegate.awaitTermination(1, TimeUnit.DAYS);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                        delegate.shutdownNow();
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
