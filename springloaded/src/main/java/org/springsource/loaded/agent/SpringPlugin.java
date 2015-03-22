/*
 * Copyright 2010-2012 VMware and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springsource.loaded.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.objectweb.asm.ClassReader;
import org.springsource.loaded.GlobalConfiguration;
import org.springsource.loaded.LoadtimeInstrumentationPlugin;
import org.springsource.loaded.ReloadEventProcessorPlugin;


/**
 * First stab at the Spring plugin for Spring-Loaded. Notes...<br>
 * <ul>
 * <li>On reload, removes the Class entry in
 * org/springframework/web/servlet/mvc/annotation/AnnotationMethodHandlerAdapter.methodResolverCache. This enables us to
 * add/changes request mappings in controllers.
 * <li>That was for Roo, if we create a simple spring template project and run it, this doesn't work. It seems we need
 * to redrive detectHandlers() on the DefaultAnnotationHandlerMapping type which will rediscover the URL mappings and
 * add them into the handler list. We don't clear old ones out (yet) but the old mappings appear not to work anyway.
 * </ul>
 * 
 * @author Andy Clement
 * @since 0.5.0
 */
public class SpringPlugin implements LoadtimeInstrumentationPlugin, ReloadEventProcessorPlugin {

	private static final String THIS_CLASS = "org/springsource/loaded/agent/SpringPlugin";

	private static Logger log = Logger.getLogger(SpringPlugin.class.getName());

	private static boolean debug = true;

	// TODO [gc] what about GC here - how do we know when they are finished with?
	public static List<Object> annotationMethodHandlerAdapterInstances = new ArrayList<Object>();

	public static List<Object> defaultAnnotationHandlerMappingInstances = new ArrayList<Object>();

	public static List<Object> requestMappingHandlerMappingInstances = new ArrayList<Object>();

	public static List<Object> localVariableTableParameterNameDiscovererInstances = null;

	public static boolean support305 = true;

	private Field classCacheField; // From CachedIntrospectionResults (Spring <= 4.0.x)

	private Field strongClassCacheField; // From CachedIntrospectionResults (Spring >= 4.1.0)

	private Field softClassCacheField; // From CachedIntrospectionResults (Spring >= 4.1.0)

	private Field declaredMethodsCacheField; // From ReflectionUtils

	private Field parameterNamesCacheField; // From LocalVariableTableParameterNameDiscoverer

	private boolean cachedIntrospectionResultsClassLoaded = false;

	private boolean reflectionUtilsClassLoaded = false;

	private Class<?> cachedIntrospectionResultsClass = null;

	private Class<?> reflectionUtilsClass = null;

	public boolean accept(String slashedTypeName, ClassLoader classLoader, ProtectionDomain protectionDomain,
			byte[] bytes) {
		// TODO take classloader into account?
		if (slashedTypeName == null) {
			return false;
		}
		if (slashedTypeName.equals("org/springframework/core/LocalVariableTableParameterNameDiscoverer")) {
			return true;
		}
		// Just interested in whether this type got loaded
		if (slashedTypeName.equals("org/springframework/beans/CachedIntrospectionResults")) {
			cachedIntrospectionResultsClassLoaded = true;
		}
		// Just interested in whether this type got loaded
		if (slashedTypeName.equals("org/springframework/util/ReflectionUtils")) {
			reflectionUtilsClassLoaded = true;
		}
		return slashedTypeName.equals("org/springframework/web/servlet/mvc/annotation/AnnotationMethodHandlerAdapter")
				||
				slashedTypeName.equals("org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerMapping")
				|| // 3.1
				(support305 && slashedTypeName
						.equals("org/springframework/web/servlet/mvc/annotation/DefaultAnnotationHandlerMapping"));
	}


