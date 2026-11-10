-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# [ФИКС] Правила для kotlinx.serialization.
# Без них R8 при minify вырежет сгенерированные сериализаторы,
# и JSON-программы перестанут читаться/писаться на релизной сборке.

# Сохраняем Companion у всех @Serializable-классов.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Сохраняем метод serializer() у Companion-объектов.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Сохраняем INSTANCE.serializer() у @Serializable-объектов (object ...).
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Сохраняем аннотации, по которым kotlinx.serialization ищет @SerialName и пр.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
