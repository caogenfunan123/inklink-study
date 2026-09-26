# Add project specific ProGuard rules here.

# 腾讯地图 SDK 混淆白名单（release 开启 minify 时必须保留，否则地图黑屏/崩溃）
-keep class com.tencent.map.** { *; }
-keep class com.tencent.tencentmap.** { *; }
-dontwarn com.tencent.map.**
-dontwarn com.tencent.tencentmap.**
