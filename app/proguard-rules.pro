# usb-serial-for-android uses reflection-free drivers, but keep the driver surface to be safe
# when R8 shrinks the release build.
-keep class com.hoho.android.usbserial.** { *; }
-dontwarn com.hoho.android.usbserial.**

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
