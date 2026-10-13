# Методы моста вызываются только из JavaScript — R8 не видит этих вызовов и вырезал бы их
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# Имена наших классов остаются читаемыми в отчётах о падениях
-keepnames class net.hdrezka.tv.** { *; }
