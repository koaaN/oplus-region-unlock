package dev.op13.regionunlock;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Root/app_process client for the OnePlus 13/15 region-lock subsystem. */
public final class RegionUnlock {
    private static final String RADIO_MANAGER = "com.oplus.telephony.RadioManager";
    private static final String VENDOR_SERVICE_PREFIX =
            "vendor.oplus.hardware.subsys_interface.subsys_radio.ISubsysRadio/slot";
    private static final String VENDOR_DESCRIPTOR =
            "vendor.oplus.hardware.subsys_interface.subsys_radio.ISubsysRadio";
    private static final int TRANSACTION_SET_REGION_LOCK_STATUS = 294;

    private static final int OPERATION_AUTO_UNLOCK = 2;
    private static final int OPERATION_LOCK_STATE = 3;
    private static final int DATA_UNLOCK = 0;
    private static final int DATA_LOCK = 1;
    private static final int TLV_TAG_STATUS = 1;
    private static final int EXPECTED_UID = 1000;

    private final Options options;
    private final Context context;
    private final Class<?> managerClass;
    private final Object manager;

    private RegionUnlock(Options options) throws Exception {
        this.options = options;
        this.context = getSystemContext();
        this.managerClass = Class.forName(RADIO_MANAGER);
        this.manager = managerClass.getMethod("createForSlotId", Context.class, int.class)
                .invoke(null, context, options.slot);
        if (manager == null) {
            throw new IllegalStateException("RadioManager.createForSlotId returned null");
        }
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help) {
                usage();
                return;
            }
            requireSystemUid();
            RegionUnlock client = new RegionUnlock(options);
            client.run();
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            usage();
            System.exit(64);
        } catch (SecurityException e) {
            System.err.println("permission denied: " + rootMessage(e));
            System.err.println("The OnePlus subsystem call must run as Android UID 1000.");
            System.exit(5);
        } catch (Throwable t) {
            System.err.println("region-unlock failed: " + rootMessage(t));
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void run() throws Exception {
        if (options.probe) {
            probe();
            return;
        }
        if (options.status) {
            printState("region-lock-state", getState());
            return;
        }
        if (options.policy) {
            printPolicy();
            return;
        }
        if (options.settings) {
            printSettings();
            return;
        }
        if (options.testInfo) {
            printTestInfo();
            return;
        }
        if (options.autoUnlock) {
            autoUnlock();
            return;
        }
        if (options.lockState) {
            lockState();
            return;
        }
        if (options.unlockCode != null) {
            unlockWithCode(options.unlockCode);
            return;
        }
        if (options.signedBlob != null) {
            updateSignedBlob(options.signedBlob);
            return;
        }
        throw new IllegalArgumentException("select an action");
    }

    private void probe() throws Exception {
        String serviceName = vendorServiceName();
        IBinder binder = waitForService(serviceName, options.waitSeconds);
        if (binder == null) {
            throw new IllegalStateException("vendor service unavailable: " + serviceName);
        }
        String descriptor = binder.getInterfaceDescriptor();
        if (!VENDOR_DESCRIPTOR.equals(descriptor)) {
            throw new IllegalStateException("unexpected descriptor: " + descriptor);
        }
        State state = getState();
        System.out.println("OK: OnePlus region-lock subsystem is available");
        System.out.println("uid=" + Process.myUid());
        System.out.println("slot=" + options.slot);
        System.out.println("vendor-service=" + serviceName);
        System.out.println("vendor-descriptor=" + descriptor);
        printState("region-lock-state", state);
    }

    private State getState() throws Exception {
        Callback callback = invokeManager(
                "getRegionLockInfo",
                new Class<?>[]{String.class, Message.class},
                new Object[]{"1"});
        callback.requireSuccess("getRegionLockInfo");
        Object info = callback.bundle.get("keyObject");
        if (info == null) {
            throw new IllegalStateException("getRegionLockInfo returned no RegionLockInfo");
        }
        return State.from(info);
    }

    private void printPolicy() throws Exception {
        State state = getState();
        printState("region-lock-state", state);
        System.out.println("policy-mask=" + state.policyMask);
        System.out.println("mcc-list=" + state.blacklistMcc);
        PolicyMask.fromHex(state.policyMask).print();
    }

    private void printSettings() throws Exception {
        Callback callback = invokeManager(
                "getRegionLockSettingData",
                new Class<?>[]{Message.class},
                new Object[]{});
        callback.requireSuccess("getRegionLockSettingData");
        String hex = callback.bundle.getString("keyString");
        if (hex == null) {
            throw new IllegalStateException("getRegionLockSettingData returned no data");
        }
        byte[] data = decodeHex(hex);
        System.out.println("settings-hex=" + hex);
        System.out.println("settings-length=" + data.length);
        if (data.length > 0) {
            System.out.println("assistant-status=" + (data[0] & 0xff));
        }
        if (data.length > 1) {
            System.out.println("retry-times=" + (data[1] & 0xff));
        }
        if (data.length > 3) {
            int unlockAttempts = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
            System.out.println("unlock-attempt-counter=" + unlockAttempts);
        }
    }

    private void printTestInfo() throws Exception {
        Callback callback = invokeManager(
                "getRegionNetLockTestInfo",
                new Class<?>[]{Message.class},
                new Object[]{});
        callback.requireSuccess("getRegionNetLockTestInfo");
        Object value = callback.bundle.get("keyObject");
        if (!(value instanceof Bundle)) {
            System.out.println("matcher-monitor=inactive");
            System.out.println("note=normal when the device is already unlocked");
            return;
        }
        Bundle info = (Bundle) value;
        System.out.println("matcher-monitor=active");
        System.out.println("activation-state=" + info.getString("bitmask0", "unknown"));
        System.out.println("service-duration-matched=" + info.getString("bitmask1", "unknown"));
        System.out.println("call-count=" + info.getString("bitmask2", "unknown"));
        System.out.println("call-duration-seconds=" + info.getString("bitmask3", "unknown"));
        System.out.println("charge-count=" + info.getString("bitmask4", "unknown"));
        System.out.println("screen-change-count=" + info.getString("bitmask5", "unknown"));
        System.out.println("service-cell-count=" + info.getString("bitmask6", "unknown"));
        System.out.println("roaming-sim-match=" + info.getString("bitmask7", "unknown"));
    }

    private void autoUnlock() throws Exception {
        State before = getState();
        printState("before", before);
        int operator = before.operator >= 0 ? before.operator : 0;
        sendStatusCommand(operator, OPERATION_AUTO_UNLOCK, DATA_UNLOCK, "AUTO_UNLOCK");
        Thread.sleep(1500L);
        printState("after", getState());
    }

    private void lockState() throws Exception {
        printState("before", getState());
        sendStatusCommand(0, OPERATION_LOCK_STATE, DATA_LOCK, "LOCK_STATE");
        Thread.sleep(1500L);
        printState("after", getState());
    }

    private void sendStatusCommand(int operator, int operation, int data, String action)
            throws Exception {
        String serviceName = vendorServiceName();
        IBinder binder = waitForService(serviceName, options.waitSeconds);
        if (binder == null) {
            throw new IllegalStateException("vendor service unavailable: " + serviceName);
        }
        String descriptor = binder.getInterfaceDescriptor();
        if (!VENDOR_DESCRIPTOR.equals(descriptor)) {
            throw new IllegalStateException("unexpected descriptor: " + descriptor);
        }

        // Stock RegionLockManager format: tag=1, big-endian length=3,
        // value=[operator, operation, data].
        byte[] tlv = new byte[]{
                (byte) TLV_TAG_STATUS, 0, 3,
                (byte) operator, (byte) operation, (byte) data
        };
        int serial = makeSerial();
        Parcel request = Parcel.obtain();
        boolean accepted;
        try {
            request.writeInterfaceToken(VENDOR_DESCRIPTOR);
            request.writeInt(serial);
            request.writeByteArray(tlv);
            accepted = binder.transact(
                    TRANSACTION_SET_REGION_LOCK_STATUS,
                    request,
                    null,
                    IBinder.FLAG_ONEWAY);
        } finally {
            request.recycle();
        }
        if (!accepted) {
            throw new IllegalStateException("vendor Binder rejected transaction 294");
        }
        System.out.println(action + " queued: serial=" + serial
                + " operator=" + operator + " operation=" + operation + " data=" + data);
        System.out.println("transport=OnePlus subsystem-radio TLV tag 1");
    }

    private void unlockWithCode(String code) throws Exception {
        if (code.length() < 1 || code.getBytes("UTF-8").length > 16) {
            throw new IllegalArgumentException("unlock code must contain 1 through 16 UTF-8 bytes");
        }
        printState("before", getState());
        Callback callback = invokeManager(
                "unlockRegionLock",
                new Class<?>[]{int.class, String.class, Message.class},
                new Object[]{0, code});
        callback.requireSuccess("unlockRegionLock");
        System.out.println("unlock-code request completed; the code was not printed");
        printState("after", getState());
    }

    private void updateSignedBlob(String encodedBlob) throws Exception {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedBlob);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("signed blob is not valid Base64");
        }
        if (decoded.length < 256) {
            throw new IllegalArgumentException("signed blob is too short to contain its 256-byte signature");
        }
        printState("before", getState());
        Callback callback = invokeManager(
                "updateRegionLockBlob",
                new Class<?>[]{String.class, Message.class},
                new Object[]{encodedBlob});
        callback.requireSuccess("updateRegionLockBlob");
        System.out.println("signed region-lock blob request completed; payload was not printed");
        printState("after", getState());
    }

    private Callback invokeManager(
            final String methodName,
            final Class<?>[] parameterTypes,
            final Object[] arguments) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Callback callback = new Callback();
        HandlerThread thread = new HandlerThread("op13-region-lock-callback");
        thread.start();
        Handler handler = new Handler(thread.getLooper()) {
            @Override
            public void handleMessage(Message message) {
                callback.bundle = message.getData() != null ? message.getData() : new Bundle();
                callback.what = message.what;
                callback.arg1 = message.arg1;
                callback.arg2 = message.arg2;
                latch.countDown();
            }
        };
        try {
            Object[] callArguments = new Object[arguments.length + 1];
            System.arraycopy(arguments, 0, callArguments, 0, arguments.length);
            callArguments[arguments.length] = Message.obtain(handler);
            managerClass.getMethod(methodName, parameterTypes).invoke(manager, callArguments);
            if (!latch.await(options.waitSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException(methodName + " callback timed out after "
                        + options.waitSeconds + " seconds");
            }
            return callback;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        } finally {
            thread.quitSafely();
        }
    }

    private String vendorServiceName() {
        return VENDOR_SERVICE_PREFIX + (options.slot + 1);
    }

    private static Context getSystemContext() throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    }

    private static void requireSystemUid() {
        if (Process.myUid() != EXPECTED_UID) {
            throw new SecurityException("running as UID " + Process.myUid()
                    + "; expected UID 1000 (root UID 0 is rejected by stock SubsysPermissions)");
        }
    }

    private static IBinder waitForService(String name, int waitSeconds) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + waitSeconds * 1000L;
        do {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class)
                    .invoke(null, name);
            if (binder != null && binder.pingBinder()) {
                return binder;
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return null;
            }
            Thread.sleep(250L);
        } while (true);
    }

    private static int makeSerial() {
        return 0x7f000000 | ((int) SystemClock.elapsedRealtime() & 0x00ffffff);
    }

    private static byte[] decodeHex(String value) {
        if (value == null || (value.length() & 1) != 0) {
            throw new IllegalArgumentException("invalid hex data");
        }
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid hex data");
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static void printState(String label, State state) {
        System.out.println(label + ": " + state.summary());
        System.out.println(label + "-device: region=" + state.region
                + " brand=" + state.brand
                + " version=" + state.majorVersion + "." + state.minorVersion);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.toString();
    }

    private static void usage() {
        System.out.println("Usage: region-unlock [--slot 0|1] [--wait seconds] ACTION");
        System.out.println("Actions (choose exactly one):");
        System.out.println("  --probe                 Validate OnePlus services and read current state");
        System.out.println("  --status                Read current region-lock state");
        System.out.println("  --policy                Decode the active region-lock policy (read-only)");
        System.out.println("  --settings              Read retry and assistant settings (read-only)");
        System.out.println("  --test-info             Read matcher progress counters (read-only)");
        System.out.println("  --auto-unlock           Send stock AUTO_UNLOCK; successful state is 0");
        System.out.println("  --lock-state            Send stock LOCK_STATE; expected state is 1");
        System.out.println("  --unlock-code CODE      Submit a provisioned local code; success is state 5");
        System.out.println("  --signed-blob BASE64    Submit a signed provisioning blob; sale success is state 2");
        System.out.println("The process must run as Android UID 1000, normally through the supplied launcher.");
    }

    private static final class Callback {
        Bundle bundle = new Bundle();
        int what;
        int arg1;
        int arg2;

        void requireSuccess(String operation) {
            int result = bundle.getInt("result", -1);
            if (result != 0) {
                throw new IllegalStateException(operation + " failed: API result=" + result
                        + " callback=" + what + "/" + arg1 + "/" + arg2);
            }
        }
    }

    private static final class State {
        int operator = -1;
        int operation = -1;
        int state = -999;
        int result = -1;
        String region = "unknown";
        String brand = "unknown";
        String majorVersion = "unknown";
        String minorVersion = "unknown";
        String policyMask = "unknown";
        String blacklistMcc = "unknown";

        static State from(Object info) throws Exception {
            State value = new State();
            value.operator = getInt(info, "getOperator", -1);
            value.operation = getInt(info, "getOperationType", -1);
            value.state = getInt(info, "getState", -999);
            value.result = getInt(info, "getResult", -1);
            value.region = getString(info, "getRegion", "unknown");
            value.brand = getString(info, "getBrand", "unknown");
            value.majorVersion = getString(info, "getMajorVersion", "unknown");
            value.minorVersion = getString(info, "getMinorVersion", "unknown");
            value.policyMask = getString(info, "getPolicyMask", "unknown");
            value.blacklistMcc = getString(info, "getBlacklistMcc", "unknown");
            return value;
        }

        String summary() {
            return "operator=" + operator + " operation=" + operation
                    + " state=" + state + " result=" + result + " (" + stateName(state) + ")";
        }

        private static int getInt(Object object, String getter, int fallback) throws Exception {
            Object value = object.getClass().getMethod(getter).invoke(object);
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static String getString(Object object, String getter, String fallback) {
            try {
                Object value = object.getClass().getMethod(getter).invoke(object);
                return value != null ? String.valueOf(value) : fallback;
            } catch (Exception ignored) {
                return fallback;
            }
        }

        private static String stateName(int state) {
            switch (state) {
                case -1: return "INVALID/TEST_LOCKED";
                case 0: return "AUTO_UNLOCKED";
                case 1: return "LOCKED";
                case 2: return "SALE_UNLOCKED";
                case 3: return "SERVER_LOCKED";
                case 4: return "SERVER_UNLOCKED";
                case 5: return "LOCAL_UNLOCKED";
                default: return "UNKNOWN";
            }
        }
    }

    private static final class PolicyMask {
        private final String bits;

        private PolicyMask(String bits) {
            this.bits = bits;
        }

        static PolicyMask fromHex(String hex) {
            byte[] data = decodeHex(hex);
            StringBuilder bits = new StringBuilder(data.length * 8);
            for (byte value : data) {
                for (int shift = 7; shift >= 0; shift--) {
                    bits.append(((value & 0xff) >> shift) & 1);
                }
            }
            if (bits.length() < 256) {
                throw new IllegalArgumentException("policy mask is shorter than 256 bits");
            }
            return new PolicyMask(bits.toString());
        }

        private boolean enabled(int bit) {
            return bit >= 0 && bit < bits.length() && bits.charAt(bit) == '1';
        }

        private int value(int start, int end) {
            int result = 0;
            for (int index = start; index <= end; index++) {
                result = (result << 1) | (enabled(index) ? 1 : 0);
            }
            return result;
        }

        void print() {
            int serviceCells = value(94, 96);
            boolean highServiceCells = enabled(97);
            if (highServiceCells) {
                serviceCells += value(98, 100) * 8;
            }
            System.out.println("ui-dialog=" + enabled(0));
            System.out.println("ui-notification=" + enabled(1));
            System.out.println("delay-notice=" + enabled(2) + " value=" + value(3, 10));
            System.out.println("delay-notice-boot-count=" + enabled(11)
                    + " value=" + value(12, 15));
            System.out.println("message-type=" + value(16, 18));
            System.out.println("disable-5g=" + enabled(32));
            System.out.println("disable-calls=" + enabled(33));
            System.out.println("disable-data=" + enabled(34));
            System.out.println("disable-sim=" + enabled(35));
            System.out.println("disable-wifi=" + enabled(36));
            System.out.println("matcher-in-service=" + enabled(48)
                    + " minutes=" + value(49, 56));
            System.out.println("matcher-call-count=" + enabled(57)
                    + " target=" + value(58, 65));
            System.out.println("matcher-call-duration=" + enabled(66)
                    + " target=" + value(67, 74));
            System.out.println("matcher-charge-count=" + enabled(75)
                    + " target=" + value(76, 83));
            System.out.println("matcher-screen-changes=" + enabled(84)
                    + " target=" + value(85, 92));
            System.out.println("matcher-service-cells=" + enabled(93)
                    + " target=" + serviceCells);
            System.out.println("matcher-roaming-sim=" + enabled(129)
                    + " mccs=" + value(130, 139) + "," + value(140, 149) + ","
                    + value(150, 159) + "," + value(160, 169) + "," + value(170, 179));
            System.out.println("switch-origin-match=" + enabled(180)
                    + " value=" + value(181, 184));
            System.out.println("mcc-mode=" + (enabled(185) ? "blacklist" : "whitelist"));
            System.out.println("override=" + enabled(186));
            System.out.println("test-roaming-sim=" + enabled(243)
                    + " operator=" + value(244, 246));
            System.out.println("test-delay=" + enabled(247)
                    + " value=" + value(248, 254));
        }
    }

    private static final class Options {
        int slot;
        int waitSeconds = 15;
        boolean probe;
        boolean status;
        boolean policy;
        boolean settings;
        boolean testInfo;
        boolean autoUnlock;
        boolean lockState;
        String unlockCode;
        String signedBlob;
        boolean help;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    options.help = true;
                } else if ("--probe".equals(arg)) {
                    options.probe = true;
                } else if ("--status".equals(arg)) {
                    options.status = true;
                } else if ("--policy".equals(arg)) {
                    options.policy = true;
                } else if ("--settings".equals(arg)) {
                    options.settings = true;
                } else if ("--test-info".equals(arg)) {
                    options.testInfo = true;
                } else if ("--auto-unlock".equals(arg)) {
                    options.autoUnlock = true;
                } else if ("--lock-state".equals(arg)) {
                    options.lockState = true;
                } else if ("--unlock-code".equals(arg)) {
                    options.unlockCode = value(args, ++i, arg);
                } else if ("--signed-blob".equals(arg)) {
                    options.signedBlob = value(args, ++i, arg);
                } else if ("--slot".equals(arg)) {
                    options.slot = parseInt(value(args, ++i, arg), arg);
                } else if ("--wait".equals(arg)) {
                    options.waitSeconds = parseInt(value(args, ++i, arg), arg);
                } else {
                    throw new IllegalArgumentException("unknown argument: " + arg);
                }
            }
            if (options.help) {
                return options;
            }
            if (options.slot < 0 || options.slot > 1) {
                throw new IllegalArgumentException("--slot must be 0 or 1");
            }
            if (options.waitSeconds < 1 || options.waitSeconds > 300) {
                throw new IllegalArgumentException("--wait must be from 1 through 300 seconds");
            }
            int actions = (options.probe ? 1 : 0)
                    + (options.status ? 1 : 0)
                    + (options.policy ? 1 : 0)
                    + (options.settings ? 1 : 0)
                    + (options.testInfo ? 1 : 0)
                    + (options.autoUnlock ? 1 : 0)
                    + (options.lockState ? 1 : 0)
                    + (options.unlockCode != null ? 1 : 0)
                    + (options.signedBlob != null ? 1 : 0);
            if (actions != 1) {
                throw new IllegalArgumentException("choose exactly one action");
            }
            return options;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("missing value for " + option);
            }
            return args[index];
        }

        private static int parseInt(String value, String option) {
            try {
                return Integer.decode(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid integer for " + option + ": " + value);
            }
        }
    }
}
