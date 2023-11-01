//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.annotations;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AnnotationParser
 * <p>
 * Use asm to scan classes for annotations. A SAX-style parsing is done.
 * Handlers are registered which will be called back when various types of
 * entity are encountered, eg a class, a method, a field.
 * <p>
 * Handlers are not called back in any particular order and are assumed
 * to be order-independent.
 * <p>
 * As a registered Handler will be called back for each annotation discovered
 * on a class, a method, a field, the Handler should test to see if the annotation
 * is one that it is interested in.
 * <p>
 * For the servlet spec, we are only interested in annotations on classes, methods and fields,
 * so the callbacks for handling finding a class, a method a field are themselves
 * not fully implemented.
 */
public class AnnotationParser
{
    private static final Logger LOG = LoggerFactory.getLogger(AnnotationParser.class);
    private static final int ASM_VERSION = asmVersion();

    /**
     * Map of classnames scanned and the first location from which scan occurred
     */
    protected Map<String, URI> _parsedClassNames = new ConcurrentHashMap<>();
    private final int _asmVersion;

    /**
     * Determine the runtime version of asm.
     *
     * @return the {@link org.objectweb.asm.Opcodes} ASM value matching the runtime version of asm.
     * TODO: can this be a jetty-util utility method to allow reuse across ee#?
     * TODO: we should probably keep ASM centralized, as it's not EE specific, but Java Runtime specific behavior to keep up to date
     */
    private static int asmVersion()
    {
        // Find the highest available ASM version on the runtime/classpath, because
        // if we run with a lower than available ASM version, against a class with
        // new language features we'll get an UnsupportedOperationException, even if
        // the ASM version supports the new language features.
        // Also, if we run with a higher than available ASM version, we'll get
        // an IllegalArgumentException from org.objectweb.asm.ClassVisitor.
        // So must find exactly the maximum ASM api version available.

        Optional<Integer> asmVersion = Arrays.stream(Opcodes.class.getFields()).sequential()
            .filter((f) -> f.getName().matches("ASM[0-9]+"))
            .map((f) -> f.getName().substring(3))
            .map(Integer::parseInt)
            .max(Integer::compareTo);

        if (asmVersion.isEmpty())
            throw new IllegalStateException("Invalid " + Opcodes.class.getName());

        int asmFieldId = asmVersion.get();
        try
        {
            String fieldName = "ASM" + asmFieldId;
            if (LOG.isDebugEnabled())
                LOG.debug("Using ASM API from {}.{}", Opcodes.class.getName(), fieldName);
            return (int)Opcodes.class.getField(fieldName).get(null);
        }
        catch (Throwable e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Convert internal name to simple name
     *
     * @param name the internal name
     * @return the simple name
     */
    public static String normalize(String name)
    {
        if (name == null)
            return null;

        if (name.startsWith("L") && name.endsWith(";"))
            name = name.substring(1, name.length() - 1);

        if (name.endsWith(".class"))
            name = name.substring(0, name.length() - ".class".length());

        return StringUtil.replace(name, '/', '.');
    }

    /**
     * Convert internal names to simple names.
     *
     * @param list the list of internal names
     * @return the list of simple names
     */
    public static String[] normalize(String[] list)
    {
        if (list == null)
            return null;
        String[] normalList = new String[list.length];
        int i = 0;
        for (String s : list)
        {
            normalList[i++] = normalize(s);
        }
        return normalList;
    }

    /**
     * Immutable information gathered by parsing class header.
     */
    public static class ClassInfo
    {
        final Resource _containingResource;
        final String _className;
        final int _version;
        final int _access;
        final String _signature;
        final String _superName;
        final String[] _interfaces;

        public ClassInfo(Resource resource, String className, int version, int access, String signature, String superName, String[] interfaces)
        {
            super();
            _containingResource = resource;
            _className = className;
            _version = version;
            _access = access;
            _signature = signature;
            _superName = superName;
            _interfaces = interfaces;
        }

        public String getClassName()
        {
            return _className;
        }

        public int getVersion()
        {
            return _version;
        }

        public int getAccess()
        {
            return _access;
        }

        public String getSignature()
        {
            return _signature;
        }

        public String getSuperName()
        {
            return _superName;
        }

        public String[] getInterfaces()
        {
            return _interfaces;
        }

        public Resource getContainingResource()
        {
            return _containingResource;
        }
    }

    /**
     * Immutable information gathered by parsing a method on a class.
     */
    public static class MethodInfo
    {
        final ClassInfo _classInfo;
        final String _methodName;
        final int _access;
        final String _desc;
        final String _signature;
        final String[] _exceptions;

        public MethodInfo(ClassInfo classInfo, String methodName, int access, String desc, String signature, String[] exceptions)
        {
            super();
            _classInfo = classInfo;
            _methodName = methodName;
            _access = access;
            _desc = desc;
            _signature = signature;
            _exceptions = exceptions;
        }

        public ClassInfo getClassInfo()
        {
            return _classInfo;
        }

        public String getMethodName()
        {
            return _methodName;
        }

        public int getAccess()
        {
            return _access;
        }

        public String getDesc()
        {
            return _desc;
        }

        public String getSignature()
        {
            return _signature;
        }

        public String[] getExceptions()
        {
            return _exceptions;
        }
    }

    /**
     * Immutable information gathered by parsing a field on a class.
     */
    public static class FieldInfo
    {
        final ClassInfo _classInfo;
        final String _fieldName;
        final int _access;
        final String _fieldType;
        final String _signature;
        final Object _value;

        public FieldInfo(ClassInfo classInfo, String fieldName, int access, String fieldType, String signature, Object value)
        {
            super();
            _classInfo = classInfo;
            _fieldName = fieldName;
            _access = access;
            _fieldType = fieldType;
            _signature = signature;
            _value = value;
        }

        public ClassInfo getClassInfo()
        {
            return _classInfo;
        }

        public String getFieldName()
        {
            return _fieldName;
        }

        public int getAccess()
        {
            return _access;
        }

        public String getFieldType()
        {
            return _fieldType;
        }

        public String getSignature()
        {
            return _signature;
        }

        public Object getValue()
        {
            return _value;
        }
    }

    /**
     * Signature for all handlers that respond to parsing class files.
     */
    public interface Handler
    {
        void handle(ClassInfo classInfo);

        void handle(MethodInfo methodInfo);

        void handle(FieldInfo fieldInfo);

        void handle(ClassInfo info, String annotationName);

        void handle(MethodInfo info, String annotationName);

        void handle(FieldInfo info, String annotationName);
    }

    /**
     * Convenience base class to provide no-ops for all Handler methods.
     */
    public abstract static class AbstractHandler implements Handler
    {
        @Override
        public void handle(ClassInfo classInfo)
        {
        }

        @Override
        public void handle(MethodInfo methodInfo)
        {
        }

        @Override
        public void handle(FieldInfo fieldInfo)
        {
        }

        @Override
        public void handle(ClassInfo info, String annotationName)
        {
        }

        @Override
        public void handle(MethodInfo info, String annotationName)
        {
        }

        @Override
        public void handle(FieldInfo info, String annotationName)
        {
        }
    }

    /**
     * ASM Visitor for parsing a method. We are only interested in the annotations on methods.
     */
    public static class MyMethodVisitor extends MethodVisitor
    {
        final MethodInfo _mi;
        final Set<? extends Handler> _handlers;

        public MyMethodVisitor(final Set<? extends Handler> handlers,
                               final ClassInfo classInfo,
                               final int access,
                               final String name,
                               final String methodDesc,
                               final String signature,
                               final String[] exceptions,
                               final int asmVersion)
        {
            super(asmVersion);
            _handlers = handlers;
            _mi = new MethodInfo(classInfo, name, access, methodDesc, signature, exceptions);
        }

        /**
         * We are only interested in finding the annotations on methods.
         */
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible)
        {
            String annotationName = normalize(desc);
            for (Handler h : _handlers)
            {
                h.handle(_mi, annotationName);
            }
            return null;
        }
    }

    /**
     * An ASM visitor for parsing Fields.
     * We are only interested in visiting annotations on Fields.
     */
    public static class MyFieldVisitor extends FieldVisitor
    {
        final FieldInfo _fieldInfo;
        final Set<? extends Handler> _handlers;

        public MyFieldVisitor(final Set<? extends Handler> handlers,
                              final ClassInfo classInfo,
                              final int access,
                              final String fieldName,
                              final String fieldType,
                              final String signature,
                              final Object value,
                              final int asmVersion)
        {
            super(asmVersion);
            _handlers = handlers;
            _fieldInfo = new FieldInfo(classInfo, fieldName, access, fieldType, signature, value);
        }

        /**
         * Parse an annotation found on a Field.
         */
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible)
        {
            String annotationName = normalize(desc);
            for (Handler h : _handlers)
            {
                h.handle(_fieldInfo, annotationName);
            }

            return null;
        }
    }

