package cc.bact.sameasbench.datagen;

import java.util.ArrayList;
import java.util.List;

/**
 * Xorshift32 PRNG that produces byte-identical output to the Python
 * {@code _DeterministicRng} in {@code data_gen.py} for the same seed.
 *
 * <p>Algorithm: state ^= state<<13; state ^= state>>17; state ^= state<<5
 * All operations are unsigned 32-bit; seed=0 is normalised to 1.
 *
 * <p>Use instead of {@code java.util.Random} whenever cross-language
 * reproducibility of generated SBOM graphs is required.
 */
public final class DeterministicRng {

    private long state; // kept in [1, 2^32-1]

    public DeterministicRng(int seed) {
        this.state = (seed == 0) ? 1L : (seed & 0xFFFFFFFFL);
    }

    // --- core ---

    private long next() {
        state ^= (state << 13) & 0xFFFFFFFFL;
        state ^=  state >> 17;
        state ^= (state <<  5) & 0xFFFFFFFFL;
        state &= 0xFFFFFFFFL;
        return state;
    }

    /** Returns value in [0, bound). */
    public int nextInt(int bound) {
        return (int)(next() % (long) bound);
    }

    // --- compound helpers (same semantics as Python counterparts) ---

    /** Fisher-Yates in-place shuffle. */
    public <T> void shuffle(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    /** Pick one element uniformly. */
    public <T> T choice(List<T> list) {
        return list.get(nextInt(list.size()));
    }

    /** Pick {@code k} elements WITH replacement. */
    public <T> List<T> choices(List<T> list, int k) {
        List<T> out = new ArrayList<>(k);
        for (int i = 0; i < k; i++) out.add(choice(list));
        return out;
    }

    /** Pick {@code k} elements WITHOUT replacement (shuffle copy, take first k). */
    public <T> List<T> sample(List<T> list, int k) {
        List<T> copy = new ArrayList<>(list);
        shuffle(copy);
        return new ArrayList<>(copy.subList(0, k));
    }
}
