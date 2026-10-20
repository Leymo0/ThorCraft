package dev.leymo.thorcraft.cobble;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small reflection helper for talking to Cobblemon without compiling against it.
 * Every lookup tries several spellings (Kotlin getter, "is" getter, mangled internal name, raw field)
 * and returns null instead of throwing, so a renamed member only disables one feature.
 */
public final class R {
    private R() {}

    private static final Map<String, Optional<Class<?>>> CLASSES = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();

    public static Class<?> cls(String name) {
        return CLASSES.computeIfAbsent(name, n -> {
            try {
                return Optional.of(Class.forName(n));
            } catch (Throwable t) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Kotlin `object` singleton (static INSTANCE field). */
    public static Object kobject(String className) {
        Class<?> c = cls(className);
        if (c == null) return null;
        try {
            Field f = c.getField("INSTANCE");
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Kotlin companion object (static Companion field). */
    public static Object companion(String className) {
        Class<?> c = cls(className);
        if (c == null) return null;
        try {
            return c.getField("Companion").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Read a property: tries getX(), isX(), x(), getX$*(), then a field named x. */
    public static Object get(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (String m : new String[]{"get" + cap, name.startsWith("is") ? name : "is" + cap, name}) {
                Method method = method(target.getClass(), m, 0);
                if (method != null) {
                    try {
                        return method.invoke(target);
                    } catch (Throwable ignored) {
                    }
                }
            }
            Method mangled = mangled(target.getClass(), "get" + cap);
            if (mangled != null) {
                try {
                    return mangled.invoke(target);
                } catch (Throwable ignored) {
                }
            }
            Field f = field(target.getClass(), name);
            if (f != null) {
                try {
                    return f.get(target);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    public static boolean set(Object target, String name, Object value) {
        if (target == null) return false;
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals("set" + cap) && m.getParameterCount() == 1 && accepts(m.getParameterTypes()[0], value)) {
                try {
                    m.invoke(target, value);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        Field f = field(target.getClass(), name);
        if (f != null) {
            try {
                f.set(target, value);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Call a method whose parameters accept the given args. Returns NONE if no such method. */
    public static final Object NONE = new Object();

    public static Object call(Object target, String name, Object... args) {
        if (target == null) return NONE;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            if (!acceptsAll(m.getParameterTypes(), args)) continue;
            try {
                return m.invoke(target, args);
            } catch (Throwable t) {
                return NONE;
            }
        }
        return NONE;
    }

    /**
     * Call a method by name where we know the first few args; remaining parameters are filled with
     * defaults (1 for ints, empty collections, null otherwise). Handles Kotlin default parameters.
     */
    public static Object callFill(Object target, String name, Object... known) {
        if (target == null) return null;
        Method best = null;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() < known.length || m.isSynthetic()) continue;
            Class<?>[] p = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < known.length; i++) if (!accepts(p[i], known[i])) ok = false;
            if (ok && (best == null || m.getParameterCount() < best.getParameterCount())) best = m;
        }
        if (best == null) return null;
        try {
            return best.invoke(target, fill(best.getParameterTypes(), known));
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object newInstance(String className, Object... known) {
        Class<?> c = cls(className);
        if (c == null) return null;
        Constructor<?> best = null;
        for (Constructor<?> k : c.getConstructors()) {
            if (k.isSynthetic() || k.getParameterCount() < known.length) continue;
            Class<?>[] p = k.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < known.length; i++) if (!accepts(p[i], known[i])) ok = false;
            if (p.length > 0 && p[p.length - 1].getName().contains("DefaultConstructorMarker")) ok = false;
            if (ok && (best == null || k.getParameterCount() < best.getParameterCount())) best = k;
        }
        if (best == null) {
            Object singleton = kobject(className);
            return known.length == 0 ? singleton : null;
        }
        try {
            return best.newInstance(fill(best.getParameterTypes(), known));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object[] fill(Class<?>[] p, Object[] known) {
        Object[] args = new Object[p.length];
        for (int i = 0; i < p.length; i++) {
            if (i < known.length) {
                args[i] = known[i];
                continue;
            }
            Class<?> t = p[i];
            if (t == int.class || t == Integer.class) args[i] = 1;
            else if (t == float.class || t == Float.class) args[i] = 1f;
            else if (t == double.class || t == Double.class) args[i] = 1d;
            else if (t == long.class || t == Long.class) args[i] = 0L;
            else if (t == boolean.class || t == Boolean.class) args[i] = false;
            else if (Set.class.isAssignableFrom(t)) args[i] = Collections.emptySet();
            else if (List.class.isAssignableFrom(t) || Collection.class.isAssignableFrom(t)) args[i] = Collections.emptyList();
            else if (Map.class.isAssignableFrom(t)) args[i] = Collections.emptyMap();
            else if (t.getName().equals("org.joml.Vector4f")) args[i] = new org.joml.Vector4f(1f, 1f, 1f, 1f);
            else args[i] = null;
        }
        return args;
    }

    public static List<?> list(Object o) {
        if (o instanceof List<?> l) return l;
        if (o instanceof Iterable<?> it) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>();
            for (Object x : it) out.add(x);
            return out;
        }
        return Collections.emptyList();
    }

    public static String str(Object o) {
        if (o == null) return null;
        if (o instanceof net.minecraft.text.Text t) return t.getString();
        if (o instanceof Enum<?> e) return e.name();
        return String.valueOf(o);
    }

    public static int i(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    public static double d(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }

    public static boolean b(Object o) {
        return o instanceof Boolean bo && bo;
    }

    // ------------------------------------------------------------------ internals

    private static Method method(Class<?> c, String name, int argc) {
        String key = c.getName() + "#" + name + "/" + argc;
        return METHODS.computeIfAbsent(key, k -> {
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc && !Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    private static Method mangled(Class<?> c, String prefix) {
        String key = c.getName() + "#" + prefix + "$*";
        return METHODS.computeIfAbsent(key, k -> {
            for (Method m : c.getMethods()) {
                if (m.getName().startsWith(prefix + "$") && m.getParameterCount() == 0) return Optional.of(m);
            }
            return Optional.empty();
        }).orElse(null);
    }

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean acceptsAll(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) if (!accepts(types[i], args[i])) return false;
        return true;
    }

    private static boolean accepts(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        if (type.isInstance(value)) return true;
        if (type == int.class) return value instanceof Integer;
        if (type == boolean.class) return value instanceof Boolean;
        if (type == float.class) return value instanceof Float;
        if (type == double.class) return value instanceof Double;
        if (type == long.class) return value instanceof Long;
        return false;
    }
}
