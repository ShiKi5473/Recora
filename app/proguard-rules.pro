# 本 App 自己的程式沒有用反射；OkHttp、Media3、MapLibre、WayMap、Coil、AndroidX、coroutines
# 都附有自己的 consumer rules。release 實測缺什麼再加在這裡。

# 閃退回報的堆疊保留行號（檔名改成 SourceFile）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
