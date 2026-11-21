package ktshim.kotlin.random;

public final class RandomKt {
    private RandomKt() {}
    public static Random Random(int seed) { return new XorShift(seed); }
    public static Random Random(long seed) { return new XorShift((int) (seed ^ (seed >>> 32))); }
    public static int nextInt(Random r, ktshim.kotlin.ranges.IntRange range) { return r.nextInt(range.getFirst(), range.getLast() + 1); }

    static final class XorShift extends Random {
        int s;
        XorShift(int seed) { s = seed == 0 ? 0x2545F491 : seed; }
        public int nextBits(int bits) {
            s ^= s << 13; s ^= s >>> 17; s ^= s << 5;
            return s >>> (32 - bits);
        }
    }
}