	public byte[] modify(String slashedClassName, ClassLoader classLoader, byte[] bytes) {
		if (GlobalConfiguration.isRuntimeLogging && log.isLoggable(Level.INFO)) {
			log.info("loadtime modifying " + slashedClassName);
		}
		if (slashedClassName.equals("org/springframework/web/servlet/mvc/annotation/AnnotationMethodHandlerAdapter")) {
			return bytesWithInstanceCreationCaptured(bytes, THIS_CLASS,
					"recordAnnotationMethodHandlerAdapterInstance");
		}
		else if (slashedClassName.equals("org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerMapping")) {
			// springmvc spring 3.1 - doesnt work on 3.1 post M2 snapshots
			return bytesWithInstanceCreationCaptured(bytes, THIS_CLASS,
					"recordRequestMappingHandlerMappingInstance");
		}
		else if (slashedClassName.equals("org/springframework/core/LocalVariableTableParameterNameDiscoverer")) {
			return bytesWithInstanceCreationCaptured(bytes, THIS_CLASS,
					"recordLocalVariableTableParameterNameDiscoverer");
		}
		else { // "org/springframework/web/servlet/mvc/annotation/DefaultAnnotationHandlerMapping"
				// springmvc spring 3.0
			return bytesWithInstanceCreationCaptured(bytes, THIS_CLASS,
					"recordDefaultAnnotationHandlerMappingInstance");
		}
	}

	// called by the modified code
	public static void recordAnnotationMethodHandlerAdapterInstance(Object obj) {
		annotationMethodHandlerAdapterInstances.add(obj);
	}

	public static void recordRequestMappingHandlerMappingInstance(Object obj) {
		requestMappingHandlerMappingInstances.add(obj);
	}

	public static void recordLocalVariableTableParameterNameDiscoverer(Object obj) {
		if (localVariableTableParameterNameDiscovererInstances == null) {
			localVariableTableParameterNameDiscovererInstances = new ArrayList<Object>();
		}
		localVariableTableParameterNameDiscovererInstances.add(obj);
	}

	static {
		try {
			String debugString = System.getProperty("springloaded.plugins.spring.debug", "false");
			debug = Boolean.valueOf(debugString);
		}
		catch (Exception e) {
			// likely security exception
		}
	}

	// called by the modified code
	public static void recordDefaultAnnotationHandlerMappingInstance(Object obj) {
		if (debug) {
			System.out.println("Recording new instance of DefaultAnnotationHandlerMappingInstance");
		}
		defaultAnnotationHandlerMappingInstances.add(obj);
	}

	public void reloadEvent(String typename, Class<?> clazz, String versionsuffix) {
		removeClazzFromMethodResolverCache(clazz);
		removeClazzFromDeclaredMethodsCache(clazz);
		clearCachedIntrospectionResults(clazz);
		reinvokeDetectHandlers(); // Spring 3.0
		reinvokeInitHandlerMethods(); // Spring 3.1
		clearLocalVariableTableParameterNameDiscovererCache(clazz);
	}

	/**
	 * The Spring class LocalVariableTableParameterNameDiscoverer holds a cache of parameter names discovered for
	 * members within classes and needs clearing if the class changes.
	 * 
	 * @param clazz the class being reloaded, which may exist in a parameter name discoverer cache
	 */
	private void clearLocalVariableTableParameterNameDiscovererCache(Class<?> clazz) {
		if (localVariableTableParameterNameDiscovererInstances == null) {
			return;
		}
		if (debug) {
			System.out.println("ParameterNamesCache: Clearing parameter name discoverer caches");
		}
		if (parameterNamesCacheField == null) {
			try {
				parameterNamesCacheField = localVariableTableParameterNameDiscovererInstances
						.get(0).getClass().getDeclaredField("parameterNamesCache");
			}
			catch (NoSuchFieldException nsfe) {
				log.log(Level.SEVERE,
						"Unexpectedly cannot find parameterNamesCache field on LocalVariableTableParameterNameDiscoverer class");
			}
		}
		for (Object instance : localVariableTableParameterNameDiscovererInstances) {
			parameterNamesCacheField.setAccessible(true);
			try {
				Map<?, ?> parameterNamesCache = (Map<?, ?>) parameterNamesCacheField.get(instance);
				Object o = parameterNamesCache.remove(clazz);
				if (debug) {
					System.out.println("ParameterNamesCache: Removed " + clazz.getName() + " from cache?" + (o != null));
				}
			}
			catch (IllegalAccessException e) {
				log.log(Level.SEVERE,
						"Unexpected IllegalAccessException trying to access parameterNamesCache field on LocalVariableTableParameterNameDiscoverer class");
			}
		}
	}

