package com.portalroomos;

import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * Puts a PS5 controller's buttons back where they belong.
 *
 * Android learned the DualSense in version 12. The Portal runs 9, with a 4.4
 * kernel that has no Sony HID driver, so over Bluetooth the pad is read as a
 * generic HID gamepad and falls through to Generic.kl, which numbers its
 * buttons in HID order: Square arrives as A, Cross as B, Circle as C, Triangle
 * as X, L1 as Y, R1 as Z. Browsers drop C and Z entirely, so Circle and R1
 * would simply vanish in a game. The fix would normally be a key layout file
 * for 054c:0ce6, but /system is read-only on a locked Portal, so the remap
 * happens here instead, on each key event before anything else sees it.
 *
 * The table keys on the raw scan code, which is the kernel's button number and
 * does not depend on which layout file got applied. It is the same table as
 * the Portal's own Vendor_054c_Product_05c4.kl for the PS4 pad, whose buttons
 * report in the same order.
 */
final class DualSense {

    private static final int SONY = 0x054c;
    private static final int DUALSENSE = 0x0ce6;
    private static final int DUALSENSE_EDGE = 0x0df2;

    private static final SparseIntArray BY_SCAN_CODE = new SparseIntArray();
    static {
        BY_SCAN_CODE.put(0x130, KeyEvent.KEYCODE_BUTTON_X);       // Square
        BY_SCAN_CODE.put(0x131, KeyEvent.KEYCODE_BUTTON_A);       // Cross
        BY_SCAN_CODE.put(0x132, KeyEvent.KEYCODE_BUTTON_B);       // Circle
        BY_SCAN_CODE.put(0x133, KeyEvent.KEYCODE_BUTTON_Y);       // Triangle
        BY_SCAN_CODE.put(0x134, KeyEvent.KEYCODE_BUTTON_L1);
        BY_SCAN_CODE.put(0x135, KeyEvent.KEYCODE_BUTTON_R1);
        BY_SCAN_CODE.put(0x136, KeyEvent.KEYCODE_BUTTON_L2);
        BY_SCAN_CODE.put(0x137, KeyEvent.KEYCODE_BUTTON_R2);
        BY_SCAN_CODE.put(0x138, KeyEvent.KEYCODE_BUTTON_SELECT);  // Create
        BY_SCAN_CODE.put(0x139, KeyEvent.KEYCODE_BUTTON_START);   // Options
        BY_SCAN_CODE.put(0x13a, KeyEvent.KEYCODE_BUTTON_THUMBL);  // L3
        BY_SCAN_CODE.put(0x13b, KeyEvent.KEYCODE_BUTTON_THUMBR);  // R3
        BY_SCAN_CODE.put(0x13c, KeyEvent.KEYCODE_BUTTON_MODE);    // PS
        BY_SCAN_CODE.put(0x13d, KeyEvent.KEYCODE_BUTTON_1);       // touchpad click
    }

    private DualSense() {}

    static boolean is(InputDevice device) {
        if (device == null || device.getVendorId() != SONY) return false;
        int product = device.getProductId();
        return product == DUALSENSE || product == DUALSENSE_EDGE;
    }

    /** The event with its key code corrected, or the same event if it needs nothing. */
    static KeyEvent fix(KeyEvent e) {
        if (!is(e.getDevice())) return e;
        int code = BY_SCAN_CODE.get(e.getScanCode(), KeyEvent.KEYCODE_UNKNOWN);
        if (code == KeyEvent.KEYCODE_UNKNOWN || code == e.getKeyCode()) return e;
        return new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), code,
            e.getRepeatCount(), e.getMetaState(), e.getDeviceId(), e.getScanCode(),
            e.getFlags(), e.getSource());
    }
}
