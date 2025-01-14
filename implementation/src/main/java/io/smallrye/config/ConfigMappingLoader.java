package io.smallrye.config;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperties;

import io.smallrye.common.classloader.ClassDefiner;
import io.smallrye.common.constraint.Assert;
import io.smallrye.config._private.ConfigMessages;

public final class ConfigMappingLoader {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final ConcurrentHashMap<String, Object> classLoaderLocks = new ConcurrentHashMap<>();

    private static final ClassValue<ConfigMappingObjectHolder> CACHE = new ClassValue<ConfigMappingObjectHolder>() {
        @Override
        protected ConfigMappingObjectHolder computeValue(final Class<?> type) {
            return new ConfigMappingObjectHolder(getImplementationClass(type));
        }
    };

    public static List<ConfigMappingMetadata> getConfigMappingsMetadata(final Class<?> type) {
        List<ConfigMappingMetadata> mappings = new ArrayList<>();
        ConfigMappingInterface configurationInterface = ConfigMappingInterface.getConfigurationInterface(type);
        if (configurationInterface != null) {
            mappings.add(configurationInterface);
            mappings.addAll(configurationInterface.getNested());
            for (ConfigMappingInterface superType : configurationInterface.getSuperTypes()) {
                mappings.add(superType);
                mappings.addAll(superType.getNested());
            }
        }
        ConfigMappingClass configMappingClass = ConfigMappingClass.getConfigurationClass(type);
        if (configMappingClass != null) {
            mappings.add(configMappingClass);
            mappings.addAll(getConfigMappingsMetadata(getConfigMapping(type).getInterfaceType()));
        }
        return List.copyOf(mappings);
    }

    public static ConfigMappingInterface getConfigMapping(final Class<?> type) {
        return ConfigMappingInterface.getConfigurationInterface(getConfigMappingClass(type));
    }