	private void removeClazzFromMethodResolverCache(Class<?> clazz) {
		for (Object o : annotationMethodHandlerAdapterInstances) {
			try {
				Field f = o.getClass().getDeclaredField("methodResolverCache");
				f.setAccessible(true);
				Map<?, ?> map = (Map<?, ?>) f.get(o);
				Method removeMethod = Map.class.getDeclaredMethod("remove", Object.class);
				Object ret = removeMethod.invoke(map, clazz);
				if (GlobalConfiguration.debugplugins) {
					System.err.println("SpringPlugin: clearing methodResolverCache for " + clazz.getName());
				}
				if (GlobalConfiguration.isRuntimeLogging && log.isLoggable(Level.INFO)) {
					log.info("cleared a cache entry? " + (ret != null));
				}
			}
			catch (Exception e) {
				log.log(Level.SEVERE, "Unexpected problem accessing methodResolverCache on " + o, e);
			}
		}
	}

	private void removeClazzFromDeclaredMethodsCache(Class<?> clazz) {
		if (reflectionUtilsClassLoaded) {
			try {
				// TODO not a fan of classloading like this
				if (reflectionUtilsClass == null) {
					// TODO what about two apps using reloading and diff versions of spring?
					reflectionUtilsClass = clazz.getClassLoader().loadClass(
							"org.springframework.util.ReflectionUtils");
				}

				if (declaredMethodsCacheField == null) {
					try {
						declaredMethodsCacheField = reflectionUtilsClass.getDeclaredField("declaredMethodsCache");
					}
					catch (NoSuchFieldException e) {

					}

				}
				if (declaredMethodsCacheField != null) {
					declaredMethodsCacheField.setAccessible(true);
					Map m = (Map) declaredMethodsCacheField.get(null);
					Object o = m.remove(clazz);
					if (GlobalConfiguration.debugplugins) {
						System.err.println("SpringPlugin: clearing ReflectionUtils.declaredMethodsCache for "
								+ clazz.getName() + " removed=" + o);
					}
				}

			}
			catch (Exception e) {
				if (GlobalConfiguration.debugplugins) {
					e.printStackTrace();
				}
			}
		}
	}

	private void clearCachedIntrospectionResults(Class<?> clazz) {
		if (cachedIntrospectionResultsClassLoaded) {
			try {
				// TODO not a fan of classloading like this
				if (cachedIntrospectionResultsClass == null) {
					// TODO what about two apps using reloading and diff versions of spring?
					cachedIntrospectionResultsClass = clazz.getClassLoader().loadClass(
							"org.springframework.beans.CachedIntrospectionResults");
				}

				if (classCacheField == null && strongClassCacheField == null) {
					try {
						classCacheField = cachedIntrospectionResultsClass.getDeclaredField("classCache");
					}
					catch (NoSuchFieldException e) {
						strongClassCacheField = cachedIntrospectionResultsClass.getDeclaredField("strongClassCache");
						softClassCacheField = cachedIntrospectionResultsClass.getDeclaredField("softClassCache");
					}

				}
				if (classCacheField != null) {
					classCacheField.setAccessible(true);
					Map m = (Map) classCacheField.get(null);
					Object o = m.remove(clazz);
					if (GlobalConfiguration.debugplugins) {
						System.err.println("SpringPlugin: clearing CachedIntrospectionResults.classCache for "
								+ clazz.getName() + " removed=" + o);
					}
				}
				if (strongClassCacheField != null) {
					strongClassCacheField.setAccessible(true);
					Map m = (Map) strongClassCacheField.get(null);
					Object o = m.remove(clazz);
					if (GlobalConfiguration.debugplugins) {
						System.err.println("SpringPlugin: clearing CachedIntrospectionResults.strongClassCache for "
								+ clazz.getName() + " removed=" + o);
					}
				}
				if (softClassCacheField != null) {
					softClassCacheField.setAccessible(true);
					Map m = (Map) softClassCacheField.get(null);
					Object o = m.remove(clazz);
					if (GlobalConfiguration.debugplugins) {
						System.err.println("SpringPlugin: clearing CachedIntrospectionResults.softClassCache for "
								+ clazz.getName() + " removed=" + o);
					}
				}

			}
			catch (Exception e) {
				if (GlobalConfiguration.debugplugins) {
					e.printStackTrace();
				}
			}
		}
	}

