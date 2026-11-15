package app.pub.core;

/** YIN difference/CMND estimator. Reuses buffers; mono input, native sample rate. */
public final class PitchDetector {
    public static final class Result {
        public final double hz, confidence, rms;
        public Result(double hz, double confidence, double rms) { this.hz = hz; this.confidence = confidence; this.rms = rms; }
    }
    private final int sampleRate, minTau, maxTau;
    private final double[] difference;
    public PitchDetector(int sampleRate, int frameSize, double minHz, double maxHz) {
        this.sampleRate = sampleRate;
        minTau = Math.max(2, (int)(sampleRate / maxHz));
        maxTau = Math.min(frameSize / 2 - 1, (int)(sampleRate / minHz));
        difference = new double[maxTau + 2];
    }
    public Result detect(float[] samples) {
        double mean = 0, power = 0;
        for (float sample : samples) mean += sample;
        mean /= samples.length;
        for (float sample : samples) power += (sample - mean) * (sample - mean);
        double rms = Math.sqrt(power / samples.length);
        if (rms < 0.004) return new Result(0, 0, rms);
        int window = samples.length - maxTau - 1;
        difference[0] = 1;
        // The normalized difference at tau only depends on smaller lags, so lags are computed in order and
        // the search stops at the first accepted dip once it stops descending (plus one lag for the
        // parabolic interpolation). Results are identical to computing every lag; higher strings, whose
        // dip comes early, cost a fraction of the full O(window * maxTau) scan.
        double sum = 0;
        int found = -1;
        for (int tau = 1; tau <= maxTau + 1; tau++) {
            double d = 0;
            for (int i = 0; i < window; i++) {
                double delta = samples[i] - samples[i + tau];
                d += delta * delta;
            }
            sum += d;
            difference[tau] = sum == 0 ? 1 : d * tau / sum;
            int previous = tau - 1;
            if (found < 0 && previous >= minTau && previous <= maxTau && difference[previous] < 0.15) found = previous;
            if (found >= 0 && found == previous) {
                if (found < maxTau && difference[tau] < difference[found]) found = tau; else break;
            }
        }
        if (found < 0) return new Result(0, 0, rms);
        double a = difference[found - 1], b = difference[found], c = difference[found + 1];
        double divisor = a - 2 * b + c;
        double offset = Math.abs(divisor) < 1e-12 ? 0 : 0.5 * (a - c) / divisor;
        offset = Math.max(-0.5, Math.min(0.5, offset));
        return new Result(sampleRate / (found + offset), Math.max(0, 1 - b), rms);
    }
}
