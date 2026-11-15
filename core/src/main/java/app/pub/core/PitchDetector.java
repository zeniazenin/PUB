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
        double sum = 0;
        for (int tau = 1; tau <= maxTau + 1; tau++) {
            double d = 0;
            for (int i = 0; i < window; i++) {
                double delta = samples[i] - samples[i + tau];
                d += delta * delta;
            }
            sum += d;
            difference[tau] = sum == 0 ? 1 : d * tau / sum;
        }
        for (int tau = minTau; tau <= maxTau; tau++) {
            if (difference[tau] < 0.15) {
                while (tau < maxTau && difference[tau + 1] < difference[tau]) tau++;
                double a = difference[tau - 1], b = difference[tau], c = difference[tau + 1];
                double divisor = a - 2 * b + c;
                double offset = Math.abs(divisor) < 1e-12 ? 0 : 0.5 * (a - c) / divisor;
                offset = Math.max(-0.5, Math.min(0.5, offset));
                return new Result(sampleRate / (tau + offset), Math.max(0, 1 - b), rms);
            }
        }
        return new Result(0, 0, rms);
    }
}
