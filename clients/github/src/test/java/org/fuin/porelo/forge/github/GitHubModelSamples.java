package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
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
import org.fuin.porelo.forge.github.model.CommitAuthor;
import org.fuin.porelo.forge.github.model.Enterprise;
import org.fuin.porelo.forge.github.model.IntegrationOwner;
import org.fuin.porelo.forge.github.model.PrivateUser;
import org.fuin.porelo.forge.github.model.PublicUser;
import org.fuin.porelo.forge.github.model.SimpleUser;
import org.fuin.porelo.forge.github.model.UsersGetAuthenticated200Response;
import org.jspecify.annotations.Nullable;

/**
 * Every value the client binds with its mapper, made by reflection on the JVM with every component
 * set, and written as JSON: the samples the round-trip test reads back, on the JVM under the
 * tracing agent and in the native image, where nothing can be made by reflection.
 *
 * <p>A record's own sample sets every component; a record nested in it sets only the components it
 * requires, since its own sample sets the rest.
 */
final class GitHubModelSamples {
    private static final String MODEL = "org.fuin.porelo.forge.github.model";

    /** The hand-written answers the client binds by reflection, the records they hold included. */
    private static final List<Class<?>> GRAPHQL =
            List.of(
                    GitHubGraphQl.ThreadsResponse.class,
                    GitHubGraphQl.CommentsResponse.class,
                    GitHubGraphQl.ThreadResponse.class,
                    GitHubGraphQl.ResolveResponse.class,
                    GitHubGraphQl.UnresolveResponse.class);

    /** The unions the client's readers choose a variant of by content, with each variant. */
    private static final Map<Class<?>, List<Class<?>>> UNIONS =
            Map.of(
                    UsersGetAuthenticated200Response.class,
                    List.of(PrivateUser.class, PublicUser.class),
                    IntegrationOwner.class,
                    List.of(Enterprise.class, SimpleUser.class),
                    CommitAuthor.class,
                    List.of(SimpleUser.class));

    /**
     * The components typed by an interface with no variant in the model, so a caller cannot set
     * them from it and the client reads none: the milestones take a string or an integer, and no
     * main code binds a validation error.
     */
    static final Set<String> NEVER_SET =
            Set.of(
                    "IssuesCreateRequest.milestone",
                    "IssuesUpdateRequest.milestone",
                    "ValidationErrorErrorsInner.value");

    /** A value of each JSON kind, as an untyped value is read. */
    private static final Map<String, Object> UNTYPED = untyped();