    /**
     * ASM visitor for a class.
     */
    public static class MyClassVisitor extends ClassVisitor
    {
        final int _asmVersion;
        final Resource _containingResource; //resource containing the class to parse
        final Resource _classResource; //resource of the class being parsed
        final Set<? extends Handler> _handlers;
        ClassInfo _ci;

        public MyClassVisitor(Set<? extends Handler> handlers, Resource containingResource, Resource classResource, int asmVersion)
        {
            super(asmVersion);
            _asmVersion = asmVersion;
            _handlers = handlers;
            _containingResource = containingResource;
            _classResource = classResource;
        }

        @Override
        public void visit(final int version,
                          final int access,
                          final String name,
                          final String signature,
                          final String superName,
                          final String[] interfaces)
        {
            //Check that the named class exists in the containingResource at the correct location.
            //eg given the class with name "com.foo.Acme" and the containingResource "jar:file://some/place/something.jar!/"
            //then the file "jar:file://some/place/something.jar!/com/foo/Acme.class" must exist.
            if (_containingResource != null && !checkClassContainment(_containingResource, _classResource, name))
            {
                throw new IllegalStateException("Class " + name + " not in correct location in " + _containingResource);
            }

            _ci = new ClassInfo(_containingResource, normalize(name), version, access, signature, normalize(superName), normalize(interfaces));
            for (Handler h : _handlers)
            {
                h.handle(_ci);
            }
        }

