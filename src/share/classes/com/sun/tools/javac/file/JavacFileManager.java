/*
 * Copyright (c) 2005, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.tools.javac.file;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import javax.lang.model.SourceVersion;
import javax.tools.ExtendedLocation;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.ModuleFileManager;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import static javax.tools.StandardLocation.*;

import com.sun.tools.javac.file.Locations.Path;
import com.sun.tools.javac.file.RelativePath.RelativeFile;
import com.sun.tools.javac.file.RelativePath.RelativeDirectory;
import com.sun.tools.javac.util.BaseFileManager;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;

import static com.sun.tools.javac.main.Option.*;

/**
 * This class provides access to the source, class and other files
 * used by the compiler and related tools.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b>
 */
public class JavacFileManager
        extends BaseFileManager
        implements StandardJavaFileManager, ModuleFileManager {

    public static char[] toArray(CharBuffer buffer) {
        if (buffer.hasArray())
            return ((CharBuffer)buffer.compact().flip()).array();
        else
            return buffer.toString().toCharArray();
    }

    private FSInfo fsInfo;

    private boolean contextUseOptimizedZip;
    private ZipFileIndexCache zipFileIndexCache;

    private final Set<JavaFileObject.Kind> sourceOrClass =
        EnumSet.of(JavaFileObject.Kind.SOURCE, JavaFileObject.Kind.CLASS);

    protected boolean mmappedIO;
    protected boolean ignoreSymbolFile;

    protected enum SortFiles implements Comparator<File> {
        FORWARD {
            public int compare(File f1, File f2) {
                return f1.getName().compareTo(f2.getName());
            }
        },
        REVERSE {
            public int compare(File f1, File f2) {
                return -f1.getName().compareTo(f2.getName());
            }
        };
    };
    protected SortFiles sortFiles;

    /**
     * Register a Context.Factory to create a JavacFileManager.
     */
    public static void preRegister(Context context) {
        context.put(JavaFileManager.class, new Context.Factory<JavaFileManager>() {
            public JavaFileManager make(Context c) {
                return new JavacFileManager(c, true, null);
            }
        });
    }

    /**
     * Create a JavacFileManager using a given context, optionally registering
     * it as the JavaFileManager for that context.
     */
    public JavacFileManager(Context context, boolean register, Charset charset) {
        super(charset);
        if (register)
            context.put(JavaFileManager.class, this);
        setContext(context);
    }

    /**
     * Set the context for JavacFileManager.
     */
    @Override
    public void setContext(Context context) {
        super.setContext(context);

        fsInfo = FSInfo.instance(context);

        contextUseOptimizedZip = options.getBoolean("useOptimizedZip", true);
        if (contextUseOptimizedZip)
            zipFileIndexCache = ZipFileIndexCache.getSharedInstance();

        mmappedIO = options.isSet("mmappedIO");
        ignoreSymbolFile = options.isSet("ignore.symbol.file");

        String sf = options.get("sortFiles");
        if (sf != null) {
            sortFiles = (sf.equals("reverse") ? SortFiles.REVERSE : SortFiles.FORWARD);
        }
    }

    @Override
    public boolean isDefaultBootClassPath() {
        return locations.isDefaultBootClassPath();
    }

    public JavaFileObject getFileForInput(String name) {
        return getRegularFile(new File(name));
    }

    public JavaFileObject getRegularFile(File file) {
        return new RegularFileObject(this, null, file);
    }

    public JavaFileObject getFileForOutput(String classname,
                                           JavaFileObject.Kind kind,
                                           JavaFileObject sibling)
        throws IOException
    {
        return getJavaFileForOutput(CLASS_OUTPUT, classname, kind, sibling);
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
        ListBuffer<File> files = new ListBuffer<File>();
        for (String name : names)
            files.append(new File(nullCheck(name)));
        return getJavaFileObjectsFromFiles(files.toList());
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
        return getJavaFileObjectsFromStrings(Arrays.asList(nullCheck(names)));
    }

    private static boolean isValidName(String name) {
        // Arguably, isValidName should reject keywords (such as in SourceVersion.isName() ),
        // but the set of keywords depends on the source level, and we don't want
        // impls of JavaFileManager to have to be dependent on the source level.
        // Therefore we simply check that the argument is a sequence of identifiers
        // separated by ".".
        for (String s : name.split("\\.", -1)) {
            if (!SourceVersion.isIdentifier(s))
                return false;
        }
        return true;
    }

    private static void validateClassName(String className) {
        if (!isValidName(className))
            throw new IllegalArgumentException("Invalid class name: " + className);
    }

    private static void validatePackageName(String packageName) {
        if (packageName.length() > 0 && !isValidName(packageName))
            throw new IllegalArgumentException("Invalid packageName name: " + packageName);
    }

    public static void testName(String name,
                                boolean isValidPackageName,
                                boolean isValidClassName)
    {
        try {
            validatePackageName(name);
            if (!isValidPackageName)
                throw new AssertionError("Invalid package name accepted: " + name);
            printAscii("Valid package name: \"%s\"", name);
        } catch (IllegalArgumentException e) {
            if (isValidPackageName)
                throw new AssertionError("Valid package name rejected: " + name);
            printAscii("Invalid package name: \"%s\"", name);
        }
        try {
            validateClassName(name);
            if (!isValidClassName)
                throw new AssertionError("Invalid class name accepted: " + name);
            printAscii("Valid class name: \"%s\"", name);
        } catch (IllegalArgumentException e) {
            if (isValidClassName)
                throw new AssertionError("Valid class name rejected: " + name);
            printAscii("Invalid class name: \"%s\"", name);
        }
    }

    private static void printAscii(String format, Object... args) {
        String message;
        try {
            final String ascii = "US-ASCII";
            message = new String(String.format(null, format, args).getBytes(ascii), ascii);
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new AssertionError(ex);
        }
        System.out.println(message);
    }


    /**
     * Insert all files in subdirectory subdirectory of directory directory
     * which match fileKinds into resultList
     */
    private void listDirectory(Location location, File directory,
                               RelativeDirectory subdirectory,
                               Set<JavaFileObject.Kind> fileKinds,
                               boolean recurse,
                               ListBuffer<JavaFileObject> resultList) {
        File d = subdirectory.getFile(directory);
        if (!caseMapCheck(d, subdirectory))
            return;

        File[] files = d.listFiles();
        if (files == null)
            return;

        if (sortFiles != null)
            Arrays.sort(files, sortFiles);

        for (File f: files) {
            String fname = f.getName();
            if (f.isDirectory()) {
                if (recurse && SourceVersion.isIdentifier(fname)) {
                    listDirectory(location,
                                  directory,
                                  new RelativeDirectory(subdirectory, fname),
                                  fileKinds,
                                  recurse,
                                  resultList);
                }
            } else {
                if (isValidFile(fname, fileKinds)) {
                    JavaFileObject fe =
                        new RegularFileObject(this, location, fname, new File(d, fname));
                    resultList.append(fe);
                }
            }
        }
    }

    /**
     * Insert all files in subdirectory subdirectory of archive archive
     * which match fileKinds into resultList
     */
    private void listArchive(Archive archive,
                               RelativeDirectory subdirectory,
                               Set<JavaFileObject.Kind> fileKinds,
                               boolean recurse,
                               ListBuffer<JavaFileObject> resultList) {
        // Get the files directly in the subdir
        List<String> files = archive.getFiles(subdirectory);
        if (files != null) {
            for (; !files.isEmpty(); files = files.tail) {
                String file = files.head;
                if (isValidFile(file, fileKinds)) {
                    resultList.append(archive.getFileObject(subdirectory, file));
                }
            }
        }
        if (recurse) {
            for (RelativeDirectory s: archive.getSubdirectories()) {
                if (subdirectory.contains(s)) {
                    // Because the archive map is a flat list of directories,
                    // the enclosing loop will pick up all child subdirectories.
                    // Therefore, there is no need to recurse deeper.
                    listArchive(archive, s, fileKinds, false, resultList);
                }
            }
        }
    }

    /**
     * container is a directory, a zip file, or a non-existant path.
     * Insert all files in subdirectory subdirectory of container which
     * match fileKinds into resultList
     */
    private void listContainer(Location location, File container,
                               RelativeDirectory subdirectory,
                               Set<JavaFileObject.Kind> fileKinds,
                               boolean recurse,
                               ListBuffer<JavaFileObject> resultList) {
        Archive archive = archives.get(container);
        if (archive == null) {
            // archives are not created for directories.
            if  (fsInfo.isDirectory(container)) {
                listDirectory(location,
                              container,
                              subdirectory,
                              fileKinds,
                              recurse,
                              resultList);
                return;
            }

            // Not a directory; either a file or non-existant, create the archive
            try {
                archive = openArchive(location, container);
            } catch (IOException ex) {
                log.error("error.reading.file",
                          container, getMessage(ex));
                return;
            }
        }
        listArchive(archive,
                    subdirectory,
                    fileKinds,
                    recurse,
                    resultList);
    }

    private boolean isValidFile(String s, Set<JavaFileObject.Kind> fileKinds) {
        JavaFileObject.Kind kind = getKind(s);
        return fileKinds.contains(kind);
    }

    private static final boolean fileSystemIsCaseSensitive =
        File.separatorChar == '/';

    /** Hack to make Windows case sensitive. Test whether given path
     *  ends in a string of characters with the same case as given name.
     *  Ignore file separators in both path and name.
     */
    private boolean caseMapCheck(File f, RelativePath name) {
        if (fileSystemIsCaseSensitive) return true;
        // Note that getCanonicalPath() returns the case-sensitive
        // spelled file name.
        String path;
        try {
            path = f.getCanonicalPath();
        } catch (IOException ex) {
            return false;
        }
        char[] pcs = path.toCharArray();
        char[] ncs = name.path.toCharArray();
        int i = pcs.length - 1;
        int j = ncs.length - 1;
        while (i >= 0 && j >= 0) {
            while (i >= 0 && pcs[i] == File.separatorChar) i--;
            while (j >= 0 && ncs[j] == '/') j--;
            if (i >= 0 && j >= 0) {
                if (pcs[i] != ncs[j]) return false;
                i--;
                j--;
            }
        }
        return j < 0;
    }

    private ModuleMode moduleMode;

    @Override // javax.tools.ModuleFileManager
    public ModuleMode getModuleMode() {
        if (moduleMode == null) {
//            boolean cp = hasLocation(StandardLocation.CLASS_PATH) || options.isSet(CLASSPATH);
            // MUST-FIX:  mp && !cp does not work well in 199 mode, need something better,
            // either explicit setModuleMode, or ability to test explicit setLocation -- uugh
            boolean cp = options.isSet(CLASSPATH);
            boolean mp = hasLocation(StandardLocation.MODULE_PATH) || options.isSet(MODULEPATH);
            if (mp && !cp)
                moduleMode = ModuleMode.MULTIPLE;
            else
                moduleMode = ModuleMode.SINGLE;
        }
        return moduleMode;
    }

    @Override // javax.tools.ModuleFileManager
    public Location getModuleLocation(Location locn, JavaFileObject fo, String pkgName)
            throws InvalidLocationException, InvalidFileObjectException {
        if (getModuleMode() == ModuleMode.SINGLE)
            return locn;
        else {
            fo.getClass(); // null check
            if (!(fo instanceof BaseFileObject))
                throw new IllegalArgumentException();

            if (!hasLocation(locn))
                throw new InvalidLocationException();
            String tag = ((BaseFileObject) fo).inferModuleTag(pkgName);
            if (tag == null)
                throw new InvalidFileObjectException();
            return getModuleLocation(locn, tag);
        }
    }

    private Map<Location,Iterable<Location>> moduleLocations =
            new LinkedHashMap<Location,Iterable<Location>>();

    @Override // javax.tools.ModuleFileManager
    public Iterable<Location> getModuleLocations(Location locn) {
        //System.err.println("JavacFileManager.getModuleLocations " + getModuleMode() + " " + locn);

        Iterable<Location> result = moduleLocations.get(locn);
        if (result == null) {
            Iterable<File> files = locations.getLocation(locn);
            if (files == null)
                result = List.<Location>nil();
            else {
                Set<Location> locns = new LinkedHashSet<Location>();
                for (File file: files) {
                    if (file.isDirectory()) {
                        for (File f: file.listFiles()) {
                            String tag = null;
                            if (f.isDirectory())
                                tag = f.getName();
//                            else if (isArchive(f)) {
//                                String name = f.getName();
//                                tag = name.substring(0, name.lastIndexOf("."));
//                            }
                            // FIXME: check if tag has already been seen
                            if (tag != null)
                                locns.add(getModuleLocation(locn, tag));
                        }
                    } else {
                        // ignore archive files for now, these would be "module archive
                        // files", containing multiple modules in a new but obvious way
                    }

                }
                result = locns;
            }
        }

//        System.err.println("JavacFileManager.getModuleLocations.result " + result);
        return result;
    }

    // cleared by setLocation
    private Map<String, Location> locationCache = new HashMap<String,Location>();

    @Override // javax.tools.ModuleFileManager
    public Location join(Iterable<? extends Location> list)
            throws IllegalArgumentException {
        StringBuilder sb = new StringBuilder("{");
        String sep = "";
        for (Location l: list) {
            if (!locations.isSupportedLocation(l))
                throw new IllegalArgumentException(l.toString());
            sb.append(sep);
            sb.append(l.getName());
            sep = ",";
        }
        sb.append("}");
        String name = sb.toString();

        // ugly, rethink this (using name for key)
        Location result = locationCache.get(name);
        if (result == null) {
            // FIXME, could flatten list, and/or use a LinkedHashSet to
            // canonicalize list
            result = new CompositeLocation(list, this);
            locationCache.put(name, result);
        }

//        System.err.println("JavacFileManager.join: " + toString(locations) + " = " + result);
        return result;
    }

    // Get a location for all the containers named "tag" on given location
    // Containers may be either directories or archive files.
    protected Location getModuleLocation(Location location, String tag) {
        // TODO: should reject bad use when location is already a module location
        // TODO: should honor location.isOutput()
        String name = location.getName() + "[" + tag + "]";
        Location result = locationCache.get(name);
        if (result == null) {
            Iterable<? extends File> files = locations.getLocation(location);
            if (files == null)
                throw new IllegalArgumentException(location.getName());
            Path p = locations.new Path();
            if (files != null) {
                for (File file: files) {
                    File dir = new File(file, tag);
                    if (dir.exists() && dir.isDirectory() || location.isOutputLocation())
                        p.addFile(dir);
                    else {
                        File jar = new File(file, tag + ".jar");
                        if (jar.exists() && jar.isFile())
                            p.addFile(jar);
                    }
                }
            }
            result = locations.createLocation(p, name, locations.getOrigin(location));
            locationCache.put(name, result);
        }
        return result;
    }

    /**
     * Update a location based on the bootclasspath options.
     * @param l the default platform location if no bootclasspath options are given
     * @param first whether or not this is the first platform location
     * @param last whether or not this is the last platform location
     * @return a list of locations based on the default location and on the
     *  values of any bootclasspath options.
     */
    public List<Location> augmentPlatformLocation(Location l, boolean first, boolean last) {
        if (l == StandardLocation.PLATFORM_CLASS_PATH) {
            assert (first && last);
            return List.of(l);
        }

        Path ppPrepend = first ? locations.getPlatformPathPrepend() : null;
        Path ppBase = locations.getPlatformPathBase();
        Path ppAppend = last ? locations.getPlatformPathAppend() : null;

        ListBuffer<Location> results = new ListBuffer<Location>();
        if (ppPrepend != null)
            results.add(locations.createLocation(ppPrepend, StandardLocation.PLATFORM_CLASS_PATH));
        if (ppBase != null) {
            if (first)
                results.add(locations.createLocation(ppBase, StandardLocation.PLATFORM_CLASS_PATH));
        } else
            results.add(l);
        if (ppAppend != null)
            results.add(locations.createLocation(ppAppend, StandardLocation.PLATFORM_CLASS_PATH));
        //System.out.println("JFM:augmentPlatformLocation: " + l + " " + first + " " + last + " " + results);
        return results.toList();
    }

    /**
     * An archive provides a flat directory structure of a ZipFile by
     * mapping directory names to lists of files (basenames).
     */
    public interface Archive {
        void close() throws IOException;

        boolean contains(RelativePath name);

        JavaFileObject getFileObject(RelativeDirectory subdirectory, String file);

        List<String> getFiles(RelativeDirectory subdirectory);

        Set<RelativeDirectory> getSubdirectories();
    }

    public class MissingArchive implements Archive {
        final File zipFileName;
        public MissingArchive(File name) {
            zipFileName = name;
        }
        public boolean contains(RelativePath name) {
            return false;
        }

        public void close() {
        }

        public JavaFileObject getFileObject(RelativeDirectory subdirectory, String file) {
            return null;
        }

        public List<String> getFiles(RelativeDirectory subdirectory) {
            return List.nil();
        }

        public Set<RelativeDirectory> getSubdirectories() {
            return Collections.emptySet();
        }

        @Override
        public String toString() {
            return "MissingArchive[" + zipFileName + "]";
        }
    }

    /** A directory of zip files already opened.
     */
    Map<File, Archive> archives = new HashMap<File,Archive>();

    private static final String[] symbolFileLocation = { "lib", "ct.sym" };
    private static final RelativeDirectory symbolFilePrefix
            = new RelativeDirectory("META-INF/sym/rt.jar/");

    /*
     * This method looks for a ZipFormatException and takes appropriate
     * evasive action. If there is a failure in the fast mode then we
     * fail over to the platform zip, and allow it to deal with a potentially
     * non compliant zip file.
     */
    protected Archive openArchive(Location location, File zipFilename) throws IOException {
        try {
            return openArchive(location, zipFilename, contextUseOptimizedZip);
        } catch (IOException ioe) {
            if (ioe instanceof ZipFileIndex.ZipFormatException) {
                return openArchive(location, zipFilename, false);
            } else {
                throw ioe;
            }
        }
    }

    /** Open a new zip file directory, and cache it.
     */
    private Archive openArchive(Location location, File zipFileName, boolean useOptimizedZip) throws IOException {
        File origZipFileName = zipFileName;
        if (!ignoreSymbolFile && locations.isDefaultBootClassPathRtJar(zipFileName)) {
            File file = zipFileName.getParentFile().getParentFile(); // ${java.home}
            if (new File(file.getName()).equals(new File("jre")))
                file = file.getParentFile();
            // file == ${jdk.home}
            for (String name : symbolFileLocation)
                file = new File(file, name);
            // file == ${jdk.home}/lib/ct.sym
            if (file.exists())
                zipFileName = file;
        }

        Archive archive;
        try {

            ZipFile zdir = null;

            boolean usePreindexedCache = false;
            String preindexCacheLocation = null;

            if (!useOptimizedZip) {
                zdir = new ZipFile(zipFileName);
            } else {
                usePreindexedCache = options.isSet("usezipindex");
                preindexCacheLocation = options.get("java.io.tmpdir");
                String optCacheLoc = options.get("cachezipindexdir");

                if (optCacheLoc != null && optCacheLoc.length() != 0) {
                    if (optCacheLoc.startsWith("\"")) {
                        if (optCacheLoc.endsWith("\"")) {
                            optCacheLoc = optCacheLoc.substring(1, optCacheLoc.length() - 1);
                        }
                        else {
                            optCacheLoc = optCacheLoc.substring(1);
                        }
                    }

                    File cacheDir = new File(optCacheLoc);
                    if (cacheDir.exists() && cacheDir.canWrite()) {
                        preindexCacheLocation = optCacheLoc;
                        if (!preindexCacheLocation.endsWith("/") &&
                            !preindexCacheLocation.endsWith(File.separator)) {
                            preindexCacheLocation += File.separator;
                        }
                    }
                }
            }

            if (origZipFileName == zipFileName) {
                if (!useOptimizedZip) {
                    archive = new ZipArchive(this, location, zdir);
                } else {
                    archive = new ZipFileIndexArchive(this, location,
                                    zipFileIndexCache.getZipFileIndex(zipFileName,
                                    null,
                                    usePreindexedCache,
                                    preindexCacheLocation,
                                    options.isSet("writezipindexfiles")));
                }
            } else {
                if (!useOptimizedZip) {
                    archive = new SymbolArchive(this, location, origZipFileName, zdir, symbolFilePrefix);
                } else {
                    archive = new ZipFileIndexArchive(this, location,
                                    zipFileIndexCache.getZipFileIndex(zipFileName,
                                    symbolFilePrefix,
                                    usePreindexedCache,
                                    preindexCacheLocation,
                                    options.isSet("writezipindexfiles")));
                }
            }
        } catch (FileNotFoundException ex) {
            archive = new MissingArchive(zipFileName);
        } catch (ZipFileIndex.ZipFormatException zfe) {
            throw zfe;
        } catch (IOException ex) {
            if (zipFileName.exists())
                log.error("error.reading.file", zipFileName, getMessage(ex));
            archive = new MissingArchive(zipFileName);
        }

        archives.put(origZipFileName, archive);
        return archive;
    }

    /** Flush any output resources.
     */
    @Override // javax.tools.JavaFileManager
    public void flush() {
        contentCache.clear();
    }

    /**
     * Close the JavaFileManager, releasing resources.
     */
    @Override // javax.tools.JavaFileManager
    public void close() {
        for (Iterator<Archive> i = archives.values().iterator(); i.hasNext(); ) {
            Archive a = i.next();
            i.remove();
            try {
                a.close();
            } catch (IOException e) {
            }
        }
    }

    private String defaultEncodingName;
    private String getDefaultEncodingName() {
        if (defaultEncodingName == null) {
            defaultEncodingName =
                new OutputStreamWriter(new ByteArrayOutputStream()).getEncoding();
        }
        return defaultEncodingName;
    }

    @Override // javax.tools.JavaFileManager
    public ClassLoader getClassLoader(Location location) {
        nullCheck(location);
        Iterable<? extends File> path = getLocation(location);
        if (path == null)
            return null;
        ListBuffer<URL> lb = new ListBuffer<URL>();
        for (File f: path) {
            try {
                lb.append(f.toURI().toURL());
            } catch (MalformedURLException e) {
                throw new AssertionError(e);
            }
        }

        return getClassLoader(lb.toArray(new URL[lb.size()]));
    }

    @Override // javax.tools.JavaFileManager
    public Iterable<JavaFileObject> list(Location location,
                                         String packageName,
                                         Set<JavaFileObject.Kind> kinds,
                                         boolean recurse)
        throws IOException
    {
        // validatePackageName(packageName);
        nullCheck(location);
        nullCheck(packageName);
        nullCheck(kinds);

        if (location instanceof ExtendedLocation) {
            return ((ExtendedLocation) location).list(packageName, kinds, recurse);
        }

        Iterable<? extends File> files = locations.getLocation(location);
        if (files == null)
            return List.nil();
        RelativeDirectory subdirectory = RelativeDirectory.forPackage(packageName);
        ListBuffer<JavaFileObject> results = new ListBuffer<JavaFileObject>();

        for (File file: files) {
            listContainer(location, file, subdirectory, kinds, recurse, results);
        }

        return results.toList();
    }

    @Override // javax.tools.JavaFileManager
    public String inferBinaryName(Location location, JavaFileObject file) {
        file.getClass(); // null check
        location.getClass(); // null check

        if (location instanceof ExtendedLocation)
            return ((ExtendedLocation) location).inferBinaryName(file);

        // Need to match the path semantics of list(location, ...)
        Iterable<? extends File> path = getLocation(location);
        if (path == null) {
            return null;
        }

        if (file instanceof BaseFileObject) {
            return ((BaseFileObject) file).inferBinaryName(path);
        } else
//            throw new IllegalArgumentException(file.getClass().getName() + ":" + file.toString());
            return null; // FIXME -- seems OK per spec but need to check
    }

    @Override // javax.tools.JavaFileManager
    public boolean isSameFile(FileObject a, FileObject b) {
        nullCheck(a);
        nullCheck(b);
        if (!(a instanceof BaseFileObject))
            throw new IllegalArgumentException("Not supported: " + a);
        if (!(b instanceof BaseFileObject))
            throw new IllegalArgumentException("Not supported: " + b);
        return a.equals(b);
    }

    @Override // javax.tools.JavaFileManager
    public boolean hasLocation(Location location) {
        return (location instanceof ExtendedLocation || (getLocation(location) != null));
    }

    @Override // javax.tools.JavaFileManager
    public JavaFileObject getJavaFileForInput(Location location,
                                              String className,
                                              JavaFileObject.Kind kind)
        throws IOException
    {
        nullCheck(location);
        // validateClassName(className);g518

        nullCheck(className);
        nullCheck(kind);
        if (!sourceOrClass.contains(kind))
            throw new IllegalArgumentException("Invalid kind: " + kind);
        return getFileForInput(location, RelativeFile.forClass(className, kind), kind);
    }

    @Override // javax.tools.JavaFileManager
    public FileObject getFileForInput(Location location,
                                      String packageName,
                                      String relativeName)
        throws IOException
    {
        nullCheck(location);
        // validatePackageName(packageName);
        nullCheck(packageName);
        if (!isRelativeUri(relativeName))
            throw new IllegalArgumentException("Invalid relative name: " + relativeName);
        RelativeFile name = packageName.length() == 0
            ? new RelativeFile(relativeName)
            : new RelativeFile(RelativeDirectory.forPackage(packageName), relativeName);
        return getFileForInput(location, name, getKindForName(name.path));
    }

    private JavaFileObject.Kind getKindForName(String name) {
        for (JavaFileObject.Kind k: JavaFileObject.Kind.values()) {
            if (k != JavaFileObject.Kind.OTHER && name.endsWith(k.extension)) {
                return k;
            }
        }
        return JavaFileObject.Kind.OTHER;
    }

    private JavaFileObject getFileForInput(Location location, RelativeFile name,
                JavaFileObject.Kind kind) throws IOException {
        if (location instanceof CompositeLocation) {
            for (Location l: ((CompositeLocation) location).locations) {
                JavaFileObject fo = getFileForInput(l, name, kind);
                if (fo != null)
                    return fo;
            }
            return null;
        }

        Iterable<? extends File> files = locations.getLocation(location);
        if (files == null)
            return null;

        for (File file: files) {
            Archive a = archives.get(file);
            if (a == null) {
                if (fsInfo.isDirectory(file)) {
                    File f = name.getFile(file);
                    if (f.exists())
                        return new RegularFileObject(this, location, f);
                    continue;
                }
                // Not a directory, create the archive
                a = openArchive(location, file);
            }
            // Process the archive
            if (a.contains(name)) {
                return a.getFileObject(name.dirname(), name.basename());
            }
        }
        return null;
    }

    @Override // javax.tools.JavaFileManager
    public JavaFileObject getJavaFileForOutput(Location location,
                                               String className,
                                               JavaFileObject.Kind kind,
                                               FileObject sibling)
        throws IOException
    {
        nullCheck(location);
        // validateClassName(className);
        nullCheck(className);
        nullCheck(kind);
        if (!sourceOrClass.contains(kind))
            throw new IllegalArgumentException("Invalid kind: " + kind);
        return getFileForOutput(location, RelativeFile.forClass(className, kind), sibling);
    }

    @Override // javax.tools.JavaFileManager
    public FileObject getFileForOutput(Location location,
                                       String packageName,
                                       String relativeName,
                                       FileObject sibling)
        throws IOException
    {
        nullCheck(location);
        // validatePackageName(packageName);
        nullCheck(packageName);
        if (!isRelativeUri(relativeName))
            throw new IllegalArgumentException("Invalid relative name: " + relativeName);
        RelativeFile name = packageName.length() == 0
            ? new RelativeFile(relativeName)
            : new RelativeFile(RelativeDirectory.forPackage(packageName), relativeName);
        return getFileForOutput(location, name, sibling);
    }

    // FIXME: compare against tl
    private JavaFileObject getFileForOutput(Location location,
                                            RelativeFile fileName,
                                            FileObject sibling)
        throws IOException
    {
        File dir;
        if (location == CLASS_OUTPUT) {
            if (getClassOutDir() != null) {
                dir = getClassOutDir();
            } else {
                File siblingDir = null;
                if (sibling != null && sibling instanceof RegularFileObject) {
                    siblingDir = ((RegularFileObject)sibling).file.getParentFile();
                }
                return new RegularFileObject(this, location, new File(siblingDir, fileName.basename()));
            }
        } else if (location == SOURCE_OUTPUT) {
            dir = (getSourceOutDir() != null ? getSourceOutDir() : getClassOutDir());
        } else {
            dir = null;
            Iterable<? extends File> path = locations.getLocation(location);
            if (path != null) {
                for (File e: path) {
                    dir = e;
                    break;
                }
            }
            //System.err.println("JavacFileManager.getFileForOutput location:" + location + " path:" + toString(path) + " dir:" + dir);
        }

        File file = fileName.getFile(dir); // null-safe
        return new RegularFileObject(this, location, file);

    }

    @Override // javax.tools.StandardJavaFileManager
    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(
        Iterable<? extends File> files)
    {
        ArrayList<RegularFileObject> result;
        if (files instanceof Collection<?>)
            result = new ArrayList<RegularFileObject>(((Collection<?>)files).size());
        else
            result = new ArrayList<RegularFileObject>();
        for (File f: files)
            result.add(new RegularFileObject(this, null, nullCheck(f)));
        return result;
    }

    @Override // javax.tools.StandardJavaFileManager
    public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
        return getJavaFileObjectsFromFiles(Arrays.asList(nullCheck(files)));
    }

    @Override // javax.tools.StandardJavaFileManager
    public void setLocation(Location location,
                            Iterable<? extends File> path)
        throws IOException
    {
        nullCheck(location);
        locations.setLocation(location, path);
        locationCache.clear();
    }

    @Override // javax.tools.StandardJavaFileManager
    public Iterable<File> getLocation(Location location) {
        return locations.getLocation(location);
    }

    @Deprecated // remove uses of this method
    Iterable<File> getEntriesForLocation(Location location) {
        nullCheck(location);
        return locations.getLocation(location);
    }

    private File getClassOutDir() {
        return locations.getOutputLocation(CLASS_OUTPUT);
    }

    private File getSourceOutDir() {
        return locations.getOutputLocation(SOURCE_OUTPUT);
    }

    /**
     * Enforces the specification of a "relative" URI as used in
     * {@linkplain #getFileForInput(Location,String,URI)
     * getFileForInput}.  This method must follow the rules defined in
     * that method, do not make any changes without consulting the
     * specification.
     */
    protected static boolean isRelativeUri(URI uri) {
        if (uri.isAbsolute())
            return false;
        String path = uri.normalize().getPath();
        if (path.length() == 0 /* isEmpty() is mustang API */)
            return false;
        if (!path.equals(uri.getPath())) // implicitly checks for embedded . and ..
            return false;
        if (path.startsWith("/") || path.startsWith("./") || path.startsWith("../"))
            return false;
        return true;
    }

    // Convenience method
    protected static boolean isRelativeUri(String u) {
        try {
            return isRelativeUri(new URI(u));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Converts a relative file name to a relative URI.  This is
     * different from File.toURI as this method does not canonicalize
     * the file before creating the URI.  Furthermore, no schema is
     * used.
     * @param file a relative file name
     * @return a relative URI
     * @throws IllegalArgumentException if the file name is not
     * relative according to the definition given in {@link
     * javax.tools.JavaFileManager#getFileForInput}
     */
    public static String getRelativeName(File file) {
        if (!file.isAbsolute()) {
            String result = file.getPath().replace(File.separatorChar, '/');
            if (isRelativeUri(result))
                return result;
        }
        throw new IllegalArgumentException("Invalid relative path: " + file);
    }

    /**
     * Get a detail message from an IOException.
     * Most, but not all, instances of IOException provide a non-null result
     * for getLocalizedMessage().  But some instances return null: in these
     * cases, fallover to getMessage(), and if even that is null, return the
     * name of the exception itself.
     * @param e an IOException
     * @return a string to include in a compiler diagnostic
     */
    public static String getMessage(IOException e) {
        String s = e.getLocalizedMessage();
        if (s != null)
            return s;
        s = e.getMessage();
        if (s != null)
            return s;
        return e.toString();
    }
}