    private static final OffsetDateTime TIME =
            OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);

    private final ObjectMapper mapper;

    private GitHubModelSamples(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Writes every sample to the file given, as an array of {@code type}, {@code expect} and {@code
     * json}: the class it is read as, the class read and the JSON.
     */
    static void write(ObjectMapper mapper, Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, new GitHubModelSamples(mapper).all().toPrettyString());
    }

    /** Every class of the generated model package, listed from the class path. */
    static Set<Class<?>> modelClasses() throws Exception {
        Path root =
                Path.of(
                        SimpleUser.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
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

    /** Whether one of the client's readers chooses a variant of this interface by content. */
    static boolean isUnion(Class<?> type) {
        return UNIONS.containsKey(type);
    }

    private ArrayNode all() throws Exception {
        var roots = new LinkedHashSet<Class<?>>(modelClasses());
        roots.addAll(GRAPHQL);
        var done = new LinkedHashSet<Class<?>>();
        var queue = new ArrayList<Class<?>>(roots);
        ArrayNode samples = mapper.createArrayNode();
        while (!queue.isEmpty()) {
            Class<?> type = queue.remove(0);
            if (!done.add(type)) continue;
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    for (Class<?> nested : records(component.getGenericType())) {
                        if (!done.contains(nested)) queue.add(nested);
                    }
                }
                add(samples, type, type, value(type, true, 0));
                writeOnly(samples, type);
            } else if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) add(samples, type, type, constant);
            } else if (UNIONS.containsKey(type)) {
                for (Class<?> variant : UNIONS.get(type)) {
                    add(samples, type, variant, value(variant, true, 0));
                }
            } else if (isBean(type)) {
                add(samples, type, type, bean(type, 0));
            }
            // An interface no reader chooses a variant of is never read by the client; a record
            // holding one gets a write-only sample per variant, or names it in NEVER_SET.
        }
        add(samples, CommitAuthor.class, Void.class, mapper.createObjectNode());
        for (Object value : UNTYPED.values()) add(samples, Object.class, Object.class, value);
        add(samples, Object.class, Object.class, UNTYPED);
        return samples;
    }

    private ObjectNode add(ArrayNode samples, Class<?> type, Class<?> expect, Object value) {
        ObjectNode sample = samples.addObject();
        sample.put("type", type.getName());
        sample.put("expect", expect.getName());
        sample.set("json", value instanceof JsonNode node ? node : mapper.valueToTree(value));
        return sample;
    }

    /**
     * One further sample of the record per variant of each interface it holds that no reader
     * chooses, the component set to that variant: the client only writes these, so the sample is
     * read back through the variant ({@code "read": false}). Any other interface component fails.
     */
    private void writeOnly(ArrayNode samples, Class<?> type) throws Exception {
        for (RecordComponent component : type.getRecordComponents()) {
            Class<?> face = interfaceOf(component.getGenericType());
            if (face == null || UNIONS.containsKey(face)) continue;
            List<Class<?>> variants = variants(face);
            if (variants.isEmpty()) {
                if (NEVER_SET.contains(type.getSimpleName() + "." + component.getName())) continue;
                throw new IllegalStateException(
                        "No sample for "
                                + type.getName()
                                + "."
                                + component.getName()
                                + ": its interface "
                                + face.getName()
                                + " has no variant and no reader");
            }
            for (Class<?> variant : variants) {
                Object set = wrap(component.getType(), value(variant, true, 1));
                ObjectNode sample =
                        add(samples, type, type, with(value(type, true, 0), component, set));
                sample.put("read", false);
                sample.put("interface", face.getName());
                sample.put("variant", variant.getName());
            }
        }
    }

    /** The entries of a never-set list that no longer name a component it is meant for. */
    static List<String> staleNeverSet(Set<String> listed) throws Exception {
        var stale = new ArrayList<String>();
        for (String entry : new TreeSet<>(listed)) {
            int dot = entry.indexOf('.');
            Class<?> type;
            try {
                type = Class.forName(MODEL + "." + entry.substring(0, dot));
            } catch (ClassNotFoundException ex) {
                stale.add(entry + " (no such record)");
                continue;
            }
            RecordComponent found = null;
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    if (component.getName().equals(entry.substring(dot + 1))) found = component;
                }
            }
            if (found == null) {
                stale.add(entry + " (no such component)");
                continue;
            }
            Class<?> face = interfaceOf(found.getGenericType());
            if (face == null) stale.add(entry + " (not typed by an interface)");
            else if (UNIONS.containsKey(face)) stale.add(entry + " (a reader chooses its variant)");
            else if (!variants(face).isEmpty()) stale.add(entry + " (its interface has a variant)");
        }
        return stale;
    }

    /** The model interface a component holds, alone or in a list, set, map or array. */
    private static @Nullable Class<?> interfaceOf(Type type) {
        if (type instanceof Class<?> plain && plain.isArray()) {
            return interfaceOf(plain.getComponentType());
        }
        if (type instanceof Class<?> plain) {
            return plain.isInterface() && plain.getPackageName().equals(MODEL) ? plain : null;
        }
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                Class<?> found = interfaceOf(argument);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The model's classes that implement an interface. */
    private static List<Class<?>> variants(Class<?> face) throws Exception {
        return modelClasses().stream()
                .filter(type -> !type.isInterface() && face.isAssignableFrom(type))
                .toList();
    }

    /** A value as a component of the given type holds it: alone, or as the one element. */
    private static Object wrap(Class<?> type, @Nullable Object value) {
        if (value == null) throw new IllegalStateException("No sample of a variant for " + type);
        if (List.class.isAssignableFrom(type)) return List.of(value);
        if (Set.class.isAssignableFrom(type)) return Set.of(value);
        if (type.isArray()) {
            Object array = Array.newInstance(type.getComponentType(), 1);
            Array.set(array, 0, value);
            return array;
        }
        return value;
    }

    /** A copy of a record with one component replaced. */
    private static Object with(@Nullable Object record, RecordComponent target, Object value)
            throws Exception {
        if (record == null) throw new IllegalStateException("No sample to set " + target);
        RecordComponent[] components = record.getClass().getRecordComponents();
        Object[] arguments = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            arguments[i] =
                    components[i].getName().equals(target.getName())
                            ? value
                            : components[i].getAccessor().invoke(record);
        }
        var constructor = record.getClass().getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private static List<Class<?>> records(Type type) {
        var found = new ArrayList<Class<?>>();
        if (type instanceof Class<?> plain && plain.isArray()) {
            found.addAll(records(plain.getComponentType()));
        } else if (type instanceof Class<?> plain
                && (plain.isRecord() || plain.isEnum() || isBean(plain))) {
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
            // A property GitHub always sends, if only as null, is required by the reader too.
            var property =
                    type.getDeclaredField(component.getName()).getAnnotation(JsonProperty.class);
            boolean sent = !nullable || (property != null && property.required());
            arguments[i] =
                    full || sent
                            ? component(
                                    type, component.getName(), component.getGenericType(), depth)
                            : null;
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

    private @Nullable Object component(Class<?> owner, String name, Type type, int depth)
            throws Exception {
        // The user reader takes an account's view as it is named, and refuses an unknown one.
        if (name.equals("userViewType")) {
            return owner == PrivateUser.class ? "private" : "public";
        }
        return of(type, depth);
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
        if (UNIONS.containsKey(raw)) return value(UNIONS.get(raw).get(0), false, depth + 1);
        if (isBean(raw)) return bean(raw, depth + 1);
        return null;
    }

    /** A generated class bound by its setters and, for an open object, its any-setter. */
    static boolean isBean(Class<?> type) {
        return type.getPackageName().equals(MODEL)
                && !type.isInterface()
                && !type.isRecord()
                && !type.isEnum()
                && !Modifier.isAbstract(type.getModifiers());
    }

    private Object bean(Class<?> type, int depth) throws Exception {
        Object bean = type.getDeclaredConstructor().newInstance();
        for (var method : type.getMethods()) {
            if (method.getDeclaringClass() != type || method.getParameterCount() == 0) continue;
            var parameters = method.getGenericParameterTypes();
            if (method.isAnnotationPresent(JsonAnySetter.class) && parameters.length == 2) {
                method.invoke(bean, "additional", of(parameters[1], depth));
            } else if (method.getName().startsWith("set") && parameters.length == 1) {
                method.invoke(bean, of(parameters[0], depth));
            }
        }
        return bean;
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
