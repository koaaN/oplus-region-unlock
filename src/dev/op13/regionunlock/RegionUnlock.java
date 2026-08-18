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

/** Root/app_process client for the OnePlus 13 region-lock subsystem. */
public final class RegionUnlock {
    private static final String RADIO_MANAGER = "com.oplus.telephony.RadioManager";
    private static final String VENDOR_SERVICE_PREFIX =
            "vendor.oplus.hardware.subsys_interface.subsys_radio.ISubsysRadio/slot";
    private static final String VENDOR_DESCRIPTOR =
            "vendor.oplus.hardware.subsys_interface.subsys_radio.ISubsysRadio";
    private static final int TRANSACTION_SET_REGION_LOCK_STATUS = 294;

    private static final int OPERATION_AUTO_UNLOCK = 2;
    private static final int DATA_UNLOCK = 0;
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
            System.err.println("The OP13 subsystem call must run as Android UID 1000.");
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
        if (options.autoUnlock) {
            autoUnlock();
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
        System.out.println("OK: OP13 region-lock subsystem is available");
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

    private void autoUnlock() throws Exception {
        State before = getState();
        printState("before", before);
        int operator = before.operator >= 0 ? before.operator : 0;
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
        // value=[operator, operation=AUTO_UNLOCK, data=0].
        byte[] tlv = new byte[]{
                (byte) TLV_TAG_STATUS, 0, 3,
                (byte) operator, (byte) OPERATION_AUTO_UNLOCK, (byte) DATA_UNLOCK
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
        System.out.println("AUTO_UNLOCK queued: serial=" + serial
                + " operator=" + operator + " operation=2 data=0");
        System.out.println("transport=OP13 subsystem-radio TLV tag 1");
        Thread.sleep(1500L);
        printState("after", getState());
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
        System.out.println("  --probe                 Validate OP13 services and read current state");
        System.out.println("  --status                Read current region-lock state");
        System.out.println("  --auto-unlock           Send stock AUTO_UNLOCK; successful state is 0");
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

    private static final class Options {
        int slot;
        int waitSeconds = 15;
        boolean probe;
        boolean status;
        boolean autoUnlock;
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
                } else if ("--auto-unlock".equals(arg)) {
                    options.autoUnlock = true;
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
                    + (options.autoUnlock ? 1 : 0)
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
