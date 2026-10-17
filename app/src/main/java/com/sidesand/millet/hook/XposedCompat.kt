package com.sidesand.millet.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 基于 LSPosed Modern API 102 (io.github.libxposed:api:102.0.0) 的 Hook 与反射工具层，
 * 完全替代已废弃的 Legacy Xposed API 82 (XposedBridge / XposedHelpers / XC_MethodHook)。
 */
object XposedCompat {

    @Volatile
    var module: XposedModule? = null
        private set

    fun init(mod: XposedModule) {
        module = mod
    }

    class MethodHookParam(
        val thisObject: Any?,
        originalArgs: List<Any?>
    ) {
        private val initialArgs: Array<Any?> = originalArgs.toTypedArray()
        val args: Array<Any?> = originalArgs.toTypedArray()

        var result: Any? = null
            set(value) {
                field = value
                returnEarly = true
            }

        var throwable: Throwable? = null
            set(value) {
                field = value
                if (value != null) {
                    returnEarly = true
                }
            }

        var returnEarly: Boolean = false
            internal set

        internal fun isArgsModified(): Boolean {
            if (args.size != initialArgs.size) return true
            for (i in args.indices) {
                if (args[i] !== initialArgs[i]) return true
            }
            return false
        }
    }

    open class MethodHook {
        open fun beforeHookedMethod(param: MethodHookParam) {}
        open fun afterHookedMethod(param: MethodHookParam) {}
    }

