package android.util;

import java.util.Objects;

/** SDK value implementation for JVM EXIF tests; mockable-android.jar leaves these fields null. */
public class Pair<F, S> {
    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }

    public static <A, B> Pair<A, B> create(A first, B second) {
        return new Pair<>(first, second);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Pair<?, ?> pair
                && Objects.equals(first, pair.first) && Objects.equals(second, pair.second);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(first) ^ Objects.hashCode(second);
    }

    @Override
    public String toString() {
        return "Pair{" + first + " " + second + "}";
    }
}
