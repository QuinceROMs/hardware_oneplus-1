package android.util;

import android.os.Build;
import android.os.SystemProperties;
import java.util.BitSet;

/**
 * Stub for OxygenOS device feature detection.
 * Provides SM8150-specific feature flags from OOS 11.0.9.1 odm_feature_list
 * when running on msmnile platform; returns false for all features otherwise.
 */
public final class OpFeatures {

    private static final String PLATFORM_SM8150 = "msmnile";
    private static final boolean sIsSM8150;
    private static final int MAX_FEATURE = 0x174;
    private static final BitSet sFeatures;

    /** Feature constants accessed by OnePlus Camera via reflection */
    public static final int OP_FEATURE_ENABLE_HBM = 0x62;
    public static final int OP_FEATURE_X_LINEAR_VIBRATION_MOTOR = 0x61;
    public static final int OP_FEATURE_HBM_AUTO_ADJUST = 0x62;
    public static final int OP_FEATURE_BOOST_BRIGHTNESS = 0x63;
    public static final int OP_FEATURE_Z_VIBRATION_MOTOR = 0xc0;
    public static final int OP_FEATURE_HOLE_PUNCH_FRONT_CAM = 0xe6;

    /** SM8150 (OnePlus 7 series) enabled features from OOS 11.0.9.1 odm_feature_list */
    private static final int[] SM8150_ENABLED_FEATURES = {
        1, 2, 3, 5, 6, 8, 9, 10, 12, 13, 14, 15, 16, 18, 19, 20,
        21, 22, 23, 24, 26, 27, 28, 30, 31, 32, 33, 34, 35, 36, 37, 38,
        40, 45, 46, 48, 49, 50, 51, 53, 58, 60, 61, 67, 69, 75, 76, 77,
        78, 79, 80, 81, 82, 85, 86, 89, 93, 98, 99, 102, 104, 105, 106,
        108, 110, 111, 112, 113, 115, 118, 119, 120, 125, 130, 131, 132,
        133, 134, 135, 136, 137, 138, 139, 140, 141, 143, 145, 146, 148,
        149, 150, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162,
        165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177,
        178, 180, 181, 182, 184, 186, 187, 189, 190, 191, 192, 199, 204,
        206, 207, 208, 210, 211, 213, 214, 215, 219, 220, 222, 233, 236,
        239, 240, 257, 259, 262, 265, 268, 269, 276, 278, 281, 284, 290,
        291, 295, 296, 301, 306, 313, 315, 320, 323, 325, 326, 332, 343,
        345, 346, 352, 355, 370, 372
    };

    static {
        sIsSM8150 = PLATFORM_SM8150.equals(
                SystemProperties.get("ro.board.platform", ""));
        sFeatures = new BitSet(MAX_FEATURE + 1);
        if (sIsSM8150) {
            for (int id : SM8150_ENABLED_FEATURES) {
                sFeatures.set(id);
            }
        }
    }

    public static boolean isSupport(int... features) {
        if (!sIsSM8150) return false;
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

    public static boolean isBlackModeOn() {
        return false;
    }

    public static String getProductName() {
        return Build.PRODUCT;
    }

    public static int getFeatureValue(String name) {
        return 0;
    }
}
