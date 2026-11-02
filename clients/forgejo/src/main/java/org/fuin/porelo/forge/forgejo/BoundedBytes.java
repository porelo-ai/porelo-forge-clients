package org.fuin.porelo.forge.forgejo;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.jspecify.annotations.Nullable;

/**
 * Reads an answer one buffer at a time up to its bound. One byte more runs the overflow and stops
 * the reading; a subscriber made refusing stops it before the first byte. It keeps at most the last
 * {@code keep} bytes of what it read.
 */
final class BoundedBytes implements HttpResponse.BodySubscriber<byte[]> {
    private final long bound;
    private final int keep;
    private final Runnable overflow;
    private final boolean refusing;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private volatile Flow.@Nullable Subscription subscription;
    private long read;

    private BoundedBytes(long bound, int keep, Runnable overflow, boolean refusing) {
        this.bound = bound;
        this.keep = keep;
        this.overflow = overflow;
        this.refusing = refusing;
    }

    /** Keeps everything up to the bound. */
    static BoundedBytes whole(int bound, Runnable overflow) {
        return new BoundedBytes(bound, bound, overflow, false);
    }

    /** Reads up to the bound and keeps the last bytes of it. */
    static BoundedBytes tail(long bound, int keep, Runnable overflow) {
        return new BoundedBytes(bound, keep, overflow, false);
    }

    /** Reads nothing, for an answer whose verdict is already given. */
    static BoundedBytes refusing() {
        return new BoundedBytes(0, 0, () -> {}, true);
    }

    /** Returns whether more was read than was kept. */
    boolean cut() {
        return read > keep;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        Objects.requireNonNull(subscription);
        if (this.subscription != null || result.isDone()) {
            subscription.cancel();
            return;
        }
        this.subscription = subscription;
        if (refusing) {
            stop(subscription);
        } else {
            subscription.request(1);
        }
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        Objects.requireNonNull(items);
        Flow.Subscription current = subscription;
        if (result.isDone() || current == null) {
            return;
        }
        for (ByteBuffer item : items) {
            while (item.hasRemaining()) {
                int take = (int) Math.min(Math.min(item.remaining(), 65536), bound + 1 - read);
                byte[] chunk = new byte[take];
                item.get(chunk);
                read += take;
                if (read > bound) {
                    overflow.run();
                    stop(current);
                    return;
                }
                bytes.writeBytes(chunk);
                compact();
            }
        }
        current.request(1);
    }

    /** Lets the buffer grow to twice what it keeps, so a long log is not copied on every read. */
    private void compact() {
        if (bytes.size() >= 2L * keep && bytes.size() > keep) {
            byte[] all = bytes.toByteArray();
            bytes.reset();
            bytes.write(all, all.length - keep, keep);
        }
    }

    /** Completes before cancelling, since a transport may fail the exchange on the cancel. */
    private void stop(Flow.Subscription current) {
        result.complete(new byte[0]);
        current.cancel();
    }

    @Override
    public void onError(Throwable error) {
        result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
        byte[] all = bytes.toByteArray();
        result.complete(
                all.length <= keep
                        ? all
                        : java.util.Arrays.copyOfRange(all, all.length - keep, all.length));
    }
}
