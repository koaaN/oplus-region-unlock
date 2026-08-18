# app_process resolves this entry point by name from the installed APK.
-keep public class dev.oplus.regionunlock.RegionUnlock {
    public static void main(java.lang.String[]);
}

-keepclassmembers class dev.oplus.regionunlock.app.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