    static Class<?> getConfigMappingClass(final Class<?> type) {
        ConfigMappingClass configMappingClass = ConfigMappingClass.getConfigurationClass(type);
        if (configMappingClass == null) {
            return type;
        } else {
            return loadClass(type, configMappingClass);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> Map<String, Map<String, Set<String>>> configMappingNames(final Class<T> interfaceType) {
        try {
            Method getNames = CACHE.get(interfaceType).getImplementationClass().getDeclaredMethod("getNames");
            return (Map<String, Map<String, Set<String>>>) getNames.invoke(null);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(e.getMessage());
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            try {
                throw e.getCause();
            } catch (RuntimeException | Error e2) {
                throw e2;
            } catch (Throwable t) {
                throw new UndeclaredThrowableException(t);
            }
        }
    }

    static <T> Map<String, String> configMappingDefaults(final Class<T> interfaceType) {
        try {
            Method getDefaults = CACHE.get(interfaceType).getImplementationClass().getDeclaredMethod("getDefaults");
            return (Map<String, String>) getDefaults.invoke(null);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(e.getMessage());
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            try {
                throw e.getCause();
            } catch (RuntimeException | Error e2) {
                throw e2;
            } catch (Throwable t) {
                throw new UndeclaredThrowableException(t);
            }
        }
    }

    static <T> T configMappingObject(final Class<T> interfaceType, final ConfigMappingContext configMappingContext) {
        ConfigMappingObject instance;
        try {
            Constructor<? extends ConfigMappingObject> constructor = CACHE.get(interfaceType).getImplementationClass()
                    .getDeclaredConstructor(ConfigMappingContext.class);
            instance = constructor.newInstance(configMappingContext);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(e.getMessage());
        } catch (InstantiationException e) {
            throw new InstantiationError(e.getMessage());
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            try {
                throw e.getCause();
            } catch (RuntimeException | Error e2) {
                throw e2;
            } catch (Throwable t) {
                throw new UndeclaredThrowableException(t);
            }
        }
        return interfaceType.cast(instance);
    }

    @SuppressWarnings("unchecked")
    public static <T> Class<? extends ConfigMappingObject> getImplementationClass(final Class<T> type) {
        try {
            Class<?> implementationClass = type.getClassLoader()
                    .loadClass(ConfigMappingInterface.getImplementationClassName(type));
            if (type.isAssignableFrom(implementationClass)) {
                return (Class<? extends ConfigMappingObject>) implementationClass;
            }

            ConfigMappingMetadata mappingMetadata = ConfigMappingInterface.getConfigurationInterface(type);
            if (mappingMetadata == null) {
                throw ConfigMessages.msg.classIsNotAMapping(type);
            }
            return (Class<? extends ConfigMappingObject>) loadClass(type, mappingMetadata);
        } catch (ClassNotFoundException e) {
            ConfigMappingMetadata mappingMetadata = ConfigMappingInterface.getConfigurationInterface(type);
            if (mappingMetadata == null) {
                throw ConfigMessages.msg.classIsNotAMapping(type);
            }
            return (Class<? extends ConfigMappingObject>) loadClass(type, mappingMetadata);
        }
    }

    static Class<?> loadClass(final Class<?> parent, final ConfigMappingMetadata configMappingMetadata) {
        // acquire a lock on the class name to prevent race conditions in multithreaded use cases
        synchronized (getClassLoaderLock(configMappingMetadata.getClassName())) {
            // Check if the interface implementation was already loaded. If not we will load it.
            try {
                Class<?> klass = parent.getClassLoader().loadClass(configMappingMetadata.getClassName());
                // Check if this is the right classloader class. If not we will load it.
                if (parent.isAssignableFrom(klass)) {
                    return klass;
                }
                // ConfigProperties should not have issues with classloader and interfaces.
                if (configMappingMetadata instanceof ConfigMappingClass) {
                    return klass;
                }
                return defineClass(parent, configMappingMetadata.getClassName(), configMappingMetadata.getClassBytes());
            } catch (ClassNotFoundException e) {
                return defineClass(parent, configMappingMetadata.getClassName(), configMappingMetadata.getClassBytes());
            }
        }
    }

    /**
     * Do not remove this method or inline it. It is keep separate on purpose, so it is easier to substitute it with
     * the GraalVM API for native image compilation.
     * <p>
     * We cannot keep dynamic references to LOOKUP, so this method may be replaced. This is not a problem, since for
     * native image we can generate the mapping class bytes in the binary so we don't need to dynamically load them.
     */
    private static Class<?> defineClass(final Class<?> parent, final String className, final byte[] classBytes) {
        return ClassDefiner.defineClass(LOOKUP, parent, className, classBytes);
    }

    private static Object getClassLoaderLock(final String className) {
        return classLoaderLocks.computeIfAbsent(className, c -> new Object());
    }

    private static final class ConfigMappingObjectHolder {
        private final Class<? extends ConfigMappingObject> implementationClass;

        ConfigMappingObjectHolder(final Class<? extends ConfigMappingObject> implementationClass) {
            this.implementationClass = implementationClass;
        }

        public Class<? extends ConfigMappingObject> getImplementationClass() {
            return implementationClass;
        }
    }

    /**
     * Implementation of {@link ConfigMappingMetadata} for MicroProfile {@link ConfigProperties}.
     */
    static final class ConfigMappingClass implements ConfigMappingMetadata {
        private static final ClassValue<ConfigMappingClass> cv = new ClassValue<>() {
            @Override
            protected ConfigMappingClass computeValue(final Class<?> classType) {
                return createConfigurationClass(classType);
            }
        };

        static ConfigMappingClass getConfigurationClass(Class<?> classType) {
            Assert.checkNotNullParam("classType", classType);
            return cv.get(classType);
        }

        private static ConfigMappingClass createConfigurationClass(final Class<?> classType) {
            if (classType.isInterface() && classType.getTypeParameters().length == 0 ||
                    Modifier.isAbstract(classType.getModifiers()) ||
                    classType.isEnum()) {
                return null;
            }

            return new ConfigMappingClass(classType);
        }

        private static String generateInterfaceName(final Class<?> classType) {
            if (classType.isInterface() && classType.getTypeParameters().length == 0 ||
                    Modifier.isAbstract(classType.getModifiers()) ||
                    classType.isEnum()) {
                throw new IllegalArgumentException();
            }

            return classType.getPackage().getName() +
                    "." +
                    classType.getSimpleName() +
                    classType.getName().hashCode() +
                    "I";
        }

        private final Class<?> classType;
        private final String interfaceName;

        public ConfigMappingClass(final Class<?> classType) {
            this.classType = classType;
            this.interfaceName = generateInterfaceName(classType);
        }

        @Override
        public Class<?> getInterfaceType() {
            return classType;
        }

        @Override
        public String getClassName() {
            return interfaceName;
        }

        @Override
        public byte[] getClassBytes() {
            return ConfigMappingGenerator.generate(classType, interfaceName);
        }
    }
}
