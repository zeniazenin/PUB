package app.pub.core;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public class PitchDetectorTest {
    private float[] tone(int rate, double frequency, boolean harmonics) {
        float[] samples = new float[4096];
        for (int i = 0; i < samples.length; i++) {
            double phase = 2 * Math.PI * frequency * i / rate;
            samples[i] = (float)(harmonics ? .12 * Math.sin(phase) + .35 * Math.sin(2*phase) + .18*Math.sin(3*phase) : .4 * Math.sin(phase));
        }
        return samples;
    }
    @Test public void detectsAllGuitarStringsAndDetuningAtBothRates() {
        for (int rate : new int[]{44100,48000}) for (double note : new double[]{73.4162,82.4069,110,146.8324,195.9977,246.9417,329.6276}) for (int cents : new int[]{-20,0,20}) {
            double expected = note * Math.pow(2, cents/1200.0);
            PitchDetector.Result result = new PitchDetector(rate,4096,65,400).detect(tone(rate,expected,false));
            assertTrue("No pitch at " + expected, result.hz > 0);
            assertEquals("Accuracy at " + expected, 0, 1200*Math.log(result.hz/expected)/Math.log(2), 2);
            assertTrue(result.confidence > .9);
        }
    }
    @Test public void rejectsOctaveErrorWhenSecondHarmonicDominates() {
        for (double note : new double[]{82.4069,110,196,329.6276}) {
            PitchDetector.Result result = new PitchDetector(48000,4096,65,400).detect(tone(48000,note,true));
            assertEquals(0,1200*Math.log(result.hz/note)/Math.log(2), 3);
        }
    }
    @Test public void rejectsSilenceAndBroadbandNoise() {
        PitchDetector detector = new PitchDetector(48000,4096,65,400);
        assertEquals(0, detector.detect(new float[4096]).hz, 0);
        float[] noise = new float[4096]; Random random = new Random(42);
        for (int i=0;i<noise.length;i++) noise[i] = (float)(random.nextGaussian()*.1);
        assertEquals(0, detector.detect(noise).hz, 0);
    }
}