        /**
         * Visit an annotation on a Class
         */
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible)
        {
            String annotationName = normalize(desc);
            for (Handler h : _handlers)
            {
                h.handle(_ci, annotationName);
            }
            return null;
        }

        /**
         * Visit a method to extract its annotations
         */
        @Override
        public MethodVisitor visitMethod(final int access,
                                         final String name,
                                         final String methodDesc,
                                         final String signature,
                                         final String[] exceptions)
        {
            return new MyMethodVisitor(_handlers, _ci, access, name, methodDesc, signature, exceptions, _asmVersion);
        }

        /**
         * Visit a field to extract its annotations
         */
        @Override
        public FieldVisitor visitField(final int access,
                                       final String fieldName,
                                       final String fieldType,
                                       final String signature,
                                       final Object value)
        {
            return new MyFieldVisitor(_handlers, _ci, access, fieldName, fieldType, signature, value, _asmVersion);
        }
    }

    /**
     * Check whether the classname is located inside its containingResource at
     * the location that matches its package. The comparison accounts for classes
     * located within WEB-INF/classes.
     * @param containingResource the Resource that contains the class
     * @param classResource the Resource representing the class
     * @param classname the package-qualified name of the class
     * @return true if the containingResource contains a class file at the location matching the package name of the class
     */
    public static boolean checkClassContainment(Resource containingResource, Resource classResource, String classname)
    {
        if (containingResource == null || classResource == null)
            return false;
        Path relative = containingResource.getPathTo(classResource);
        if (relative == null)
            return false; //unable to be verified

        StringTokenizer tokenizer = new StringTokenizer(classname, "/", false);

        for (int i = 0; i < relative.getNameCount(); i++)
        {
            String s = relative.getName(i).toString();
            if (i == 0 && "WEB-INF".equalsIgnoreCase(s))
                continue;
            if (i == 1 && "classes".equalsIgnoreCase(s))
                continue;
            if (i == relative.getNameCount() - 1)
            {
                if ("class".equals(FileID.getExtension(s)))
                    s = s.substring(0, s.length() - 6);
            }

            if (!tokenizer.nextToken().equalsIgnoreCase(s))
                return false;
        }
        return true;
    }

    public AnnotationParser()
    {
        this(ASM_VERSION);
    }

    /**
     * @param asmVersion The target asm version or 0 for the internal version.
     */
    public AnnotationParser(int asmVersion)
    {
        if (asmVersion == 0)
            asmVersion = ASM_VERSION;
        _asmVersion = asmVersion;
    }

    /**
     * Parse a resource
     *
     * @param handlers the handlers to look for classes in
     * @param r the resource to parse
     * @throws Exception if unable to parse
     */
    public void parse(final Set<? extends Handler> handlers, Resource r) throws Exception
    {
        if (r == null)
            return;

        if (FileID.isJavaArchive(r.getPath()))
        {
            parseJar(handlers, r);
            return;
        }

        if (r.isDirectory())
        {
            parseDir(handlers, r);
            return;
        }

        if (FileID.isClassFile(r.getPath()))
        {
            parseClass(handlers, null, r);
        }

        if (LOG.isDebugEnabled())
            LOG.warn("Resource not able to be scanned for classes: {}", r);
    }

    /**
     * Parse all classes in a directory
     *
     * @param handlers the set of handlers to look for classes in
     * @param dirResource the resource representing the baseResource being scanned (jar, dir, etc)
     * @throws Exception if unable to parse
     */
    protected void parseDir(Set<? extends Handler> handlers, Resource dirResource) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Scanning dir {}", dirResource);

        assert dirResource.isDirectory();

        ExceptionUtil.MultiException multiException = new ExceptionUtil.MultiException();

        for (Resource candidate : dirResource.getAllResources())
        {
            // Skip directories
            if (candidate.isDirectory())
                continue;

            // Get the path relative to the base resource
            Path relative = dirResource.getPathTo(candidate);

            // select only relative non-hidden class files that are not modules nor versions
            if (relative == null ||
                FileID.isHidden(relative) ||
                FileID.isMetaInfVersions(relative) ||
                FileID.isModuleInfoClass(relative) ||
                !FileID.isClassFile(relative))
                continue;

            try
            {
                parseClass(handlers, dirResource, candidate);
            }
            catch (Exception ex)
            {
                multiException.add(new RuntimeException("Error scanning entry " + ex, ex));
            }
        }

        multiException.ifExceptionThrow();
    }

    /**
     * Parse a resource that is a jar file.
     *
     * @param handlers the handlers to look for classes in
     * @param jarResource the jar resource to parse
     * @throws Exception if unable to parse
     */
    protected void parseJar(Set<? extends Handler> handlers, Resource jarResource) throws Exception
    {
        if (jarResource == null)
            return;

        /*        if (!FileID.isJavaArchive(jarResource.getPath()))
            return;*/

        if (LOG.isDebugEnabled())
            LOG.debug("Scanning jar {}", jarResource);

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource insideJarResource = resourceFactory.newJarFileResource(jarResource.getURI());
            parseDir(handlers, insideJarResource);
        }
    }

    /**
     * Use ASM on a class
     *
     * @param handlers the handlers to look for classes in
     * @param containingResource the dir or jar that the class is contained within, can be null if not known
     * @param classResource the class file to parse
     * @throws IOException if unable to parse
     */
    protected void parseClass(Set<? extends Handler> handlers, Resource containingResource, Resource classResource) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Parse class from {}", classResource);

        try (InputStream in = Files.newInputStream(classResource.getPath()))
        {
            ClassReader reader = new ClassReader(in);
            reader.accept(new MyClassVisitor(handlers, containingResource, classResource, _asmVersion), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            String classname = normalize(reader.getClassName());
            URI location = classResource.getPath().toUri();
            URI existing = _parsedClassNames.putIfAbsent(classname, location);
            if (existing != null)
                LOG.warn("{} scanned from multiple locations: {}, {}", classname, existing, location);
        }
        catch (IllegalArgumentException | IOException e)
        {
            throw new IOException("Unable to parse class: " + classResource.getURI(), e);
        }
    }

    /**
     * Useful mostly for testing to expose the list of parsed classes.
     * @return the map of classnames to their URIs
     */
    Map<String, URI> getParsedClassNames()
    {
        return Collections.unmodifiableMap(_parsedClassNames);
    }
}
