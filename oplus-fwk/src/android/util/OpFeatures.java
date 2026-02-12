package android.util;

import android.os.Build;
import java.util.BitSet;

/**
 * Stub for OxygenOS device feature detection.
 * Returns true for all features supported by OnePlus 7 series (SM8150).
 */
public final class OpFeatures {

    private static final int MAX_FEATURE = 0x175;
    private static final BitSet sFeatures;

    static {
        sFeatures = new BitSet(MAX_FEATURE + 1);
        sFeatures.set(0, MAX_FEATURE + 1);
    }

    public static boolean isSupport(int... features) {
        for (int feature : features) {
            if (feature < 0 || feature > MAX_FEATURE) {
                return false;
            }
            if (!sFeatures.get(feature)) {
                return false;
            }
        }
        return true;
    }

    public static String getProductName() {
        return Build.PRODUCT;
    }

    public static int getFeatureValue(String name) {
        return 0;
    }
}