	private void reinvokeDetectHandlers() {
		// want to call detectHandlers on the DefaultAnnotationHandlerMapping type
		// protected void detectHandlers() throws BeansException {  is defined on  AbstractDetectingUrlHandlerMapping
		for (Object o : defaultAnnotationHandlerMappingInstances) {
			if (debug) {
				System.out.println("Invoking detectHandlers on instance of DefaultAnnotationHandlerMappingInstance");
			}
			try {
				Class<?> clazz_AbstractDetectingUrlHandlerMapping = o.getClass().getSuperclass();
				Method method_detectHandlers = clazz_AbstractDetectingUrlHandlerMapping.getDeclaredMethod("detectHandlers");
				method_detectHandlers.setAccessible(true);
				method_detectHandlers.invoke(o);
			}
			catch (Exception e) {
				// if debugging then print it
				if (GlobalConfiguration.debugplugins) {
					e.printStackTrace();
				}
			}
		}
	}

	@SuppressWarnings("rawtypes")
	private void reinvokeInitHandlerMethods() {
		//		org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping (super AbstractHandlerMethodMapping) - call protected void initHandlerMethods() on it.

		for (Object o : requestMappingHandlerMappingInstances) {
			if (debug) {
				System.out.println("Invoking initHandlerMethods on instance of RequestMappingHandlerMapping");
			}
			try {
				Class<?> clazz_AbstractHandlerMethodMapping = o.getClass().getSuperclass().getSuperclass();
				Field[] declaredFields = clazz_AbstractHandlerMethodMapping.getDeclaredFields();

				// private final Map<T, HandlerMethod> handlerMethods = new LinkedHashMap<T, HandlerMethod>();
				Field field_handlerMethods = findField(declaredFields, "handlerMethods");
				if (field_handlerMethods != null) {
					field_handlerMethods.setAccessible(true);
					Map m = (Map) field_handlerMethods.get(o);
					m.clear();
				}

				// private final MultiValueMap<String, T> urlMap = new LinkedMultiValueMap<String, T>();
				Field field_urlMap = findField(declaredFields, "urlMap");
				if (field_urlMap != null) {
					field_urlMap.setAccessible(true);
					Map m = (Map) field_urlMap.get(o);
					m.clear();
				}

				// private final MultiValueMap<String, HandlerMethod> nameMap = new LinkedMultiValueMap<String, HandlerMethod>();
				Field field_nameMap = findField(declaredFields, "nameMap");
				if (field_nameMap != null) {
					field_nameMap.setAccessible(true);
					Map m = (Map) field_nameMap.get(o);
					m.clear();
				}

				Method method_initHandlerMethods = clazz_AbstractHandlerMethodMapping.getDeclaredMethod("initHandlerMethods");
				method_initHandlerMethods.setAccessible(true);
				method_initHandlerMethods.invoke(o);
			}
                        catch (Exception e) {
				if (GlobalConfiguration.debugplugins) {
					e.printStackTrace();
				}
			}
		}
	}

	private static Field findField(Field[] fields, String name) {
		for (Field field : fields) {
			if (field.getName().equals(name)) {
				return field;
			}
		}
		return null;
	}

	public boolean shouldRerunStaticInitializer(String typename, Class<?> clazz, String encodedTimestamp) {
		return false;
	}

	/**
	 * Modify the supplied bytes such that constructors are intercepted and will invoke the specified class/method so
	 * that the instances can be tracked.
	 * 
	 * @return modified bytes for the class
	 */
	private byte[] bytesWithInstanceCreationCaptured(byte[] bytes, String classToCall, String methodToCall) {
		ClassReader cr = new ClassReader(bytes);
		ClassVisitingConstructorAppender ca = new ClassVisitingConstructorAppender(classToCall, methodToCall);
		cr.accept(ca, 0);
		byte[] newbytes = ca.getBytes();
		return newbytes;
	}

}
