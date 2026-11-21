package ktshim.kotlin.random;

import ktshim.ktvm.Native;

public abstract class Random {
    public static final Default Default = new Default();

    public abstract int nextBits(int bits);
    public int nextInt() { return nextBits(32); }
    public int nextInt(int until) { return nextInt(0, until); }
    public int nextInt(int from, int until) {
        if (until <= from) throw new IllegalArgumentException("Random range is empty: [" + from + ", " + until + ").");
        long n = (long) until - from;
        return (int) (from + ((nextInt() & 0xffffffffL) % n));
    }
    public long nextLong() { return ((long) nextInt() << 32) | (nextInt() & 0xffffffffL); }
    public boolean nextBoolean() { return nextBits(1) != 0; }
    public double nextDouble() { return (nextBits(26) * 134217728.0 + nextBits(27)) / 9007199254740992.0; }
    public double nextDouble(double until) { return nextDouble() * until; }
    public double nextDouble(double from, double until) { return from + nextDouble() * (until - from); }
    public float nextFloat() { return nextBits(24) / 16777216.0f; }

    public static final class Default extends Random {
        public int nextBits(int bits) { return Native.randomInt() >>> (32 - bits); }
    }
}
