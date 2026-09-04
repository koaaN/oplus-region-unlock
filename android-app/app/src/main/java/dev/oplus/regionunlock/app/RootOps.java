package dev.oplus.regionunlock.app;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RootOps {
    private static final String MAIN_CLASS = "dev.oplus.regionunlock.RegionUnlock";
    private static final String SU = "/system/bin/su";
    private static final String ONEPLUS_13_PROJECT_ID = "23821";
    private static final String ONEPLUS_13T_PROJECT_ID = "24821";
    private static final String ONEPLUS_15_PROJECT_ID = "24831";
    private static final String ONEPLUS_ACE_5_PRO_PROJECT_ID = "24811";
    private static final String ONEPLUS_ACE_5_PROJECT_ID = "23851";
    private static final String ONEPLUS_ACE_6_PROJECT_ID = "24851";
    private static final String ONEPLUS_ACE_6T_PROJECT_ID = "24855";
    private static final Pattern STATE_PATTERN = Pattern.compile("\\bstate=(-?\\d+)\\b");
    private static final Pattern OPERATOR_PATTERN = Pattern.compile("\\boperator=(-?\\d+)\\b");
    private static final Pattern OPERATION_PATTERN = Pattern.compile("\\boperation=(-?\\d+)\\b");
    private static final Pattern RESULT_PATTERN = Pattern.compile("\\bresult=(-?\\d+)\\b");
    private static final Pattern REGION_PATTERN = Pattern.compile("\\bregion=([^\\s]+)");
    private static final Pattern BRAND_PATTERN = Pattern.compile("\\bbrand=([^\\s]+)");
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\bversion=([^\\s]+)");

    private RootOps() {
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    private static CommandResult command(long timeoutSeconds, String... arguments) throws Exception {
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            throw new IOException("Command timed out after " + timeoutSeconds + " seconds.");
        }
        return new CommandResult(process.exitValue(), readAll(process.getInputStream()));
    }

    private static JSONObject response(boolean ok, String message, String log) throws Exception {
        JSONObject result = new JSONObject().put("ok", ok).put("message", message);
        if (log != null && !log.isEmpty()) {
            result.put("log", log);
        }
        return result;
    }

    private static String property(String name) {
        try {
            CommandResult result = command(5, "/system/bin/getprop", name);
            return result.exitCode == 0 ? result.output.trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String projectId() {
        for (String name : new String[]{
                "ro.boot.prjname", "ro.boot.project_name", "ro.boot.prjid",
                "ro.boot.project_id", "ro.product.prjname"
        }) {
            String value = property(name);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String marketingName(String project) {
        if (ONEPLUS_13_PROJECT_ID.equals(project)) {
            return "OnePlus 13";
        }
        if (ONEPLUS_13T_PROJECT_ID.equals(project)) {
            return "OnePlus 13T";
        }
        if (ONEPLUS_15_PROJECT_ID.equals(project)) {
            return "OnePlus 15";
        }
        if (ONEPLUS_ACE_5_PRO_PROJECT_ID.equals(project)) {
            return "OnePlus Ace 5 Pro";
        }
        if (ONEPLUS_ACE_5_PROJECT_ID.equals(project)) {
            return "OnePlus Ace 5";
        }
        if (ONEPLUS_ACE_6_PROJECT_ID.equals(project)) {
            return "OnePlus Ace 6";
        }
        if (ONEPLUS_ACE_6T_PROJECT_ID.equals(project)) {
            return "OnePlus Ace 6T";
        }
        return "";
    }

    private static String deviceName(String project, String fallback) {
        if (ONEPLUS_13_PROJECT_ID.equals(project)) {
            return "PJZ110 / OnePlus 13";
        }
        if (ONEPLUS_13T_PROJECT_ID.equals(project)) {
            return "OnePlus 13T";
        }
        if (ONEPLUS_15_PROJECT_ID.equals(project)) {
            return "PLK110 / OnePlus 15";
        }
        if (ONEPLUS_ACE_5_PRO_PROJECT_ID.equals(project)) {
            return "OnePlus Ace 5 Pro";
        }
        if (ONEPLUS_ACE_5_PROJECT_ID.equals(project)) {
            return "OnePlus Ace 5";
        }
        if (ONEPLUS_ACE_6_PROJECT_ID.equals(project)) {
            return "OnePlus Ace 6";
        }
        if (ONEPLUS_ACE_6T_PROJECT_ID.equals(project)) {
            return "OnePlus Ace 6T";
        }
        return fallback;
    }

    static String deviceInfo() {
        try {
            String model = Build.MANUFACTURER + " " + Build.MODEL;
            String project = projectId();
            String marketingName = marketingName(project);
            return new JSONObject()
                    .put("model", model.trim())
                    .put("projectId", project)
                    .put("knownProject", !marketingName.isEmpty())
                    .put("deviceName", deviceName(project, model.trim()))
                    .put("marketingName", marketingName)
                    .put("android", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .toString();
        } catch (Exception ignored) {
            return "{}";
        }
    }

    static String rootCheck() {
        try {
            CommandResult result = command(30, SU, "-c", "id -u");
            boolean available = result.exitCode == 0 && "0".equals(result.output.trim());
            return response(
                    available,
                    available ? "Root access is available." : "Root access was not granted.",
                    available ? "uid=0" : result.output).toString();
        } catch (Throwable error) {
            return failure("Root check failed", error);
        }
    }

    private static CommandResult runClient(Context context, String action, int timeoutSeconds)
            throws Exception {
        String apk = context.getApplicationInfo().sourceDir;
        String client = "CLASSPATH=" + shellQuote(apk)
                + " exec /system/bin/app_process /system/bin " + MAIN_CLASS
                + " --slot 0 --wait " + timeoutSeconds + " " + action;
        // OPlus SubsysPermissions rejects UID 0. Root is used only to transition
        // the client to Android system UID 1000, matching the stock service.
        return command(timeoutSeconds + 20L, SU, "1000", "-c", client);
    }

    private static int parseState(String output) {
        return parseLastInt(STATE_PATTERN, output);
    }

    private static int parseLastInt(Pattern pattern, String output) {
        Matcher matcher = pattern.matcher(output == null ? "" : output);
        int state = Integer.MIN_VALUE;
        while (matcher.find()) {
            try {
                state = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return state;
    }

    private static String parseLastText(Pattern pattern, String output) {
        Matcher matcher = pattern.matcher(output == null ? "" : output);
        String value = "";
        while (matcher.find()) {
            value = matcher.group(1);
        }
        return value;
    }

    private static String stateName(int state) {
        switch (state) {
            case -1: return "Invalid/test locked";
            case 0: return "Auto-unlocked";
            case 1: return "Locked";
            case 2: return "Sale-unlocked";
            case 3: return "Server-locked";
            case 4: return "Server-unlocked";
            case 5: return "Locally unlocked";
            default: return "Unknown";
        }
    }

    private static boolean isSupportedRegion(String region) {
        return "CN".equalsIgnoreCase(region == null ? "" : region.trim());
    }

    private static JSONObject regionGateFailure(String action, CommandResult status)
            throws Exception {
        String region = parseLastText(REGION_PATTERN, status.output);
        if (status.exitCode != 0) {
            return response(false,
                    "Could not verify region support. " + action + " was not sent.",
                    status.output)
                    .put("supported", false)
                    .put("region", region)
                    .put("exitCode", status.exitCode);
        }
        return response(false,
                "Region CN was not reported. " + action + " was not sent.",
                status.output)
                .put("supported", false)
                .put("region", region);
    }

    static String status(Context context) {
        try {
            CommandResult command = runClient(context, "--status", 30);
            if (command.exitCode != 0) {
                return response(false, "Could not read the region-lock state.", command.output)
                        .put("exitCode", command.exitCode).toString();
            }
            int state = parseState(command.output);
            String region = parseLastText(REGION_PATTERN, command.output);
            boolean unlocked = state == 0 || state == 2 || state == 4 || state == 5;
            return response(true, stateName(state), command.output)
                    .put("state", state)
                    .put("operator", parseLastInt(OPERATOR_PATTERN, command.output))
                    .put("operation", parseLastInt(OPERATION_PATTERN, command.output))
                    .put("result", parseLastInt(RESULT_PATTERN, command.output))
                    .put("region", region)
                    .put("brand", parseLastText(BRAND_PATTERN, command.output))
                    .put("version", parseLastText(VERSION_PATTERN, command.output))
                    .put("unlocked", unlocked)
                    .put("supported", isSupportedRegion(region))
                    .toString();
        } catch (Throwable error) {
            return failure("Status check failed", error);
        }
    }

    static String policy(Context context) {
        return diagnostic(context, "--policy", "Policy read successfully.");
    }

    static String settings(Context context) {
        return diagnostic(context, "--settings", "Settings read successfully.");
    }

    private static String diagnostic(Context context, String action, String success) {
        try {
            CommandResult command = runClient(context, action, 30);
            return response(command.exitCode == 0,
                    command.exitCode == 0 ? success : "Diagnostic command failed.",
                    command.output)
                    .put("exitCode", command.exitCode)
                    .toString();
        } catch (Throwable error) {
            return failure("Diagnostic failed", error);
        }
    }

    static String unlock(Context context) {
        try {
            CommandResult status = runClient(context, "--status", 30);
            String region = parseLastText(REGION_PATTERN, status.output);
            if (status.exitCode != 0 || !isSupportedRegion(region)) {
                return regionGateFailure("AUTO_UNLOCK", status).toString();
            }
            CommandResult command = runClient(context, "--auto-unlock", 60);
            if (command.exitCode != 0) {
                return response(false, "AUTO_UNLOCK was not accepted.", command.output)
                        .put("exitCode", command.exitCode).toString();
            }
            return response(true,
                    "Unlock request accepted. Reboot the phone to apply state 0.",
                    command.output)
                    .put("accepted", true)
                    .put("supported", true)
                    .put("region", region)
                    .put("requiresReboot", true)
                    .toString();
        } catch (Throwable error) {
            return failure("Unlock failed", error);
        }
    }

    static String lock(Context context, String confirmation) {
        try {
            if (!"LOCK".equals(confirmation)) {
                return response(false,
                        "Lock confirmation was rejected.",
                        "Enter the exact uppercase word LOCK. No command was sent.")
                        .toString();
            }
            CommandResult status = runClient(context, "--status", 30);
            String region = parseLastText(REGION_PATTERN, status.output);
            if (status.exitCode != 0 || !isSupportedRegion(region)) {
                return regionGateFailure("LOCK_STATE", status).toString();
            }
            CommandResult command = runClient(context, "--lock-state", 60);
            if (command.exitCode != 0) {
                return response(false, "LOCK_STATE was not accepted.", command.output)
                        .put("exitCode", command.exitCode).toString();
            }
            return response(true,
                    "Lock request accepted. Reboot the phone to apply state 1.",
                    command.output)
                    .put("accepted", true)
                    .put("supported", true)
                    .put("region", region)
                    .put("targetState", 1)
                    .put("requiresReboot", true)
                    .toString();
        } catch (Throwable error) {
            return failure("Lock failed", error);
        }
    }

    static void reboot() throws IOException {
        new ProcessBuilder(
                SU, "-c",
                "sync; sleep 1; /system/bin/setprop sys.powerctl reboot")
                .redirectErrorStream(true)
                .start();
    }

    private static String failure(String prefix, Throwable error) {
        try {
            String detail = error.getMessage();
            if (detail == null || detail.trim().isEmpty()) {
                detail = error.toString();
            }
            return response(false, prefix + ".", detail).toString();
        } catch (Exception ignored) {
            return "{\"ok\":false,\"message\":\"Operation failed.\"}";
        }
    }
}
