# app_process resolves this entry point by name from the installed APK.
-keep public class dev.op13.regionunlock.RegionUnlock {
    public static void main(java.lang.String[]);
}

-keepclassmembers class dev.op13.regionunlock.app.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
