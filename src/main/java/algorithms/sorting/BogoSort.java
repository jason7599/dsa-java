package algorithms.sorting;

import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;

public class BogoSort implements Sort {

    private static final int MAX_TRIES_BEFORE_POOPING_PANTS = 10_000;

    @Override
    public <T> void sort(T[] arr, Comparator<? super T> cmp) {
        int tries = 0;
        while (!isSorted(arr, cmp)) {
            if (tries++ == MAX_TRIES_BEFORE_POOPING_PANTS) {
                throw new PantsPooedException();
            }
            fisherYates(arr);
        }
    }

    @Override
    public boolean isStable() {
        return false;
    }

    private static <T> boolean isSorted(T[] arr, Comparator<? super T> cmp) {
        for (int i = 0; i < arr.length - 1; i++) {
            if (cmp.compare(arr[i], arr[i + 1]) > 0) {
                return false;
            }
        }
        return true;
    }

    private static <T> void fisherYates(T[] arr) {
        for (int i = 0; i < arr.length - 1; i++) {
            Sort.swap(arr, i, ThreadLocalRandom.current().nextInt(i, arr.length));
        }
    }

    public static class PantsPooedException extends RuntimeException {
        public PantsPooedException() {
            super("Pants Pooed");
        }
    }
}