    val DO_NOTHING: MethodHook = object : MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
        }
    }

    fun log(priority: Int, tag: String, msg: String, tr: Throwable? = null) {
        val mod = module
        if (mod != null) {
            try {
                if (tr != null) {
                    mod.log(priority, tag, msg, tr)
                } else {
                    mod.log(priority, tag, msg)
                }
                return
            } catch (_: Throwable) {
            }
        }
        val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        Log.println(priority, tag, fullMsg)
    }

    private fun invokeHook(chain: XposedInterface.Chain, hook: MethodHook): Any? {
        val param = MethodHookParam(chain.thisObject, chain.args)
        try {
            hook.beforeHookedMethod(param)
        } catch (t: Throwable) {
            SMilletLogger.error("beforeHookedMethod 异常", t)
            return chain.proceed()
        }

        if (param.returnEarly) {
            param.throwable?.let { throw it }
            return param.result
        }

        try {
            val res = if (param.isArgsModified()) {
                chain.proceed(param.args)
            } else {
                chain.proceed()
            }
            param.result = res
            param.returnEarly = false
        } catch (t: Throwable) {
            param.throwable = t
            param.returnEarly = false
        }

        try {
            hook.afterHookedMethod(param)
        } catch (t: Throwable) {
            SMilletLogger.error("afterHookedMethod 异常", t)
        }

        param.throwable?.let { throw it }
        return param.result
    }

    fun hookMethod(method: Method, hook: MethodHook) {
        val mod = module ?: return
        runCatching {
            method.isAccessible = true
            mod.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> invokeHook(chain, hook) }
        }.onFailure {
            SMilletLogger.error("hookMethod 失败: ${method.declaringClass.name}#${method.name}", it)
        }
    }

    fun hookConstructor(ctor: Constructor<*>, hook: MethodHook) {
        val mod = module ?: return
        runCatching {
            ctor.isAccessible = true
            mod.hook(ctor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> invokeHook(chain, hook) }
        }.onFailure {
            SMilletLogger.error("hookConstructor 失败: ${ctor.declaringClass.name}", it)
        }
    }

    fun hookAllMethods(clazz: Class<*>, methodName: String, hook: MethodHook): Int {
        var count = 0
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (method.name == methodName && !Modifier.isAbstract(method.modifiers)) {
                    hookMethod(method, hook)
                    count++
                }
            }
            if (count > 0) break
            current = current.superclass
        }
        return count
    }

    fun hookAllConstructors(clazz: Class<*>, hook: MethodHook): Int {
        var count = 0
        for (ctor in clazz.declaredConstructors) {
            hookConstructor(ctor, hook)
            count++
        }
        return count
    }

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        return try {
            Class.forName(className, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findFieldRecursive(clazz: Class<*>?, fieldName: String): Field? {
        var current = clazz
        while (current != null) {
            try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                return field
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    fun getObjectField(obj: Any, fieldName: String): Any? {
        val field = findFieldRecursive(obj.javaClass, fieldName)
            ?: throw NoSuchFieldException("${obj.javaClass.name}#$fieldName")
        return field.get(obj)
    }

    fun setObjectField(obj: Any, fieldName: String, value: Any?) {
        val field = findFieldRecursive(obj.javaClass, fieldName)
            ?: throw NoSuchFieldException("${obj.javaClass.name}#$fieldName")
        field.set(obj, value)
    }

    fun getIntField(obj: Any, fieldName: String): Int {
        val field = findFieldRecursive(obj.javaClass, fieldName)
            ?: throw NoSuchFieldException("${obj.javaClass.name}#$fieldName")
        return field.getInt(obj)
    }

    fun setBooleanField(obj: Any, fieldName: String, value: Boolean) {
        val field = findFieldRecursive(obj.javaClass, fieldName)
            ?: throw NoSuchFieldException("${obj.javaClass.name}#$fieldName")
        field.setBoolean(obj, value)
    }

    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? {
        val field = findFieldRecursive(clazz, fieldName)
            ?: throw NoSuchFieldException("${clazz.name}#$fieldName")
        return field.get(null)
    }

    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) {
        val field = findFieldRecursive(clazz, fieldName)
            ?: throw NoSuchFieldException("${clazz.name}#$fieldName")
        field.set(null, value)
    }

    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        val method = findMatchingMethod(obj.javaClass, methodName, args)
            ?: throw NoSuchMethodException("${obj.javaClass.name}#$methodName")
        method.isAccessible = true
        return method.invoke(obj, *args)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        val method = findMatchingMethod(clazz, methodName, args)
            ?: throw NoSuchMethodException("${clazz.name}#$methodName")
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        for (ctor in clazz.declaredConstructors) {
            if (isParameterTypesCompatible(ctor.parameterTypes, args)) {
                ctor.isAccessible = true
                return ctor.newInstance(*args)
            }
        }
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    private fun findMatchingMethod(clazz: Class<*>?, methodName: String, args: Array<out Any?>): Method? {
        var current = clazz
        while (current != null) {
            for (m in current.declaredMethods) {
                if (m.name == methodName && isParameterTypesCompatible(m.parameterTypes, args)) {
                    return m
                }
            }
            current = current.superclass
        }
        // Fallback: match by method name and parameter count
        current = clazz
        while (current != null) {
            for (m in current.declaredMethods) {
                if (m.name == methodName && m.parameterTypes.size == args.size) {
                    return m
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun isParameterTypesCompatible(paramTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (paramTypes.size != args.size) return false
        for (i in paramTypes.indices) {
            val arg = args[i]
            val pt = boxedType(paramTypes[i])
            if (arg == null) {
                if (paramTypes[i].isPrimitive) return false
            } else if (!pt.isAssignableFrom(boxedType(arg.javaClass))) {
                return false
            }
        }
        return true
    }

    private fun boxedType(type: Class<*>): Class<*> {
        if (!type.isPrimitive) return type
        return when (type) {
            Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
            Int::class.javaPrimitiveType -> java.lang.Integer::class.java
            Long::class.javaPrimitiveType -> java.lang.Long::class.java
            Float::class.javaPrimitiveType -> java.lang.Float::class.java
            Double::class.javaPrimitiveType -> java.lang.Double::class.java
            Byte::class.javaPrimitiveType -> java.lang.Byte::class.java
            Short::class.javaPrimitiveType -> java.lang.Short::class.java
            Char::class.javaPrimitiveType -> java.lang.Character::class.java
            else -> type
        }
    }
}
