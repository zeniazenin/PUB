# Rhino instantiates its standard objects, regexp engine and runtime helpers by class name
# (LazilyLoadedCtor, Kit.classOrNull), so its classes must keep their names and members.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
