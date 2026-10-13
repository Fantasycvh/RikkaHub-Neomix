# app 模块的混淆规则。
#
# ## 为什么这个文件必须存在（2026-10-13 真机事故）
#
# 上游 `de3399ba3`「build: 简化 keep rules」删掉了 `ai/proguard-rules.pro` 与
# `app/proguard-rules.pro`，**却没有把里面的 kotlinx.serialization 规则补到别处**
# （`ai/consumer-rules.pro` 被清成 0 字节，各模块的 build.gradle.kts 也都不再引用规则文件）。
# 于是 release 构建里 `@Serializable` 类会被 R8 重命名/裁剪，而 kotlinx.serialization 仍要按
# 名字找序列化器 ⇒ 运行时报：
#
#     Serializer for class 'xxx' is not found.
#     Please ensure that class is marked as '@Serializable' and that the serialization compiler plugin is applied.
#
# 真机表现：聊天流式落库的检查点（`ConversationRepository.checkpointGeneratingNode`
# → `JsonInstant.encodeToString(node.messages)`）抛异常，日志里是
# `ChatService: stream checkpoint failed`。**debug 构建完全正常**——这就是它只在 release 暴露的原因。
#
# ## 只恢复必要的规则，不恢复上游那条 -dontobfuscate
#
# 上游原来还有一条 `-dontobfuscate`（全局关混淆）。那等于放弃 R8 的体积收益，
# 与「简化 keep rules」的意图相反。真正必需的是**保住可序列化类**，所以这里只 keep
# `@Serializable` 及其生成的 `$$serializer` / `Companion`，其余照常混淆。

# kotlinx.serialization：保住可序列化类本身（含其合成的 Companion / $$serializer）。
-keep @kotlinx.serialization.Serializable class * {*;}

# 多态序列化（本仓大量使用 sealed class + @SerialName，如 UIMessagePart / Avatar /
# ToolApprovalState）在运行时要按子类查找序列化器，子类也必须保住。
-keep class * extends kotlinx.serialization.KSerializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# 生成的 `$$serializer` 是被反射查找的（`serializer()` / `PolymorphicSerializer` 都按名字找），
# 上面按「实现 KSerializer」的 keep 在某些 R8 版本下仍可能被内联/裁剪掉外壳 —— 直接按名字兜住。
-keep class **$$serializer { *; }
-keepclassmembers class * {
    *** $serializer;
}

# `@SerialName` / `@SerialInfo` 等注解本身及其在「多态序列化按名字找子类」时的可读性。
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature

# ⚠️ 兜底：**不做混淆**。
#
# 上游 `de3399ba3`「简化 keep rules」删除的规则里就有这一条。实测（2026-10-13，release 真机）：
# 只恢复上面那些针对性 keep 之后，仍会周期性报
#   `Serializer for class 'XXX' is not found`（XXX 是 R8 混淆名，如 qka/rka，
#   mapping 还原后是 okhttp3.RequestBody / io.ktor.client.request.RequestBodyKt），
# 并在 `ChatService: handleMessageComplete` 处中断 —— 与最初的事故同形。
#
# 也就是说：在本仓当前依赖组合（ktor + okhttp + kotlinx-serialization + 大量多态 sealed 类）下，
# 针对性 keep **不足以**保证 kotlinx.serialization 的运行时查找成立；混淆会把某些按名字查找的
# 类型改掉。保体积不如保正确：这里直接关掉混淆（上游原本也是这么做的），
# R8 的裁剪/优化仍然生效，只是类名保持原样。
-dontobfuscate

# 便于把线上堆栈还原成源码行（上游原本也有这条；删掉后 mapping 仍可反混淆类名，
# 但拿不到行号，排查成本高得多）。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
