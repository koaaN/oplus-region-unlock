package dev.op15.regionunlock;

import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.Method;

/**
 * Root/app_process client for Oplus' stable radio AIDL service.
 *
 * This deliberately does not call setCallback(): the radio HAL has one callback
 * pair owned by the phone process, and replacing it can disrupt telephony.
 */
public final class RegionUnlock {
    private static final String SERVICE_PREFIX =
            "vendor.oplus.hardware.radio.IRadioStable/OplusRadio";
    private static final String DESCRIPTOR =
            "vendor.oplus.hardware.radio.IOplusRadio";
    private static final String TELEPHONY_SERVICE = "oplus_telephony_ext";
    private static final String TELEPHONY_INTERFACE =
            "com.android.internal.telephony.IOplusTelephonyExt";
    private static final int TRANSACTION_UPDATE_REGIONLOCK_STATUS = 13;
    private static final int OPERATION_AUTO_UNLOCK = 2;
    private static final int DATA_AUTO_UNLOCK = 0;

    private RegionUnlock() {}

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help) {
                usage();
                return;
            }

            if (options.status) {
                FrameworkState state = readFrameworkState();
                if (state == null) {
                    fail(6, "The phone process has no initialized region-lock state");
                } else {
                    System.out.println("framework-state: " + state);
                }
                return;
            }

            String serviceName = SERVICE_PREFIX + options.slot;
            IBinder binder = waitForService(serviceName, options.waitSeconds);
            if (binder == null) {
                fail(2, "Service not found after " + options.waitSeconds + "s: " + serviceName);
                return;
            }

            String actualDescriptor = binder.getInterfaceDescriptor();
            if (!DESCRIPTOR.equals(actualDescriptor)) {
                fail(3, "Unexpected Binder descriptor: " + actualDescriptor
                        + " (expected " + DESCRIPTOR + ")");
                return;
            }

            System.out.println("service=" + serviceName);
            System.out.println("descriptor=" + actualDescriptor);
            if (options.probe) {
                System.out.println("OK: service is present and has the expected interface");
                return;
            }

            int operator = options.operator != null ? options.operator : readOperatorOrDefault();
            int serial = options.serial != null ? options.serial : makeSerial();
            Parcel request = Parcel.obtain();
            boolean accepted;
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                request.writeInt(serial);
                request.writeByte((byte) operator);
                request.writeByte((byte) OPERATION_AUTO_UNLOCK);
                request.writeByte((byte) DATA_AUTO_UNLOCK);
                accepted = binder.transact(
                        TRANSACTION_UPDATE_REGIONLOCK_STATUS,
                        request,
                        null,
                        IBinder.FLAG_ONEWAY);
            } finally {
                request.recycle();
            }

            if (!accepted) {
                fail(4, "Binder rejected transaction 13 as unimplemented");
                return;
            }

            System.out.println("AUTO_UNLOCK queued: serial=" + serial
                    + " operator=" + operator + " operation=2 data=0");
            System.out.println("Note: this confirms Binder acceptance, not the asynchronous modem result.");

            // The stock phone callback may update its cached state after the
            // asynchronous modem response or an unsolicited indication. This is
            // useful evidence when available, but an unchanged cache is not proof
            // that the modem rejected the operation.
            Thread.sleep(1000L);
            try {
                FrameworkState state = readFrameworkState();
                if (state != null) {
                    System.out.println("framework-state-after-1s: " + state);
                }
            } catch (Throwable t) {
                System.err.println("warning: could not read post-request framework state: " + rootMessage(t));
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            usage();
            System.exit(64);
        } catch (SecurityException e) {
            System.err.println("permission denied: " + e);
            System.err.println("Run as root; if SELinux denied the call, install the module policy or inspect dmesg/logcat.");
            System.exit(5);
        } catch (Throwable t) {
            System.err.println("region-unlock failed: " + t);
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static IBinder waitForService(String name, int waitSeconds) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + waitSeconds * 1000L;
        do {
            IBinder binder = getService(name);
            if (binder != null && binder.pingBinder()) {
                return binder;
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return null;
            }
            Thread.sleep(250L);
        } while (true);
    }

    private static IBinder getService(String name) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        return (IBinder) getService.invoke(null, name);
    }

    private static int readOperatorOrDefault() {
        try {
            FrameworkState state = readFrameworkState();
            if (state != null && state.operator >= 0 && state.operator <= 255) {
                System.out.println("framework-state-before: " + state);
                System.out.println("Using current framework operator=" + state.operator);
                return state.operator;
            }
            System.err.println("warning: no initialized framework state; using operator=0 fallback");
        } catch (Throwable t) {
            System.err.println("warning: operator auto-detection failed; using operator=0: " + rootMessage(t));
        }
        return 0;
    }

    private static FrameworkState readFrameworkState() throws Exception {
        IBinder binder = getService(TELEPHONY_SERVICE);
        if (binder == null || !binder.pingBinder()) {
            throw new IllegalStateException("service is unavailable: " + TELEPHONY_SERVICE);
        }
        Class<?> interfaceClass = Class.forName(TELEPHONY_INTERFACE);
        Class<?> stubClass = Class.forName(TELEPHONY_INTERFACE + "$Stub");
        Object telephony = stubClass.getMethod("asInterface", IBinder.class).invoke(null, binder);
        Object state = interfaceClass.getMethod("getRegionLockState", String.class)
                .invoke(telephony, "");
        if (state == null) {
            return null;
        }
        Class<?> stateClass = state.getClass();
        String operator = (String) stateClass.getMethod("getOperator").invoke(state);
        String operation = (String) stateClass.getMethod("getOperationType").invoke(state);
        String lockState = (String) stateClass.getMethod("getState").invoke(state);
        int result = (Integer) stateClass.getMethod("getResult").invoke(state);
        return new FrameworkState(parseOptionalByte(operator), operation, lockState, result);
    }

    private static int parseOptionalByte(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value) & 0xff;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.toString();
    }

    // RIL request serials are normally allocated inside the phone process. Use a
    // high, changing positive value so its asynchronous response is very unlikely
    // to collide with a live framework request.
    private static int makeSerial() {
        return 0x7f000000 | ((int) SystemClock.elapsedRealtime() & 0x00ffffff);
    }

    private static void fail(int status, String message) {
        System.err.println("error: " + message);
        System.exit(status);
    }

    private static void usage() {
        System.out.println("Usage: region-unlock [--slot 0|1] [--operator auto|0..255]");
        System.out.println("                     [--serial 1..2147483647] [--wait seconds]");
        System.out.println("                     [--probe | --status]");
        System.out.println("Queues updateRegionlockStatus(serial, operator, 2, 0) on the selected radio HAL.");
    }

    private static final class FrameworkState {
        final int operator;
        final String operation;
        final String state;
        final int result;

        FrameworkState(int operator, String operation, String state, int result) {
            this.operator = operator;
            this.operation = operation;
            this.state = state;
            this.result = result;
        }

        @Override
        public String toString() {
            return "operator=" + (operator >= 0 ? Integer.toString(operator) : "unknown")
                    + " operation=" + operation + " state=" + state + " result=" + result;
        }
    }

    private static final class Options {
        int slot = 0;
        Integer operator;
        Integer serial;
        int waitSeconds = 15;
        boolean probe;
        boolean status;
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
                } else if ("--slot".equals(arg)) {
                    options.slot = parseInt(value(args, ++i, arg), arg);
                } else if ("--operator".equals(arg)) {
                    String operator = value(args, ++i, arg);
                    options.operator = "auto".equals(operator) ? null : parseInt(operator, arg);
                } else if ("--serial".equals(arg)) {
                    options.serial = parseInt(value(args, ++i, arg), arg);
                } else if ("--wait".equals(arg)) {
                    options.waitSeconds = parseInt(value(args, ++i, arg), arg);
                } else {
                    throw new IllegalArgumentException("unknown argument: " + arg);
                }
            }
            if (options.slot < 0 || options.slot > 1) {
                throw new IllegalArgumentException("--slot must be 0 or 1");
            }
            if (options.operator != null && (options.operator < 0 || options.operator > 255)) {
                throw new IllegalArgumentException("--operator must be from 0 through 255");
            }
            if (options.serial != null && options.serial <= 0) {
                throw new IllegalArgumentException("--serial must be positive");
            }
            if (options.waitSeconds < 0 || options.waitSeconds > 300) {
                throw new IllegalArgumentException("--wait must be from 0 through 300 seconds");
            }
            if (options.probe && options.status) {
                throw new IllegalArgumentException("--probe and --status are mutually exclusive");
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
