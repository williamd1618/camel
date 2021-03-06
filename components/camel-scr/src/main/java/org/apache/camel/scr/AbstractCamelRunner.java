/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.scr;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.component.properties.PropertiesComponent;
import org.apache.camel.core.osgi.OsgiCamelContextPublisher;
import org.apache.camel.core.osgi.OsgiDefaultCamelContext;
import org.apache.camel.core.osgi.utils.BundleDelegatingClassLoader;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.ExplicitCamelContextNameStrategy;
import org.apache.camel.impl.SimpleRegistry;
import org.apache.camel.util.ReflectionHelper;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractCamelRunner implements Runnable {

    public static final int START_DELAY = 5000;
    public static final String PROPERTY_PREFIX = "camel.scr.properties.prefix";
    
    protected Logger log = LoggerFactory.getLogger(getClass());
    protected CamelContext context;
    protected SimpleRegistry registry = new SimpleRegistry();
    protected boolean active;

    private ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture starter;
    private volatile boolean activated;
    private volatile boolean started;

    public synchronized void activate(final BundleContext bundleContext, final Map<String, String> props) throws Exception {
        if (activated) {
            return;
        }
        log.debug("activated!");

        activated = true;

        prepare(bundleContext, props);

        runWithDelay(this);
    }

    public synchronized void prepare(final BundleContext bundleContext, final Map<String, String> props) throws Exception {
        createCamelContext(bundleContext, props);

        // Configure fields from properties
        configure(context, this, log, true);

        setupCamelContext(bundleContext);
    }

    protected void createCamelContext(final BundleContext bundleContext, final Map<String, String> props) {
        if (bundleContext != null) {
            context = new OsgiDefaultCamelContext(bundleContext, registry);
            // Setup the application context classloader with the bundle classloader
            context.setApplicationContextClassLoader(new BundleDelegatingClassLoader(bundleContext.getBundle()));
            // and make sure the TCCL is our classloader
            Thread.currentThread().setContextClassLoader(context.getApplicationContextClassLoader());
        } else {
            context = new DefaultCamelContext(registry);
        }
        setupPropertiesComponent(context, props, log);

        String name = props.remove("camelContextId");
        if (name != null) {
            context.setNameStrategy(new ExplicitCamelContextNameStrategy(name));
        }

        // ensure we publish this CamelContext to the OSGi service registry
        context.getManagementStrategy().addEventNotifier(new OsgiCamelContextPublisher(bundleContext));
    }
    
    protected void setupCamelContext(final BundleContext bundleContext) throws Exception {
        // Set up CamelContext
        context.setUseMDCLogging(true);
        context.setUseBreadcrumb(true);

        // Add routes
        for (RoutesBuilder route : getRouteBuilders()) {
            context.addRoutes(configure(context, route, log));
        }
    }

    public static void setupPropertiesComponent(final CamelContext context, final Map<String, String> props, Logger log) {
        // Set up PropertiesComponent
        PropertiesComponent pc = new PropertiesComponent();
        if (context.getComponentNames().contains("properties")) {
            pc = context.getComponent("properties", PropertiesComponent.class);
        } else {
            context.addComponent("properties", pc);
        }

        // Set property prefix
        if (null != System.getProperty(PROPERTY_PREFIX)) {
            pc.setPropertyPrefix(System.getProperty(PROPERTY_PREFIX) + ".");
        }

        if (null != props) {
            Properties initialProps = new Properties();
            initialProps.putAll(props);
            log.debug(String.format("Added %d initial properties", props.size()));
            try {
                pc.setInitialProperties(initialProps);
            } catch (NoSuchMethodError e) {
                // For Camel versions without setInitialProperties
                pc.setOverrideProperties(initialProps);
                pc.setLocation("default.properties");
            }
        }
    }

    protected abstract List<RoutesBuilder> getRouteBuilders();

    // Run after a delay unless the method is called again
    private void runWithDelay(final Runnable runnable) {
        if (activated && !started) {
            cancelDelayedRun();
            // Run after a delay
            starter = executor.schedule(runnable, START_DELAY, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelDelayedRun() {
        if (null != starter) {
            // Cancel but don't interrupt
            starter.cancel(false);
        }
    }

    public synchronized void run() {
        startCamelContext();
    }

    public synchronized void deactivate() {
        if (!activated) {
            return;
        }
        log.debug("deactivated!");

        activated = false;

        cancelDelayedRun();

        stop();
    }

    public synchronized void stop() {
        stopCamelContext();
    }

    private void startCamelContext() {
        if (started) {
            return;
        }
        try {
            if (!active) {
                context.setAutoStartup(false);
            }
            context.start();
            started = true;
        } catch (Exception e) {
            log.warn("Failed to start Camel context. Will try again when more Camel components have been registered.", e);
        }
    }

    private void stopCamelContext() {
        if (!started) {
            return;
        }
        try {
            context.stop();
        } catch (Exception e) {
            log.error("Failed to stop Camel context.", e);
        } finally {
            // Even if stopping failed we consider Camel context stopped
            started = false;
        }
    }
    
    public CamelContext getContext() {
        return context;
    }

    protected void gotCamelComponent(final ServiceReference serviceReference) {
        log.trace("Got a new Camel Component.");
        runWithDelay(this);
    }

    protected void lostCamelComponent(final ServiceReference serviceReference) {
        log.trace("Lost a Camel Component.");
    }

    public static <T> T configure(final CamelContext context, final T target, final Logger log) {
        return configure(context, target, log, false);
    }

    public static <T> T configure(final CamelContext context, final T target, final Logger log, final boolean deep) {
        Class<?> clazz = target.getClass();
        log.debug("Configuring {}", clazz.getName());
        Collection<Field> fields = new ArrayList<Field>();
        fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
        if (deep) {
            fields.addAll(Arrays.asList(clazz.getFields()));
            fields.addAll(Arrays.asList(clazz.getSuperclass().getDeclaredFields()));
        }
        for (Field field : fields) {
            String propertyValue;
            try {
                propertyValue = context.resolvePropertyPlaceholders("{{" + field.getName() + "}}");
            } catch (Exception e) {
                log.debug("Skipped field {}", field.getName());
                continue;
            }
            try {
                if (!propertyValue.isEmpty()) {
                    // Try to convert the value and set the field
                    Object convertedValue = convertValue(propertyValue, field.getGenericType());
                    ReflectionHelper.setField(field, target, convertedValue);
                    log.debug("Configured field {} with value {}", field.getName(), propertyValue);
                }
            } catch (Exception e) {
                log.error("Error setting field " + field.getName() + " due: " + e.getMessage() + ". This exception is ignored.", e);
            }
        }
        return target;
    }

    public static Object convertValue(final String value, final Type type) throws Exception {
        Class<?> clazz = null;
        if (type instanceof ParameterizedType) {
            clazz = (Class<?>) ((ParameterizedType) type).getRawType();
        } else if (type instanceof Class) {
            clazz = (Class<?>) type;
        }
        if (null != value) {
            if (clazz.isInstance(value)) {
                return value;
            } else if (clazz == String.class) {
                return value;
            } else if (clazz == Boolean.class || clazz == boolean.class) {
                return Boolean.parseBoolean(value);
            } else if (clazz == Integer.class || clazz == int.class) {
                return Integer.parseInt(value);
            } else if (clazz == Long.class || clazz == long.class) {
                return Long.parseLong(value);
            } else if (clazz == Double.class || clazz == double.class) {
                return Double.parseDouble(value);
            } else if (clazz == File.class) {
                return new File(value);
            } else if (clazz == URI.class) {
                return new URI(value);
            } else if (clazz == URL.class) {
                return new URL(value);
            } else {
                throw new IllegalArgumentException("Unsupported type: " + (clazz != null ? clazz.getName() : null));
            }
        } else {
            return null;
        }
    }
}
