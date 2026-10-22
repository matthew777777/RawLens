# RawLens R8 rules (every release build; instrumented tests run the
# unminified deviceTest build type). The default
# proguard-android-optimize.txt covers manifest components, AndroidX,
# annotations, and enum/serialization machinery; only app-specific
# reflection-adjacent cases need rules here.
#
# Audited 2026-10: no Class.forName/getMethod string reflection, no
# Parcelable/Serializable, no JNI_OnLoad (all JNI resolves by Dalvik
# name), no reverse-JNI into app classes (native touches only JDK and
# android.* framework methods). Re-audit if any of that changes.

# JNI: every native method resolves by its Dalvik class+method name at
# runtime, so the declaring classes, the native method names, and their
# descriptor types must survive shrinking and renaming. Unconditional
# keep (not ...names): R8 cannot see JNI reachability, so anything less
# lets it strip live entry points.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# Custom views inflated from res/layout XML by name (LayoutInflater
# reflects on the class name); keep them plus the AttributeSet
# constructors (whether or not R8's own layout analysis covers them).
-keepclasseswithmembers class com.matthew.rawlens.* extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
