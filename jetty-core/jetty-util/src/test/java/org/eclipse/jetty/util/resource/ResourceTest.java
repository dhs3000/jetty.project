//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith(WorkDirExtension.class)
public class ResourceTest
{
    private static final boolean DIR = true;
    private static final boolean EXISTS = true;
    private static final ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable();

    @AfterAll
    public static void afterAll()
    {
        resourceFactory.close();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    static class Scenario
    {
        Resource resource;
        String test;
        boolean exists;
        boolean dir;
        String content;

        Scenario(Scenario data, String path, boolean exists, boolean dir)
        {
            this.test = data.resource + "+" + path;
            resource = data.resource.resolve(path);
            this.exists = exists;
            this.dir = dir;
        }

        Scenario(Scenario data, String path, boolean exists, boolean dir, String content)
        {
            this.test = data.resource + "+" + path;
            resource = data.resource.resolve(path);
            this.exists = exists;
            this.dir = dir;
            this.content = content;
        }

        Scenario(URL url, boolean exists, boolean dir)
        {
            this.test = url.toString();
            this.exists = exists;
            this.dir = dir;
            resource = resourceFactory.newResource(url);
        }

        Scenario(String url, boolean exists, boolean dir)
        {
            this.test = url;
            this.exists = exists;
            this.dir = dir;
            resource = resourceFactory.newResource(url);
        }

        Scenario(URI uri, boolean exists, boolean dir)
        {
            this.test = uri.toASCIIString();
            this.exists = exists;
            this.dir = dir;
            resource = resourceFactory.newResource(uri);
        }

        Scenario(File file, boolean exists, boolean dir)
        {
            this.test = file.toString();
            this.exists = exists;
            this.dir = dir;
            resource = resourceFactory.newResource(file.toPath());
        }

        @Override
        public String toString()
        {
            return this.test;
        }
    }

    static class Scenarios extends ArrayList<Arguments>
    {
        final File fileRef;
        final URI uriRef;
        final String relRef;

        final Scenario[] baseCases;

        public Scenarios(String ref)
        {
            // relative directory reference
            this.relRef = FS.separators(ref);
            // File object reference
            this.fileRef = MavenTestingUtils.getProjectDir(relRef);
            // URI reference
            this.uriRef = fileRef.toURI();

            // create baseline cases
            baseCases = new Scenario[]{
                new Scenario(relRef, EXISTS, DIR),
                new Scenario(uriRef, EXISTS, DIR),
                new Scenario(fileRef, EXISTS, DIR)
            };

            // add all baseline cases
            for (Scenario bcase : baseCases)
            {
                addCase(bcase);
            }
        }

        public void addCase(Scenario ucase)
        {
            add(Arguments.of(ucase));
        }

        public void addAllSimpleCases(String subpath, boolean exists, boolean dir)
            throws Exception
        {
            addCase(new Scenario(FS.separators(relRef + subpath), exists, dir));
            addCase(new Scenario(uriRef.resolve(subpath).toURL(), exists, dir));
            addCase(new Scenario(new File(fileRef, subpath), exists, dir));
        }

        public Scenario addAllAddPathCases(String subpath, boolean exists, boolean dir)
        {
            Scenario bdata = null;

            for (Scenario bcase : baseCases)
            {
                bdata = new Scenario(bcase, subpath, exists, dir);
                addCase(bdata);
            }

            return bdata;
        }
    }

    public static Stream<Arguments> scenarios() throws Exception
    {
        Scenarios cases = new Scenarios("src/test/resources/");

        File testDir = MavenTestingUtils.getTargetTestingDir(ResourceTest.class.getName());
        FS.ensureEmpty(testDir);
        File tmpFile = new File(testDir, "test.tmp");
        FS.touch(tmpFile);

        cases.addCase(new Scenario(tmpFile.toString(), EXISTS, !DIR));

        // Some resource references.
        cases.addAllSimpleCases("resource.txt", EXISTS, !DIR);
        cases.addAllSimpleCases("NoName.txt", !EXISTS, !DIR);

        // Some addPath() forms
        cases.addAllAddPathCases("resource.txt", EXISTS, !DIR);
        cases.addAllAddPathCases("/resource.txt", EXISTS, !DIR);
        cases.addAllAddPathCases("//resource.txt", EXISTS, !DIR);
        cases.addAllAddPathCases("NoName.txt", !EXISTS, !DIR);
        cases.addAllAddPathCases("/NoName.txt", !EXISTS, !DIR);
        cases.addAllAddPathCases("//NoName.txt", !EXISTS, !DIR);

        Scenario tdata1 = cases.addAllAddPathCases("TestData", EXISTS, DIR);
        Scenario tdata2 = cases.addAllAddPathCases("TestData/", EXISTS, DIR);

        cases.addCase(new Scenario(tdata1, "alphabet.txt", EXISTS, !DIR, "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        cases.addCase(new Scenario(tdata2, "alphabet.txt", EXISTS, !DIR, "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));

        String urlRef = cases.uriRef.toASCIIString();
        resourceFactory.newResource(URI.create("jar:" + urlRef + "TestData/test.zip!/"));
        Scenario zdata = new Scenario("jar:" + urlRef + "TestData/test.zip!/", EXISTS, DIR);
        cases.addCase(zdata);

        cases.addCase(new Scenario(zdata, "Unknown", !EXISTS, !DIR));
        cases.addCase(new Scenario(zdata, "/Unknown/", !EXISTS, !DIR));

        cases.addCase(new Scenario(zdata, "subdir", EXISTS, DIR));
        cases.addCase(new Scenario(zdata, "/subdir/", EXISTS, DIR));
        cases.addCase(new Scenario(zdata, "alphabet", EXISTS, !DIR,
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        cases.addCase(new Scenario(zdata, "/subdir/alphabet", EXISTS, !DIR,
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ"));

        cases.addAllAddPathCases("/TestData/test/subdir/subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("//TestData/test/subdir/subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("/TestData//test/subdir/subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("/TestData/test//subdir/subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("/TestData/test/subdir//subsubdir/", EXISTS, DIR);

        cases.addAllAddPathCases("TestData/test/subdir/subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("TestData/test/subdir/subsubdir//", EXISTS, DIR);
        cases.addAllAddPathCases("TestData/test/subdir//subsubdir/", EXISTS, DIR);
        cases.addAllAddPathCases("TestData/test//subdir/subsubdir/", EXISTS, DIR);

        cases.addAllAddPathCases("/TestData/../TestData/test/subdir/subsubdir/", EXISTS, DIR);

        return cases.stream();
    }

    public WorkDir workDir;

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceExists(Scenario data)
    {
        assertThat("Exists: " + data.resource.getName(), data.resource.exists(), equalTo(data.exists));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceDir(Scenario data)
    {
        assertThat("Is Directory: " + data.test, data.resource.isDirectory(), equalTo(data.dir));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testEncodeAddPath(Scenario data)
    {
        if (data.dir)
        {
            Resource r = data.resource.resolve("foo%25/b%20r");
            assertThat(r.getPath().toString(), Matchers.anyOf(Matchers.endsWith("foo%/b r"), Matchers.endsWith("foo%\\b r")));
            assertThat(r.getURI().toString(), Matchers.endsWith("/foo%25/b%20r"));
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResourceContent(Scenario data)
        throws Exception
    {
        Assumptions.assumeTrue(data.content != null);

        InputStream in = data.resource.newInputStream();
        String c = IO.toString(in);
        assertThat("Content: " + data.test, c, startsWith(data.content));
    }

    @Test
    public void testGlobPath()
    {
        Path testDir = MavenTestingUtils.getTargetTestingPath("testGlobPath");
        FS.ensureEmpty(testDir);

        try
        {
            String globReference = testDir.toAbsolutePath() + File.separator + '*';
            Resource globResource = resourceFactory.newResource(globReference);
            assertNotNull(globResource, "Should have produced a Resource");
        }
        catch (InvalidPathException e)
        {
            // if unable to reference the glob file, no point testing the rest.
            // this is the path that Microsoft Windows takes.
            assumeTrue(false, "Glob not supported on this OS");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void testEqualsWindowsAltUriSyntax() throws Exception
    {
        URI a = new URI("file:/C:/foo/bar");
        URI b = new URI("file:///C:/foo/bar");

        Resource ra = resourceFactory.newResource(a);
        Resource rb = resourceFactory.newResource(b);

        assertEquals(rb, ra);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void testEqualsWindowsCaseInsensitiveDrive() throws Exception
    {
        URI a = new URI("file:///c:/foo/bar");
        URI b = new URI("file:///C:/foo/bar");
        
        Resource ra = resourceFactory.newResource(a);
        Resource rb = resourceFactory.newResource(b);

        assertEquals(rb, ra);
    }

    @Test
    public void testResourceExtraSlashStripping()
    {
        Resource ra = resourceFactory.newResource("file:/a/b/c");
        Resource rb = ra.resolve("///");
        Resource rc = ra.resolve("///d/e///f");

        assertEquals(ra, rb);
        assertEquals(rc.getURI().getPath(), "/a/b/c/d/e/f");

        Resource rd = resourceFactory.newResource("file:///a/b/c");
        Resource re = rd.resolve("///");
        Resource rf = rd.resolve("///d/e///f");

        assertEquals(rd, re);
        assertEquals(rf.getURI().getPath(), "/a/b/c/d/e/f");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void testWindowsResourceFromString()
    {
        // Check strings that look like URIs but actually are paths.
        Resource ra = resourceFactory.newResource("C:\\foo\\bar");
        Resource rb = resourceFactory.newResource("C:/foo/bar");
        Resource rc = resourceFactory.newResource("C:///foo/bar");

        assertEquals(rb, ra);
        assertEquals(rb, rc);
    }

    @Test
    public void testClimbAboveBase()
    {
        Resource resource = resourceFactory.newResource("/foo/bar");
        assertThrows(IllegalArgumentException.class, () -> resource.resolve(".."));
        assertThrows(IllegalArgumentException.class, () -> resource.resolve("./.."));
        assertThrows(IllegalArgumentException.class, () -> resource.resolve("./../bar"));
    }

    @Test
    public void testDotAliasDirExists(WorkDir workDir) throws IOException
    {
        Path dir = workDir.getEmptyPathDir().resolve("foo/bar");
        FS.ensureDirExists(dir);
        Resource resource = resourceFactory.newResource(dir);
        Resource dot = resource.resolve(".");
        assertNotNull(dot);
        assertTrue(dot.exists());
        assertTrue(dot.isAlias(), "Reference to '.' is an alias to itself");
        assertTrue(Files.isSameFile(dot.getPath(), Paths.get(dot.getTargetURI())));
    }

    @Test
    public void testDotAliasDirDoesNotExist(WorkDir workDir)
    {
        Path dir = workDir.getEmptyPathDir().resolve("foo/bar");
        // at this point we have a directory reference that does not exist
        Resource resource = resourceFactory.newResource(dir);
        Resource dot = resource.resolve(".");
        assertNotNull(dot);
        assertFalse(dot.exists());
        assertFalse(dot.isAlias(), "Reference to '.' is not an alias as directory doesn't exist");
    }

    @Test
    public void testDotAliasFileExists(WorkDir workDir) throws IOException
    {
        Path dir = workDir.getEmptyPathDir().resolve("foo");
        FS.ensureDirExists(dir);
        Path file = dir.resolve("bar.txt");
        FS.touch(file);
        Resource resource = resourceFactory.newResource(file);
        Resource dot = resource.resolve(".");
        assertNotNull(dot);
        assertTrue(dot.exists());
        assertTrue(dot.isAlias(), "Reference to '.' is an alias to itself");
        assertTrue(Files.isSameFile(dot.getPath(), Paths.get(dot.getTargetURI())));
    }

    @Test
    public void testDotAliasFileDoesNotExists(WorkDir workDir) throws IOException
    {
        Path dir = workDir.getEmptyPathDir().resolve("foo");
        FS.ensureDirExists(dir);
        Path file = dir.resolve("bar.txt");
        // at this point we have a file reference that does not exist
        assertFalse(Files.exists(file));
        Resource resource = resourceFactory.newResource(file);
        Resource dot = resource.resolve(".");
        assertNotNull(dot);
        assertFalse(dot.exists());
        assertFalse(dot.isAlias(), "Reference to '.' is not an alias as file doesn't exist");
    }

    @Test
    public void testJrtResourceModule()
    {
        Resource resource = ResourceFactory.root().newResource("jrt:/java.base");

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(true));
        assertThat(resource.length(), is(0L));
    }

    @Test
    public void testJrtResourceAllModules()
    {
        Resource resource = ResourceFactory.root().newResource("jrt:/");

        assertThat(resource.exists(), is(true));
        assertThat(resource.isDirectory(), is(true));
        assertThat(resource.length(), is(0L));
    }

    /**
     * Test a class path resource for existence.
     */
    @Test
    public void testClassPathResourceClassRelative()
    {
        final String classPathName = "Resource.class";

        Resource resource = resourceFactory.newClassPathResource(classPathName);

        // A class path cannot be a directory
        assertFalse(resource.isDirectory(), "Class path cannot be a directory.");

        // A class path must exist
        assertTrue(resource.exists(), "Class path resource does not exist.");
    }

    /**
     * Test a class path resource for existence.
     */
    @Test
    public void testClassPathResourceClassAbsolute()
    {
        final String classPathName = "/org/eclipse/jetty/util/resource/Resource.class";

        Resource resource = resourceFactory.newClassPathResource(classPathName);

        // A class path cannot be a directory
        assertFalse(resource.isDirectory(), "Class path cannot be a directory.");

        // A class path must exist
        assertTrue(resource.exists(), "Class path resource does not exist.");
    }

    /**
     * Test a class path resource for directories.
     *
     * @throws Exception failed test
     */
    @Test
    public void testClassPathResourceDirectory() throws Exception
    {
        // If the test runs in the module-path, resource "/" cannot be found.
        assumeFalse(Resource.class.getModule().isNamed());

        final String classPathName = "/";

        Resource resource = resourceFactory.newClassPathResource(classPathName);

        // A class path must be a directory
        assertTrue(resource.isDirectory(), "Class path must be a directory.");

        assertTrue(Files.isDirectory(resource.getPath()), "Class path returned file must be a directory.");

        // A class path must exist
        assertTrue(resource.exists(), "Class path resource does not exist.");
    }

    /**
     * Test a class path resource for a file.
     *
     * @throws Exception failed test
     */
    @Test
    public void testClassPathResourceFile() throws Exception
    {
        final String fileName = "resource.txt";
        final String classPathName = "/" + fileName;

        // Will locate a resource in the class path
        Resource resource = resourceFactory.newClassPathResource(classPathName);

        // A class path cannot be a directory
        assertFalse(resource.isDirectory(), "Class path must be a directory.");

        assertNotNull(resource);

        Path path = resource.getPath();

        assertEquals(fileName, path.getFileName().toString(), "File name from class path is not equal.");
        assertTrue(Files.isRegularFile(path), "File returned from class path should be a regular file.");

        // A class path must exist
        assertTrue(resource.exists(), "Class path resource does not exist.");
    }
}