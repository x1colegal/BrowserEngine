import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

plugins {
    id("com.android.application") version "9.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

abstract class AdaptivePlayback720Factory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor = object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            return when {
                classContext.currentClassData.className == "org.mozilla.gecko.media.Codec" &&
                    name == "setupAdaptivePlayback" -> object : MethodVisitor(Opcodes.ASM9, mv) {
                    private fun emitFormatDimOrDefault(key: String, fallback: Int) {
                        val elseLabel = Label()
                        val endLabel = Label()
                        super.visitVarInsn(Opcodes.ALOAD, 2)
                        super.visitLdcInsn(key)
                        super.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "android/media/MediaFormat",
                            "containsKey",
                            "(Ljava/lang/String;)Z",
                            false
                        )
                        super.visitJumpInsn(Opcodes.IFEQ, elseLabel)
                        super.visitVarInsn(Opcodes.ALOAD, 2)
                        super.visitLdcInsn(key)
                        super.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "android/media/MediaFormat",
                            "getInteger",
                            "(Ljava/lang/String;)I",
                            false
                        )
                        super.visitJumpInsn(Opcodes.GOTO, endLabel)
                        super.visitLabel(elseLabel)
                        super.visitIntInsn(Opcodes.SIPUSH, fallback)
                        super.visitLabel(endLabel)
                    }

                    override fun visitIntInsn(opcode: Int, operand: Int) {
                        when {
                            opcode == Opcodes.SIPUSH && operand == 1920 -> emitFormatDimOrDefault("width", 1280)
                            opcode == Opcodes.SIPUSH && operand == 1080 -> emitFormatDimOrDefault("height", 720)
                            else -> super.visitIntInsn(opcode, operand)
                        }
                    }
                }
                classContext.currentClassData.className == "org.mozilla.gecko.process.GeckoProcessManager" &&
                    name == "getSurfaceAllocator" -> object : MethodVisitor(Opcodes.ASM9, mv) {
                    override fun visitLdcInsn(value: Any?) {
                        super.visitLdcInsn(if (value == 100L) 5000L else value)
                    }
                }
                else -> mv
            }
        }
    }

    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className == "org.mozilla.gecko.media.Codec" ||
            classData.className == "org.mozilla.gecko.process.GeckoProcessManager"
}

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(
            AdaptivePlayback720Factory::class.java,
            InstrumentationScope.ALL
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
        )
    }
}

android {
    namespace = "com.x1colegal.browserengine"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.x1colegal.browserengine"
        minSdk = 21
        targetSdk = 36
        versionCode = 29
        versionName = "2.10"
    }
    flavorDimensions += "engine"
    productFlavors {
        create("legacy") {
            dimension = "engine"
            minSdk = 21
            versionNameSuffix = "-legacy"
            buildConfigField("String", "GECKOVIEW_MAJOR", "\"143\"")
        }
        create("modern") {
            dimension = "engine"
            minSdk = 26
            versionNameSuffix = "-modern"
            buildConfigField("String", "GECKOVIEW_MAJOR", "\"163\"")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += setOf("META-INF/versions/**", "META-INF/INDEX.LIST")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.work:work-runtime:2.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    "legacyImplementation"("org.mozilla.geckoview:geckoview:143.0.20251003115653")
    "modernImplementation"("org.mozilla.geckoview:geckoview-nightly:+")
    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-dash:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")
}
