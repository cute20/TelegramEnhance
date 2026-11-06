package io.github.telegramenhance.transfer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** A validation/preparation refusal before any task transaction was attempted. */
public final class CreationRejectedException extends IllegalArgumentException {
    public static final String CODE = "CREATION_REJECTED";

    public CreationRejectedException(String message) { super(message); }
    private CreationRejectedException(IllegalArgumentException cause) { super(cause.getMessage(), cause); }

    public static <T> T prepare(Supplier<T> action) {
        try { return action.get(); }
        catch (CreationRejectedException rejected) { throw rejected; }
        catch (IllegalArgumentException rejected) { throw new CreationRejectedException(rejected); }
    }

    /** The returned stage must finish before invoking repository.create/createBatch. */
    public static <T> CompletableFuture<T> preparation(Supplier<CompletableFuture<T>> action) {
        return prepare(action).handle((value, failure) -> {
            if (failure == null) return value;
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof CreationRejectedException rejected) throw new CompletionException(rejected);
            if (cause instanceof IllegalArgumentException rejected) throw new CompletionException(new CreationRejectedException(rejected));
            throw new CompletionException(cause);
        });
    }
}
