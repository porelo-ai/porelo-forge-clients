package org.fuin.porelo.forge.forgejo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.fuin.porelo.forge.forgejo.model.User;
import org.jspecify.annotations.Nullable;

/**
 * Every value the client binds with its mapper, made by reflection on the JVM with every component
 * set, and written as JSON: the samples the round-trip test reads back, on the JVM under the
 * tracing agent and in the native image, where nothing can be made by reflection.
 *
 * <p>A record's own sample sets every component; a record nested in it sets only the components it
 * requires, since its own sample sets the rest.
 */
final class ForgejoModelSamples {
    private static final String MODEL = "org.fuin.porelo.forge.forgejo.model";

    /** The hand-written webhook payloads the client binds by reflection. */
    private static final List<Class<?>> WEBHOOK =
            List.of(
                    ForgejoWebhook.PullRequestEvent.class,
                    ForgejoWebhook.ReviewEvent.class,
                    ForgejoWebhook.ActionsRunEvent.class);

    /** A value of each JSON kind, as an untyped value is read. */
    private static final Map<String, Object> UNTYPED = untyped();

    private static final OffsetDateTime TIME =
            OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);

    private final ObjectMapper mapper;

    private ForgejoModelSamples(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Writes every sample to the file given, as an array of {@code type} and {@code json}: the
     * class it is read as and the JSON.
     */
    static void write(ObjectMapper mapper, Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, new ForgejoModelSamples(mapper).all().toPrettyString());
    }

    /** Every class of the generated model package, listed from the class path. */
    static Set<Class<?>> modelClasses() throws Exception {
        Path root = Path.of(User.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path dir = root.resolve(MODEL.replace('.', '/'));
        var classes = new TreeSet<Class<?>>((a, b) -> a.getName().compareTo(b.getName()));
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".class")
                        && !name.contains("$")
                        && !name.startsWith("package-info")) {
                    classes.add(Class.forName(MODEL + "." + name.substring(0, name.length() - 6)));
                }
            }
        }
        return classes;
    }

    private ArrayNode all() throws Exception {
        var roots = new LinkedHashSet<Class<?>>(modelClasses());
        roots.addAll(WEBHOOK);
        var done = new LinkedHashSet<Class<?>>();
        var queue = new ArrayList<Class<?>>(roots);
        ArrayNode samples = mapper.createArrayNode();
        while (!queue.isEmpty()) {
            Class<?> type = queue.remove(0);
            if (!done.add(type)) continue;
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    requireNoInterface(type, component);
                    for (Class<?> nested : records(component.getGenericType())) {
                        if (!done.contains(nested)) queue.add(nested);
                    }
                }
                add(samples, type, value(type, true, 0));
            } else if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) add(samples, type, constant);
            } else {
                throw new IllegalStateException(
                        "No sample for " + type.getName() + ": neither a record nor an enum");
            }
        }
        for (Object value : UNTYPED.values()) add(samples, Object.class, value);
        add(samples, Object.class, UNTYPED);
        return samples;
    }

    /** Forgejo's model has no union: an interface component would be left out unseen. */
    private static void requireNoInterface(Class<?> type, RecordComponent component) {
        if (holdsInterface(component.getGenericType())) {
            throw new IllegalStateException(
                    "No sample for "
                            + type.getName()
                            + "."
                            + component.getName()
                            + ": an interface");
        }
    }

    private static boolean holdsInterface(Type type) {
        if (type instanceof Class<?> plain) {
            return plain.isArray() ? holdsInterface(plain.getComponentType()) : plain.isInterface();
        }
        if (type instanceof ParameterizedType parameterized) {
            if (((Class<?>) parameterized.getRawType()).isInterface()
                    && !List.class.isAssignableFrom((Class<?>) parameterized.getRawType())
                    && !Set.class.isAssignableFrom((Class<?>) parameterized.getRawType())
                    && !Map.class.isAssignableFrom((Class<?>) parameterized.getRawType())) {
                return true;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (holdsInterface(argument)) return true;
            }
        }
        return false;
    }

    private void add(ArrayNode samples, Class<?> type, Object value) throws Exception {
        ObjectNode sample = samples.addObject();
        sample.put("type", type.getName());
        // Through text, as the test writes it back: a time written as epoch seconds reads back as
        // a fraction, not as the whole number a tree made directly holds.
        sample.set("json", mapper.readTree(mapper.writeValueAsString(value)));
    }

    private static List<Class<?>> records(Type type) {
        var found = new ArrayList<Class<?>>();
        if (type instanceof Class<?> plain && plain.isArray()) {
            found.addAll(records(plain.getComponentType()));
        } else if (type instanceof Class<?> plain && (plain.isRecord() || plain.isEnum())) {
            found.add(plain);
        } else if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                found.addAll(records(argument));
            }
        }
        return found;
    }

    private @Nullable Object value(Class<?> type, boolean full, int depth) throws Exception {
        if (depth > 12) throw new IllegalStateException("Required components loop at " + type);
        RecordComponent[] components = type.getRecordComponents();
        Object[] arguments = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            types[i] = component.getType();
            boolean nullable = component.getAnnotatedType().isAnnotationPresent(Nullable.class);
            // A property Forgejo always sends, if only as null, is required by the reader too.
            var property =
                    type.getDeclaredField(component.getName()).getAnnotation(JsonProperty.class);
            boolean sent = !nullable || (property != null && property.required());
            arguments[i] = full || sent ? of(component.getGenericType(), depth) : null;
            if (arguments[i] == null && !nullable && !component.getType().isPrimitive()) {
                throw new IllegalStateException(
                        "No sample for the required " + type.getName() + "." + component.getName());
            }
            if (arguments[i] == null && component.getType().isPrimitive()) {
                arguments[i] = primitive(component.getType());
            }
        }
        var constructor = type.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private @Nullable Object of(Type type, int depth) throws Exception {
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw)) {
                Object element = of(arguments[0], depth + 1);
                if (element == null) return null;
                return Set.class.isAssignableFrom(raw) ? Set.of(element) : List.of(element);
            }
            if (Map.class.isAssignableFrom(raw)) {
                Object element = of(arguments[1], depth + 1);
                return element == null ? null : Map.of("key", element);
            }
            throw new IllegalStateException("No sample for " + type);
        }
        Class<?> raw = (Class<?>) type;
        if (raw == String.class) return "text";
        if (raw == Long.class || raw == long.class) return 42L;
        if (raw == Integer.class || raw == int.class) return 7;
        if (raw == Boolean.class || raw == boolean.class) return true;
        if (raw == Double.class || raw == double.class) return 1.5d;
        if (raw == Float.class || raw == float.class) return 2.5f;
        if (raw == BigDecimal.class) return new BigDecimal("3.25");
        if (raw == URI.class) return URI.create("https://example.com/a");
        if (raw == OffsetDateTime.class) return TIME;
        if (raw == LocalDate.class) return TIME.toLocalDate();
        if (raw == UUID.class) return new UUID(1, 2);
        if (raw == Object.class) return UNTYPED;
        if (raw.isArray()) {
            Object element = of(raw.getComponentType(), depth + 1);
            if (element == null) return null;
            Object array = Array.newInstance(raw.getComponentType(), 1);
            Array.set(array, 0, element);
            return array;
        }
        if (raw.isEnum()) return raw.getEnumConstants()[0];
        if (raw.isRecord()) return value(raw, false, depth + 1);
        throw new IllegalStateException("No sample for " + raw.getName());
    }

    private static Object primitive(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        return 0;
    }

    private static Map<String, Object> untyped() {
        var values = new LinkedHashMap<String, Object>();
        values.put("text", "text");
        values.put("whole", 1);
        values.put("large", 12345678901L);
        values.put("fraction", 1.5d);
        values.put("flag", true);
        values.put("list", List.of("a", 2));
        values.put("object", Map.of("key", "value"));
        return values;
    }
}
